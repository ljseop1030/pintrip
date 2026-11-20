package com.pintrip.app.place

import androidx.annotation.StringRes
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.firestore.FirebaseFirestoreException
import com.pintrip.app.R
import com.pintrip.app.data.model.PlaceDetail
import com.pintrip.app.data.repository.PlaceDetailRepository
import com.pintrip.app.data.repository.RoomRepository
import com.pintrip.app.ui.common.Event
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Random

/**
 * 흔들어서 고르기 기능의 비즈니스 로직 및 상태를 관리하는 ViewModel.
 *
 * docs/data-contract.md §4 및 AGENTS.md 규격을 준수합니다:
 * 1. [roomRepository.getRoom]으로 방 지역 정보(room.region)를 얻어 [placeDetailRepository.getPlaceDetails]를 호출합니다.
 * 2. 카카오 재조회 결과 및 추첨 결과는 ViewModel 메모리에만 유지합니다.
 * 3. status == [PlaceDetail.STATUS_FOUND] 인 장소만 추첨 후보로 사용하며 notFound는 제외합니다.
 * 4. 선택된 카테고리 필터(ALL, dining, cafe, sights, stay, etc) 내에서 균등 무작위로 1개를 추첨하며 각 추첨은 독립적입니다.
 * 5. 카테고리 필터 변경 시 Firestore/Kakao 재호출 없이 메모리 상에서만 필터링하며, 이전 추첨 결과는 제거합니다.
 * 6. 동일 roomId의 중복 요청은 진행 중 및 로드 완료 시 건너뛰고, retry 요청 시 재조회합니다.
 * 7. 저장된 장소 0개(NO_SAVED_PLACES), found 장소 0개(NO_AVAILABLE_PLACES), 필터 조건 장소 0개(NO_FILTER_MATCHES)를 명확히 구분합니다.
 */
class ShakeToPickViewModel(
    private val roomRepository: RoomRepository = RoomRepository(),
    private val placeDetailRepository: PlaceDetailRepository = PlaceDetailRepository(),
    private val random: Random = Random(),
) : ViewModel() {

    private val _uiState = MutableLiveData<ShakeToPickUiState>(ShakeToPickUiState.Idle)
    val uiState: LiveData<ShakeToPickUiState> = _uiState

    private val _shakeEvent = MutableLiveData<Event<Unit>>()
    val shakeEvent: LiveData<Event<Unit>> = _shakeEvent

    private var currentRoomId: String? = null
    private var currentRequestId: Long = 0L
    private var currentCategory: PlaceCategory = PlaceCategory.ALL
    private var loadedPlaces: List<PlaceDetail> = emptyList()
    private var loadJob: Job? = null

    /**
     * 지정한 방(roomId)의 장소 목록을 로드하고 카카오 재조회를 수행합니다.
     *
     * @param roomId 대상 여행방 ID
     * @param forceRefresh true 시 진행 중이거나 기존에 성공한 조회를 무시하고 새로 재조회합니다.
     */
    fun loadPlaces(roomId: String, forceRefresh: Boolean = false) {
        if (roomId.isBlank()) {
            loadJob?.cancel()
            loadJob = null
            currentRoomId = null
            currentRequestId++
            loadedPlaces = emptyList()
            _uiState.value = ShakeToPickUiState.Error(R.string.place_list_error_no_room_id)
            return
        }

        if (loadJob?.isActive == true) {
            if (!forceRefresh && currentRoomId == roomId) {
                return
            }
            loadJob?.cancel()
        } else if (!forceRefresh && currentRoomId == roomId && isSuccessfullyLoadedState()) {
            return
        }

        currentRoomId = roomId
        currentRequestId++
        val targetRequestId = currentRequestId
        _uiState.value = ShakeToPickUiState.Loading(currentCategory)

        loadJob = viewModelScope.launch {
            try {
                val room = roomRepository.getRoom(roomId)
                if (currentRequestId != targetRequestId) return@launch
                if (room == null) {
                    _uiState.value = ShakeToPickUiState.Error(R.string.place_list_error_no_room_id)
                    return@launch
                }

                val placeDetails = placeDetailRepository.getPlaceDetails(roomId, room.region)
                if (currentRequestId != targetRequestId) return@launch

                loadedPlaces = placeDetails
                updateUiStateAfterLoad()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (currentRequestId != targetRequestId) return@launch

                @StringRes val errorResId = when (e) {
                    is FirebaseFirestoreException -> {
                        when (e.code) {
                            FirebaseFirestoreException.Code.PERMISSION_DENIED -> R.string.error_room_permission_denied
                            FirebaseFirestoreException.Code.UNAVAILABLE,
                            FirebaseFirestoreException.Code.DEADLINE_EXCEEDED -> R.string.error_network_unavailable
                            else -> R.string.place_list_error_default
                        }
                    }
                    else -> R.string.place_list_error_default
                }
                _uiState.value = ShakeToPickUiState.Error(errorResId)
            }
        }
    }

    /**
     * 카테고리 필터를 변경합니다.
     *
     * 네트워크 재요청 없이 메모리의 로드된 장소에서만 필터를 적용하며 이전 추첨 결과는 정리합니다.
     */
    fun selectCategory(category: PlaceCategory) {
        if (currentCategory == category && _uiState.value !is ShakeToPickUiState.Idle) {
            return
        }
        currentCategory = category

        if (isSuccessfullyLoadedState()) {
            updateUiStateAfterLoad()
        } else if (_uiState.value is ShakeToPickUiState.Loading) {
            _uiState.value = ShakeToPickUiState.Loading(category)
        }
    }

    /**
     * 흔들기 감지 또는 터치 클릭 시 현재 카테고리 필터에 맞는 후보 장소 중 무작위 1개를 추첨합니다.
     */
    fun pickRandomPlace() {
        val currentState = _uiState.value
        if (currentState !is ShakeToPickUiState.Ready && currentState !is ShakeToPickUiState.Result) {
            return
        }

        val totalCount = loadedPlaces.size
        val foundPlaces = loadedPlaces.filter { it.status == PlaceDetail.STATUS_FOUND }
        val availableCount = foundPlaces.size
        val filteredPlaces = foundPlaces.filter { currentCategory.matches(it.category) }
        val filteredCount = filteredPlaces.size

        if (filteredPlaces.isEmpty()) {
            return
        }

        val selectedIndex = random.nextInt(filteredPlaces.size)
        val selectedPlace = filteredPlaces[selectedIndex]

        _uiState.value = ShakeToPickUiState.Result(
            selectedPlace = selectedPlace,
            category = currentCategory,
            totalCount = totalCount,
            availableCount = availableCount,
            filteredCount = filteredCount,
        )
        _shakeEvent.value = Event(Unit)
    }

    /**
     * 실패 또는 현재 방의 장소 목록을 명시적으로 재시도(재조회)합니다.
     */
    fun retry() {
        val roomId = currentRoomId
        if (!roomId.isNullOrBlank()) {
            loadPlaces(roomId, forceRefresh = true)
        }
    }

    private fun updateUiStateAfterLoad() {
        val totalCount = loadedPlaces.size
        if (totalCount == 0) {
            _uiState.value = ShakeToPickUiState.Empty(
                reason = EmptyReason.NO_SAVED_PLACES,
                category = currentCategory,
                totalCount = 0,
                availableCount = 0,
            )
            return
        }

        val foundPlaces = loadedPlaces.filter { it.status == PlaceDetail.STATUS_FOUND }
        val availableCount = foundPlaces.size
        if (availableCount == 0) {
            _uiState.value = ShakeToPickUiState.Empty(
                reason = EmptyReason.NO_AVAILABLE_PLACES,
                category = currentCategory,
                totalCount = totalCount,
                availableCount = 0,
            )
            return
        }

        val filteredPlaces = foundPlaces.filter { currentCategory.matches(it.category) }
        val filteredCount = filteredPlaces.size
        if (filteredCount == 0) {
            _uiState.value = ShakeToPickUiState.Empty(
                reason = EmptyReason.NO_FILTER_MATCHES,
                category = currentCategory,
                totalCount = totalCount,
                availableCount = availableCount,
            )
            return
        }

        _uiState.value = ShakeToPickUiState.Ready(
            category = currentCategory,
            totalCount = totalCount,
            availableCount = availableCount,
            filteredCount = filteredCount,
        )
    }

    private fun isSuccessfullyLoadedState(): Boolean {
        val state = _uiState.value
        return state is ShakeToPickUiState.Ready ||
                state is ShakeToPickUiState.Result ||
                state is ShakeToPickUiState.Empty
    }
}

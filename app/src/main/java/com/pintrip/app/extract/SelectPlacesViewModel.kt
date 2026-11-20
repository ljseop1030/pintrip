package com.pintrip.app.extract

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.pintrip.app.R
import com.pintrip.app.data.model.Place
import com.pintrip.app.data.repository.PlaceRepository
import com.pintrip.app.data.repository.UserRepository
import com.pintrip.app.ui.common.Event
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

data class RegionMismatchNotice(
    val mismatchCount: Int,
    val roomRegion: String,
)

data class SavePlacesResultState(
    val addedCount: Int,
    val skippedCount: Int,
    val excludedCount: Int = 0,
    val notFoundCount: Int = 0,
)

/**
 * [SelectPlacesActivity]의 화면 상태(매칭 결과 목록, 체크 여부, 선택된 후보)를 관리하는 ViewModel.
 *
 * docs/data-contract.md §9 규격을 준수합니다:
 * 1. CHECK 항목은 사용자가 후보를 고르기 전까지 미선택(selectedPlaceId = null)으로 유지하며, 저장 시 확인 팝업 후 제외합니다.
 * 2. 자동 선택된 후보의 주소로 기존 [isCandidateAddressOutsideRoomRegion] 함수를 재사용하여 [regionMismatch] 및 초기 [checked] 상태를 재판정합니다.
 * 3. Firestore `rooms/{roomId}`에서 방 이름 및 대표 지역을 단건 조회합니다.
 * 4. 진입 시 지역 불일치([MatchResult.regionMismatch]) 항목이 존재하는 경우 1회성 팝업 이벤트를 발행합니다.
 * 5. 선택된 항목을 [Place] 객체로 변환하여 [PlaceRepository.addPlaces]를 호출해 Firestore에 저장합니다.
 * 6. 닉네임 조회가 불가한 경우 기본값 할당 없이 저장을 중단하고 프로필 조회 실패 에러 이벤트를 전달합니다.
 * 7. 화면 회전(Configuration Change) 시에도 사용자의 선택/체크 상태 및 조회된 방 데이터가 유지됩니다.
 */
class SelectPlacesViewModel(
    private val db: FirebaseFirestore? = null,
    private val auth: FirebaseAuth? = null,
    private val placeRepository: PlaceRepository = PlaceRepository(db = db ?: FirebaseFirestore.getInstance(), auth = auth),
    private val userRepository: UserRepository = UserRepository(db = db ?: FirebaseFirestore.getInstance(), auth = auth),
    private val currentUidProvider: () -> String? = { FirebaseAuth.getInstance().currentUser?.uid },
) : ViewModel() {

    private val _roomId = MutableLiveData<String>()
    val roomId: LiveData<String> = _roomId

    private val _roomName = MutableLiveData<String>()
    val roomName: LiveData<String> = _roomName

    private val _roomRegion = MutableLiveData<String>()
    val roomRegion: LiveData<String> = _roomRegion

    private val _matchResults = MutableLiveData<List<MatchResult>>()
    val matchResults: LiveData<List<MatchResult>> = _matchResults

    private val _regionMismatchNotice = MutableLiveData<Event<RegionMismatchNotice>>()
    val regionMismatchNotice: LiveData<Event<RegionMismatchNotice>> = _regionMismatchNotice

    private val _unconfirmedCheckNotice = MutableLiveData<Event<Int>>()
    val unconfirmedCheckNotice: LiveData<Event<Int>> = _unconfirmedCheckNotice

    private val _noticeEvent = MutableLiveData<Event<ShareNoticeEvent>>()
    val noticeEvent: LiveData<Event<ShareNoticeEvent>> = _noticeEvent

    private val _isSaving = MutableLiveData(false)
    val isSaving: LiveData<Boolean> = _isSaving

    private val _saveResultEvent = MutableLiveData<Event<SavePlacesResultState>>()
    val saveResultEvent: LiveData<Event<SavePlacesResultState>> = _saveResultEvent

    private var isInitialized = false

    fun init(roomIdInput: String, initialResults: List<MatchResult>) {
        if (isInitialized) return
        isInitialized = true

        _roomId.postValue(roomIdInput)

        // Firestore rooms/{roomId} 단건 읽기 (방 이름, 대표 지역 조회)
        viewModelScope.launch {
            try {
                var roomRegionStr = ""
                if (roomIdInput.isNotBlank()) {
                    val firestore = db ?: FirebaseFirestore.getInstance()
                    val snapshot = firestore.collection("rooms").document(roomIdInput).get().await()
                    if (snapshot.exists()) {
                        val name = snapshot.getString("name").orEmpty()
                        val region = snapshot.getString("region").orEmpty()
                        _roomName.postValue(name)
                        _roomRegion.postValue(region)
                        roomRegionStr = region
                    }
                }

                _matchResults.postValue(initialResults)

                // 지역 불일치 안내 (docs/data-contract.md §9): 진입 시 n개 장소가 방 지역 밖임을 팝업 이벤트로 전달
                val mismatchCount = initialResults.count { it.regionMismatch }
                if (mismatchCount > 0 && roomRegionStr.isNotBlank()) {
                    _regionMismatchNotice.postValue(
                        Event(
                            RegionMismatchNotice(
                                mismatchCount = mismatchCount,
                                roomRegion = roomRegionStr,
                            ),
                        ),
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load room details for roomId=$roomIdInput: ${e.message}", e)
                _matchResults.postValue(initialResults)
            }
        }
    }

    fun toggleCheck(index: Int, isChecked: Boolean) {
        val currentList = _matchResults.value?.toMutableList() ?: return
        if (index in currentList.indices) {
            val item = currentList[index]
            if (item.status != MatchResult.STATUS_NOT_FOUND) {
                currentList[index] = item.copy(checked = isChecked)
                _matchResults.postValue(currentList)
            }
        }
    }

    fun updateItemAt(index: Int, updatedMatchResult: MatchResult) {
        val currentList = _matchResults.value?.toMutableList() ?: return
        if (index in currentList.indices) {
            currentList[index] = updatedMatchResult
            _matchResults.postValue(currentList)
        }
    }

    fun getConfirmableCheckedCount(): Int {
        val currentList = _matchResults.value ?: return 0
        return currentList.count { it.isConfirmable }
    }

    fun getUnconfirmedCheckedCount(): Int {
        val currentList = _matchResults.value ?: return 0
        return currentList.count { it.checked && it.status == MatchResult.STATUS_CHECK && it.selectedPlaceId == null }
    }

    fun getNotFoundCount(): Int {
        val currentList = _matchResults.value ?: return 0
        return currentList.count { it.status == MatchResult.STATUS_NOT_FOUND }
    }

    fun hasAnyCheckedItems(): Boolean {
        val currentList = _matchResults.value ?: return false
        return currentList.any { it.checked && it.status != MatchResult.STATUS_NOT_FOUND }
    }

    /**
     * 체크된 유효 장소 목록을 [Place] 모델로 변환하여 [PlaceRepository.addPlaces]를 호출해 저장합니다.
     */
    fun saveSelectedPlaces(skipUnconfirmed: Boolean = false) {
        if (_isSaving.value == true) return
        val currentRoomId = _roomId.value.orEmpty()
        if (currentRoomId.isBlank()) return

        val notFoundCount = getNotFoundCount()
        val unconfirmedCount = getUnconfirmedCheckedCount()
        if (unconfirmedCount > 0 && !skipUnconfirmed) {
            _unconfirmedCheckNotice.postValue(Event(unconfirmedCount))
            return
        }

        val excludedCount = if (skipUnconfirmed) unconfirmedCount else 0
        val confirmableItems = _matchResults.value?.filter { it.isConfirmable } ?: emptyList()

        if (confirmableItems.isEmpty()) {
            _saveResultEvent.postValue(
                Event(
                    SavePlacesResultState(
                        addedCount = 0,
                        skippedCount = 0,
                        excludedCount = excludedCount,
                        notFoundCount = notFoundCount,
                    ),
                ),
            )
            return
        }

        _isSaving.postValue(true)

        viewModelScope.launch {
            try {
                val uid = currentUidProvider()
                if (uid == null) {
                    Log.w(TAG, "Auth currentUser is null, stopping save")
                    _isSaving.postValue(false)
                    _noticeEvent.postValue(
                        Event(
                            ShareNoticeEvent.ToastNotice(
                                messageResId = R.string.error_not_logged_in,
                            ),
                        ),
                    )
                    return@launch
                }
                val userProfile = try {
                    userRepository.getUserProfile(uid)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to get user profile for uid=$uid: ${e.message}", e)
                    null
                }

                val nickname = userProfile?.nickname?.trim().orEmpty()
                if (nickname.isBlank()) {
                    Log.w(TAG, "Nickname is blank for uid=$uid, stopping save")
                    _isSaving.postValue(false)
                    _noticeEvent.postValue(
                        Event(
                            ShareNoticeEvent.ToastNotice(
                                messageResId = R.string.error_profile_load_failed,
                            ),
                        ),
                    )
                    return@launch
                }

                val placesToSave = confirmableItems.mapNotNull { item ->
                    val selectedCand = item.selectedCandidate ?: return@mapNotNull null
                    val searchRegionVal = item.extracted.region?.trim()?.ifBlank { null }

                    val matchTypeVal = when (item.status) {
                        MatchResult.STATUS_MATCHED -> "auto"
                        else -> "confirmed"
                    }

                    Place(
                        kakaoPlaceId = selectedCand.kakaoPlaceId,
                        kakaoPlaceUrl = selectedCand.kakaoPlaceUrl,
                        searchKeyword = item.extracted.name,
                        searchRegion = searchRegionVal,
                        matchType = matchTypeVal,
                        sourceType = item.sourceType,
                        sourceUrl = item.sourceUrl,
                        addedBy = uid,
                        addedByNickname = nickname,
                        likedBy = emptyList(),
                        likeCount = 0,
                        createdAt = null,
                    )
                }

                val result = placeRepository.addPlaces(currentRoomId, placesToSave)
                Log.d(TAG, "Saved places successfully: added=${result.addedCount}, skipped=${result.skippedCount}, excluded=$excludedCount, notFound=$notFoundCount")

                _isSaving.postValue(false)
                _saveResultEvent.postValue(
                    Event(
                        SavePlacesResultState(
                            addedCount = result.addedCount,
                            skippedCount = result.skippedCount,
                            excludedCount = excludedCount,
                            notFoundCount = notFoundCount,
                        ),
                    ),
                )

            } catch (e: Exception) {
                Log.e(TAG, "Failed to save selected places: ${e.message}", e)
                _isSaving.postValue(false)
                _noticeEvent.postValue(
                    Event(
                        ShareNoticeEvent.ToastNotice(
                            messageResId = R.string.select_places_save_error,
                        ),
                    ),
                )
            }
        }
    }

    companion object {
        private const val TAG = "SelectPlacesViewModel"
    }
}

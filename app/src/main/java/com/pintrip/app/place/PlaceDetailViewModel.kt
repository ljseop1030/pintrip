package com.pintrip.app.place

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.pintrip.app.R
import com.pintrip.app.data.model.PlaceDetail
import com.pintrip.app.data.model.Room
import com.pintrip.app.data.repository.PlaceDetailRepository
import com.pintrip.app.data.repository.PlaceRepository
import com.pintrip.app.data.repository.RoomRepository
import com.pintrip.app.ui.common.Event
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

data class LikedUserInfo(
    val uid: String,
    val nickname: String,
    val avatarColor: String?,
)

data class AddedByUserInfo(
    val nickname: String,
    val avatarColor: String?,
)

sealed class PlaceDetailUiState {
    object Loading : PlaceDetailUiState()

    data class Success(
        val placeDetail: PlaceDetail,
        val likedUsers: List<LikedUserInfo>,
        val addedByUserInfo: AddedByUserInfo,
        val formattedCreatedAt: String,
        val categoryResId: Int,
        val isLikedByMe: Boolean = false,
        val isLikeInProgress: Boolean = false,
    ) : PlaceDetailUiState()

    data class Error(val messageResId: Int) : PlaceDetailUiState()

    object NotFoundInFirestore : PlaceDetailUiState()
}

class PlaceDetailViewModel(
    private val roomRepository: RoomRepository = RoomRepository(),
    private val placeDetailRepository: PlaceDetailRepository = PlaceDetailRepository(),
    private val placeRepository: PlaceRepository = PlaceRepository(),
    private val currentUidProvider: () -> String? = { FirebaseAuth.getInstance().currentUser?.uid },
) : ViewModel() {

    private val _uiState = MutableLiveData<PlaceDetailUiState>()
    val uiState: LiveData<PlaceDetailUiState> = _uiState

    private val _toastEvent = MutableLiveData<Event<Int>>()
    val toastEvent: LiveData<Event<Int>> = _toastEvent

    private var isLoading = false
    private var isDataLoaded = false
    private var isObservingStarted = false

    private var cachedRoom: Room? = null
    private var currentRoomId: String? = null
    private var currentKakaoPlaceId: String? = null

    fun loadPlaceDetail(roomId: String, kakaoPlaceId: String) {
        if (isDataLoaded || isLoading) return
        isLoading = true
        currentRoomId = roomId
        currentKakaoPlaceId = kakaoPlaceId

        _uiState.value = PlaceDetailUiState.Loading

        viewModelScope.launch {
            try {
                val room = roomRepository.getRoom(roomId)
                if (room == null) {
                    isLoading = false
                    _uiState.value = PlaceDetailUiState.Error(R.string.room_map_error_room_not_found)
                    return@launch
                }
                cachedRoom = room

                val placeDetail = placeDetailRepository.getPlaceDetail(roomId, room.region, kakaoPlaceId)
                if (placeDetail == null) {
                    isLoading = false
                    _uiState.value = PlaceDetailUiState.NotFoundInFirestore
                    return@launch
                }

                val likedUsers = placeDetail.place.likedBy.mapNotNull { uid ->
                    val member = room.members[uid]
                    if (member != null) {
                        LikedUserInfo(
                            uid = uid,
                            nickname = member.nickname,
                            avatarColor = member.avatarColor.ifBlank { null }
                        )
                    } else {
                        null
                    }
                }

                val addedByMember = room.members[placeDetail.place.addedBy]
                val addedByUserInfo = if (addedByMember != null) {
                    AddedByUserInfo(
                        nickname = addedByMember.nickname.ifBlank { placeDetail.place.addedByNickname },
                        avatarColor = addedByMember.avatarColor.ifBlank { null }
                    )
                } else {
                    AddedByUserInfo(
                        nickname = placeDetail.place.addedByNickname,
                        avatarColor = null
                    )
                }

                val formattedDate = formatCreatedAt(placeDetail.place.createdAt)
                val categoryResId = getCategoryResId(placeDetail.category)

                val currentUid = currentUidProvider()
                val isLikedByMe = if (currentUid != null) placeDetail.place.likedBy.contains(currentUid) else false

                isDataLoaded = true
                isLoading = false

                _uiState.value = PlaceDetailUiState.Success(
                    placeDetail = placeDetail,
                    likedUsers = likedUsers,
                    addedByUserInfo = addedByUserInfo,
                    formattedCreatedAt = formattedDate,
                    categoryResId = categoryResId,
                    isLikedByMe = isLikedByMe,
                    isLikeInProgress = false,
                )

                startObservingPlace(roomId, kakaoPlaceId)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load place detail: ${e.message}", e)
                isLoading = false
                isDataLoaded = false
                _uiState.value = PlaceDetailUiState.Error(R.string.room_map_error_load_failed)
            }
        }
    }

    private fun startObservingPlace(roomId: String, kakaoPlaceId: String) {
        if (isObservingStarted) return
        isObservingStarted = true

        viewModelScope.launch {
            placeRepository.observePlace(roomId, kakaoPlaceId)
                .catch { e ->
                    Log.w(TAG, "observePlace error: ${e.message}", e)
                }
                .collect { updatedPlace ->
                    if (updatedPlace == null) {
                        _uiState.value = PlaceDetailUiState.NotFoundInFirestore
                        return@collect
                    }

                    val currentSuccess = _uiState.value as? PlaceDetailUiState.Success ?: return@collect
                    val room = cachedRoom ?: return@collect

                    val updatedPlaceDetail = currentSuccess.placeDetail.copy(place = updatedPlace)
                    val updatedLikedUsers = updatedPlace.likedBy.mapNotNull { uid ->
                        val member = room.members[uid]
                        if (member != null) {
                            LikedUserInfo(
                                uid = uid,
                                nickname = member.nickname,
                                avatarColor = member.avatarColor.ifBlank { null }
                            )
                        } else null
                    }

                    val currentUid = currentUidProvider()
                    val isLikedByMe = if (currentUid != null) updatedPlace.likedBy.contains(currentUid) else false

                    _uiState.value = currentSuccess.copy(
                        placeDetail = updatedPlaceDetail,
                        likedUsers = updatedLikedUsers,
                        isLikedByMe = isLikedByMe,
                    )
                }
        }
    }

    fun toggleLike() {
        val currentState = _uiState.value as? PlaceDetailUiState.Success ?: return
        if (currentState.isLikeInProgress) return

        val roomId = currentRoomId ?: return
        val kakaoPlaceId = currentKakaoPlaceId ?: return

        _uiState.value = currentState.copy(isLikeInProgress = true)

        viewModelScope.launch {
            try {
                placeRepository.toggleLike(roomId, kakaoPlaceId)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to toggle like: ${e.message}", e)
                _toastEvent.value = Event(R.string.place_detail_like_failed)
            } finally {
                val latestState = _uiState.value as? PlaceDetailUiState.Success
                if (latestState != null) {
                    _uiState.value = latestState.copy(isLikeInProgress = false)
                }
            }
        }
    }

    fun retry(roomId: String, kakaoPlaceId: String) {
        isDataLoaded = false
        isLoading = false
        isObservingStarted = false
        cachedRoom = null
        loadPlaceDetail(roomId, kakaoPlaceId)
    }

    private fun formatCreatedAt(timestamp: Timestamp?): String {
        if (timestamp == null) return ""
        val date = timestamp.toDate()
        val sdf = SimpleDateFormat("MM/dd", Locale.getDefault())
        return sdf.format(date)
    }

    private fun getCategoryResId(categoryCode: String?): Int {
        return when (categoryCode) {
            PlaceCategory.DINING.code -> R.string.category_dining
            PlaceCategory.CAFE.code -> R.string.category_cafe
            PlaceCategory.SIGHTS.code -> R.string.category_sights
            PlaceCategory.STAY.code -> R.string.category_stay
            else -> R.string.category_etc
        }
    }

    companion object {
        private const val TAG = "PlaceDetailViewModel"
    }
}

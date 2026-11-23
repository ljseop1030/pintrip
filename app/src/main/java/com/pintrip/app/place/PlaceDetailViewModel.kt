package com.pintrip.app.place

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.Timestamp
import com.pintrip.app.R
import com.pintrip.app.data.model.PlaceDetail
import com.pintrip.app.data.repository.PlaceDetailRepository
import com.pintrip.app.data.repository.RoomRepository
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
    ) : PlaceDetailUiState()

    data class Error(val messageResId: Int) : PlaceDetailUiState()

    object NotFoundInFirestore : PlaceDetailUiState()
}

class PlaceDetailViewModel(
    private val roomRepository: RoomRepository = RoomRepository(),
    private val placeDetailRepository: PlaceDetailRepository = PlaceDetailRepository(),
) : ViewModel() {

    private val _uiState = MutableLiveData<PlaceDetailUiState>()
    val uiState: LiveData<PlaceDetailUiState> = _uiState

    private var isLoading = false
    private var isDataLoaded = false

    fun loadPlaceDetail(roomId: String, kakaoPlaceId: String) {
        if (isDataLoaded || isLoading) return
        isLoading = true

        _uiState.value = PlaceDetailUiState.Loading

        viewModelScope.launch {
            try {
                val room = roomRepository.getRoom(roomId)
                if (room == null) {
                    isLoading = false
                    _uiState.value = PlaceDetailUiState.Error(R.string.room_map_error_room_not_found)
                    return@launch
                }

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

                isDataLoaded = true
                isLoading = false

                _uiState.value = PlaceDetailUiState.Success(
                    placeDetail = placeDetail,
                    likedUsers = likedUsers,
                    addedByUserInfo = addedByUserInfo,
                    formattedCreatedAt = formattedDate,
                    categoryResId = categoryResId,
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load place detail: ${e.message}", e)
                isLoading = false
                isDataLoaded = false
                _uiState.value = PlaceDetailUiState.Error(R.string.room_map_error_load_failed)
            }
        }
    }

    fun retry(roomId: String, kakaoPlaceId: String) {
        isDataLoaded = false
        isLoading = false
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

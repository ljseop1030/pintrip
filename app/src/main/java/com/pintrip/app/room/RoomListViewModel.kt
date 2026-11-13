package com.pintrip.app.room

import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Source
import com.pintrip.app.BuildConfig
import com.pintrip.app.R
import com.pintrip.app.data.model.Room
import com.pintrip.app.data.repository.RoomRepository
import com.pintrip.app.data.repository.UserRepository
import com.pintrip.app.ui.common.Event
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

sealed class RoomListUiState {
    object Loading : RoomListUiState()
    object Empty : RoomListUiState()
    data class Success(val rooms: List<Room>) : RoomListUiState()
    data class Error(@get:StringRes val messageResId: Int) : RoomListUiState()
}

class RoomListViewModel(
    private val roomRepository: RoomRepository = RoomRepository(),
    private val userRepository: UserRepository = UserRepository(),
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
) : ViewModel() {

    companion object {
        private const val TAG = "RoomListViewModel"
    }

    private val _uiState = MutableLiveData<RoomListUiState>()
    val uiState: LiveData<RoomListUiState> = _uiState

    private val _nickname = MutableLiveData<String>()
    val nickname: LiveData<String> = _nickname

    private val _avatarColor = MutableLiveData<String>()
    val avatarColor: LiveData<String> = _avatarColor

    private val _avatarText = MutableLiveData<String>()
    val avatarText: LiveData<String> = _avatarText

    private val _navigateToCreateRoom = MutableLiveData<Event<Unit>>()
    val navigateToCreateRoom: LiveData<Event<Unit>> = _navigateToCreateRoom

    private val _navigateToPlaceList = MutableLiveData<Event<String>>()
    val navigateToPlaceList: LiveData<Event<String>> = _navigateToPlaceList

    private val _toastEvent = MutableLiveData<Event<Int>>()
    val toastEvent: LiveData<Event<Int>> = _toastEvent

    private var isDataLoaded = false
    private var isNavigating = false
    private var loadJob: Job? = null

    fun loadRooms(forceRefresh: Boolean = false) {
        if (loadJob?.isActive == true) {
            if (!forceRefresh) return
            loadJob?.cancel()
        } else if (isDataLoaded && !forceRefresh) {
            return
        }

        val currentUser = auth.currentUser
        if (currentUser == null) {
            _uiState.value = RoomListUiState.Error(R.string.error_not_logged_in)
            return
        }

        _uiState.value = RoomListUiState.Loading

        loadJob = viewModelScope.launch {
            try {
                // 1. 프로필 정보 로드
                val profile = userRepository.getUserProfile(currentUser.uid, source = Source.DEFAULT)
                if (profile != null && profile.nickname.isNotBlank()) {
                    _nickname.value = profile.nickname
                    _avatarColor.value = profile.avatarColor
                    _avatarText.value = profile.nickname.take(1).uppercase()
                } else {
                    throw IllegalStateException("사용자 프로필 정보를 불러올 수 없습니다.")
                }

                // 2. 내 방 목록 로드
                val rooms = roomRepository.getMyRooms(source = Source.DEFAULT)
                isDataLoaded = true

                if (rooms.isEmpty()) {
                    _uiState.value = RoomListUiState.Empty
                } else {
                    _uiState.value = RoomListUiState.Success(rooms)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: FirebaseFirestoreException) {
                if (BuildConfig.DEBUG) {
                    Log.e(TAG, "Failed to load rooms due to Firestore exception", e)
                }
                @StringRes val errorResId = when (e.code) {
                    FirebaseFirestoreException.Code.PERMISSION_DENIED -> R.string.error_room_permission_denied
                    FirebaseFirestoreException.Code.UNAVAILABLE -> R.string.error_network_unavailable
                    else -> R.string.error_room_list_default
                }
                _uiState.value = RoomListUiState.Error(errorResId)
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) {
                    Log.e(TAG, "Failed to load rooms due to unexpected exception", e)
                }
                _uiState.value = RoomListUiState.Error(R.string.error_room_list_default)
            }
        }
    }

    fun onCreateRoomClick() {
        if (isNavigating) return
        isNavigating = true
        _navigateToCreateRoom.value = Event(Unit)
    }

    fun onJoinCodeClick() {
        _toastEvent.value = Event(R.string.notice_join_code_feature_later)
    }

    fun onRoomCardClick(room: Room) {
        if (isNavigating) return

        if (room.id.isNotBlank()) {
            isNavigating = true
            _navigateToPlaceList.value = Event(room.id)
        } else {
            _toastEvent.value = Event(R.string.place_list_error_no_room_id)
        }
    }

    fun resetNavigation() {
        isNavigating = false
    }

    fun logout() {
        auth.signOut()
    }
}

package com.pintrip.app.room

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.Source
import com.pintrip.app.data.model.Room
import com.pintrip.app.data.repository.RoomRepository
import com.pintrip.app.data.repository.UserRepository
import com.pintrip.app.ui.common.Event
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

sealed class RoomListUiState {
    object Loading : RoomListUiState()
    object Empty : RoomListUiState()
    data class Success(val rooms: List<Room>) : RoomListUiState()
    data class Error(val message: String?) : RoomListUiState()
}

class RoomListViewModel(
    private val roomRepository: RoomRepository = RoomRepository(),
    private val userRepository: UserRepository = UserRepository(),
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
) : ViewModel() {

    private val _uiState = MutableLiveData<RoomListUiState>()
    val uiState: LiveData<RoomListUiState> = _uiState

    private val _nickname = MutableLiveData<String>()
    val nickname: LiveData<String> = _nickname

    private val _avatarText = MutableLiveData<String>()
    val avatarText: LiveData<String> = _avatarText

    private val _navigateToCreateRoom = MutableLiveData<Event<Unit>>()
    val navigateToCreateRoom: LiveData<Event<Unit>> = _navigateToCreateRoom

    private val _navigateToPlaceList = MutableLiveData<Event<String>>()
    val navigateToPlaceList: LiveData<Event<String>> = _navigateToPlaceList

    private val _toastEvent = MutableLiveData<Event<String>>()
    val toastEvent: LiveData<Event<String>> = _toastEvent

    private var isDataLoaded = false

    fun loadRooms(forceRefresh: Boolean = false) {
        if (isDataLoaded && !forceRefresh) return

        val currentUser = auth.currentUser
        if (currentUser == null) {
            _uiState.value = RoomListUiState.Error("로그인이 필요합니다.")
            return
        }

        _uiState.value = RoomListUiState.Loading

        viewModelScope.launch {
            try {
                // 1. 프로필 정보 로드
                try {
                    val profile = userRepository.getUserProfile(currentUser.uid, source = Source.DEFAULT)
                    val name = profile?.nickname
                    if (!name.isNullOrBlank()) {
                        _nickname.value = name
                        _avatarText.value = name.take(1).uppercase()
                    } else {
                        _nickname.value = "User"
                        _avatarText.value = "U"
                    }
                } catch (_: Exception) {
                    _nickname.value = "User"
                    _avatarText.value = "U"
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
            } catch (e: Exception) {
                val errorMsg = e.localizedMessage ?: "방 목록을 불러오지 못했습니다."
                _uiState.value = RoomListUiState.Error(errorMsg)
            }
        }
    }

    fun onCreateRoomClick() {
        _navigateToCreateRoom.value = Event(Unit)
    }

    fun onJoinCodeClick() {
        _toastEvent.value = Event("초대 코드로 참여하기 기능은 다음 업데이트에서 제공됩니다.")
    }

    fun onRoomCardClick(room: Room) {
        if (room.id.isNotBlank()) {
            _navigateToPlaceList.value = Event(room.id)
        } else {
            _toastEvent.value = Event("방 ID를 찾을 수 없습니다.")
        }
    }

    fun logout() {
        auth.signOut()
    }
}

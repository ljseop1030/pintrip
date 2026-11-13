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
import com.pintrip.app.data.repository.JoinRoomResult
import com.pintrip.app.data.repository.RoomJoinException
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
    private val auth: FirebaseAuth? = null,
) : ViewModel() {

    private val currentAuth: FirebaseAuth?
        get() = auth ?: try { FirebaseAuth.getInstance() } catch (_: Exception) { null }

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

    private val _navigateToLogin = MutableLiveData<Event<Unit>>()
    val navigateToLogin: LiveData<Event<Unit>> = _navigateToLogin

    private val _toastEvent = MutableLiveData<Event<Int>>()
    val toastEvent: LiveData<Event<Int>> = _toastEvent

    private val _isJoinDialogOpen = MutableLiveData<Boolean>(false)
    val isJoinDialogOpen: LiveData<Boolean> = _isJoinDialogOpen

    private val _joinCodeInput = MutableLiveData<String>("")
    val joinCodeInput: LiveData<String> = _joinCodeInput

    private val _isJoining = MutableLiveData<Boolean>(false)
    val isJoining: LiveData<Boolean> = _isJoining

    private val _joinErrorMessageResId = MutableLiveData<Int?>(null)
    val joinErrorMessageResId: LiveData<Int?> = _joinErrorMessageResId

    private val _joinSuccessResult = MutableLiveData<JoinRoomResult?>(null)
    val joinSuccessResult: LiveData<JoinRoomResult?> = _joinSuccessResult

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

        val currentUser = currentAuth?.currentUser
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
        if (isNavigating || _isJoining.value == true || _joinSuccessResult.value != null) return
        isNavigating = true
        _navigateToCreateRoom.value = Event(Unit)
    }

    fun onJoinCodeClick() {
        if (isNavigating || _isJoining.value == true || _joinSuccessResult.value != null) return
        _joinErrorMessageResId.value = null
        _isJoinDialogOpen.value = true
    }

    fun onJoinCodeInputChanged(input: String) {
        if (_joinCodeInput.value != input) {
            _joinCodeInput.value = input
        }
        if (_joinErrorMessageResId.value != null) {
            _joinErrorMessageResId.value = null
        }
    }

    fun onJoinDialogDismissed() {
        if (_isJoining.value == true) return
        _isJoinDialogOpen.value = false
        _joinErrorMessageResId.value = null
    }

    fun joinRoomWithCode(rawCode: String) {
        if (isNavigating || _isJoining.value == true || _joinSuccessResult.value != null) return

        val trimmedCode = rawCode.trim().uppercase()
        _joinCodeInput.value = rawCode
        if (!trimmedCode.matches(Regex("^[23456789ABCDEFGHJKLMNPQRSTUVWXYZ]{6}$"))) {
            _joinErrorMessageResId.value = R.string.error_invalid_invite_code_format
            return
        }

        _isJoining.value = true

        viewModelScope.launch {
            try {
                val result = roomRepository.joinRoomWithCode(trimmedCode)
                _isJoining.value = false
                when (result) {
                    is com.pintrip.app.data.repository.JoinRoomResult.Creator -> {
                        _joinErrorMessageResId.value = R.string.notice_join_own_room
                    }
                    is com.pintrip.app.data.repository.JoinRoomResult.ExistingMember -> {
                        _joinErrorMessageResId.value = R.string.notice_already_joined_room
                    }
                    is com.pintrip.app.data.repository.JoinRoomResult.NewJoin,
                    is com.pintrip.app.data.repository.JoinRoomResult.Rechecked -> {
                        _isJoinDialogOpen.value = false
                        _joinCodeInput.value = ""
                        _joinErrorMessageResId.value = null
                        loadRooms(forceRefresh = true)
                        _joinSuccessResult.value = result
                    }
                }
            } catch (e: CancellationException) {
                _isJoining.value = false
                throw e
            } catch (e: RoomJoinException) {
                if (BuildConfig.DEBUG) {
                    Log.e(TAG, "Failed to join room due to RoomJoinException", e)
                }
                _isJoining.value = false
                @StringRes val resId = when (e) {
                    is RoomJoinException.NotLoggedIn -> R.string.error_not_logged_in
                    is RoomJoinException.ProfileRequired -> R.string.error_profile_check_required
                    is RoomJoinException.InvalidCodeFormat -> R.string.error_invalid_invite_code_format
                    is RoomJoinException.CodeNotFound -> R.string.error_invite_code_not_found
                    is RoomJoinException.NetworkUnavailable -> R.string.error_network_unavailable
                    is RoomJoinException.PermissionDenied -> R.string.error_room_join_permission_denied
                    is RoomJoinException.Unknown -> R.string.error_room_join_default
                }
                _joinErrorMessageResId.value = resId
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) {
                    Log.e(TAG, "Failed to join room due to unexpected exception", e)
                }
                _isJoining.value = false
                _joinErrorMessageResId.value = R.string.error_room_join_default
            }
        }
    }

    fun onViewPlacesFromJoinSuccess(roomId: String) {
        if (isNavigating || _isJoining.value == true) return
        val currentResult = _joinSuccessResult.value
        val targetRoomId = currentResult?.room?.id ?: roomId
        if (targetRoomId.isNotBlank()) {
            isNavigating = true
            _navigateToPlaceList.value = Event(targetRoomId)
        } else {
            _toastEvent.value = Event(R.string.place_list_error_cannot_open)
        }
    }

    fun onPlaceListLaunchSuccess() {
        _joinSuccessResult.value = null
    }

    fun onPlaceListLaunchFailed() {
        isNavigating = false
        _toastEvent.value = Event(R.string.place_list_error_cannot_open)
    }

    fun onDismissJoinSuccess() {
        _joinSuccessResult.value = null
    }

    fun onRoomCardClick(room: Room) {
        if (isNavigating || _isJoining.value == true || _joinSuccessResult.value != null) return

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
        if (isNavigating || _isJoining.value == true || _joinSuccessResult.value != null) return
        currentAuth?.signOut()
        _navigateToLogin.value = Event(Unit)
    }
}

package com.pintrip.app.place

import androidx.annotation.StringRes
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.firestore.FirebaseFirestoreException
import com.pintrip.app.R
import com.pintrip.app.data.model.Place
import com.pintrip.app.data.repository.PlaceRepository
import com.pintrip.app.data.repository.RoomRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

sealed class PlaceListUiState {
    object Loading : PlaceListUiState()
    object Empty : PlaceListUiState()
    data class Success(val places: List<Place>) : PlaceListUiState()
    data class Error(@get:StringRes val messageResId: Int) : PlaceListUiState()
}

sealed class InviteCodeUiState {
    object Idle : InviteCodeUiState()
    object Loading : InviteCodeUiState()
    data class Success(val inviteCode: String) : InviteCodeUiState()
    data class Error(@get:StringRes val messageResId: Int) : InviteCodeUiState()
}

class PlaceListViewModel(
    private val repository: PlaceRepository = PlaceRepository(),
    private val roomRepository: RoomRepository = RoomRepository(),
) : ViewModel() {

    private val _uiState = MutableLiveData<PlaceListUiState>()
    val uiState: LiveData<PlaceListUiState> = _uiState

    private val _isInviteCodeDialogOpen = MutableLiveData<Boolean>(false)
    val isInviteCodeDialogOpen: LiveData<Boolean> = _isInviteCodeDialogOpen

    private val _inviteCodeUiState = MutableLiveData<InviteCodeUiState>(InviteCodeUiState.Idle)
    val inviteCodeUiState: LiveData<InviteCodeUiState> = _inviteCodeUiState

    private var currentRoomId: String? = null
    private var cachedInviteCode: String? = null
    private var loadPlacesJob: Job? = null
    private var fetchCodeJob: Job? = null

    fun loadPlaces(roomId: String, forceRefresh: Boolean = false) {
        if (roomId.isBlank()) return

        if (loadPlacesJob?.isActive == true) {
            if (!forceRefresh) return
            loadPlacesJob?.cancel()
        } else if (!forceRefresh && currentRoomId == roomId && (_uiState.value != null && _uiState.value !is PlaceListUiState.Error)) {
            return
        }

        currentRoomId = roomId
        _uiState.value = PlaceListUiState.Loading

        loadPlacesJob = viewModelScope.launch {
            try {
                val places = repository.getPlaces(roomId)
                if (places.isEmpty()) {
                    _uiState.value = PlaceListUiState.Empty
                } else {
                    _uiState.value = PlaceListUiState.Success(places)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                @StringRes val errorResId = when (e) {
                    is FirebaseFirestoreException -> {
                        when (e.code) {
                            FirebaseFirestoreException.Code.PERMISSION_DENIED -> R.string.error_room_permission_denied
                            FirebaseFirestoreException.Code.UNAVAILABLE -> R.string.error_network_unavailable
                            else -> R.string.place_list_error_default
                        }
                    }
                    else -> R.string.place_list_error_default
                }
                _uiState.value = PlaceListUiState.Error(errorResId)
            }
        }
    }

    fun onInviteCodeClick() {
        _isInviteCodeDialogOpen.value = true
        fetchInviteCodeIfNeeded()
    }

    fun onInviteCodeDialogDismissed() {
        _isInviteCodeDialogOpen.value = false
    }

    fun retryFetchInviteCode() {
        cachedInviteCode = null
        if (fetchCodeJob?.isActive == true) {
            fetchCodeJob?.cancel()
        }
        fetchInviteCodeIfNeeded()
    }

    private fun fetchInviteCodeIfNeeded() {
        val roomId = currentRoomId ?: return
        val existingCode = cachedInviteCode
        if (!existingCode.isNullOrBlank()) {
            _inviteCodeUiState.value = InviteCodeUiState.Success(existingCode)
            return
        }

        if (fetchCodeJob?.isActive == true) return

        _inviteCodeUiState.value = InviteCodeUiState.Loading

        fetchCodeJob = viewModelScope.launch {
            try {
                val room = roomRepository.getRoom(roomId)
                if (room != null && room.inviteCode.isNotBlank()) {
                    cachedInviteCode = room.inviteCode
                    _inviteCodeUiState.value = InviteCodeUiState.Success(room.inviteCode)
                } else {
                    _inviteCodeUiState.value = InviteCodeUiState.Error(R.string.error_load_invite_code_failed)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                @StringRes val resId = when (e) {
                    is FirebaseFirestoreException -> {
                        when (e.code) {
                            FirebaseFirestoreException.Code.UNAVAILABLE -> R.string.error_network_unavailable
                            else -> R.string.error_load_invite_code_failed
                        }
                    }
                    else -> R.string.error_load_invite_code_failed
                }
                _inviteCodeUiState.value = InviteCodeUiState.Error(resId)
            }
        }
    }

    fun retry() {
        val roomId = currentRoomId
        if (!roomId.isNullOrBlank()) {
            loadPlaces(roomId, forceRefresh = true)
        }
    }
}

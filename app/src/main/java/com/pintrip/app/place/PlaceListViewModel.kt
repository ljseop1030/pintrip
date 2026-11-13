package com.pintrip.app.place

import androidx.annotation.StringRes
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pintrip.app.R
import com.pintrip.app.data.model.Place
import com.pintrip.app.data.repository.PlaceRepository
import com.pintrip.app.data.repository.RoomRepository
import com.pintrip.app.ui.common.Event
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

sealed class PlaceListUiState {
    object Loading : PlaceListUiState()
    object Empty : PlaceListUiState()
    data class Success(val places: List<Place>) : PlaceListUiState()
    data class Error(val message: String?) : PlaceListUiState()
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
    private var isFetchingCode = false

    fun loadPlaces(roomId: String, forceRefresh: Boolean = false) {
        if (roomId.isBlank()) return

        // 화면 회전 시 이미 불러온 상태가 있으면 다시 호출하지 않음
        if (!forceRefresh && currentRoomId == roomId && (_uiState.value != null)) {
            return
        }

        currentRoomId = roomId
        _uiState.value = PlaceListUiState.Loading

        viewModelScope.launch {
            try {
                val places = repository.getPlaces(roomId)
                if (places.isEmpty()) {
                    _uiState.value = PlaceListUiState.Empty
                } else {
                    _uiState.value = PlaceListUiState.Success(places)
                }
            } catch (e: Exception) {
                _uiState.value = PlaceListUiState.Error(e.localizedMessage)
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
        fetchInviteCodeIfNeeded()
    }

    private fun fetchInviteCodeIfNeeded() {
        val roomId = currentRoomId ?: return
        val existingCode = cachedInviteCode
        if (!existingCode.isNullOrBlank()) {
            _inviteCodeUiState.value = InviteCodeUiState.Success(existingCode)
            return
        }

        if (isFetchingCode) return
        isFetchingCode = true
        _inviteCodeUiState.value = InviteCodeUiState.Loading

        viewModelScope.launch {
            try {
                val room = roomRepository.getRoom(roomId)
                isFetchingCode = false
                if (room != null && room.inviteCode.isNotBlank()) {
                    cachedInviteCode = room.inviteCode
                    _inviteCodeUiState.value = InviteCodeUiState.Success(room.inviteCode)
                } else {
                    _inviteCodeUiState.value = InviteCodeUiState.Error(R.string.error_load_invite_code_failed)
                }
            } catch (e: Exception) {
                isFetchingCode = false
                if (e is CancellationException) throw e
                _inviteCodeUiState.value = InviteCodeUiState.Error(R.string.error_load_invite_code_failed)
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

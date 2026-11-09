package com.pintrip.app.place

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pintrip.app.data.model.Place
import com.pintrip.app.data.repository.PlaceRepository
import kotlinx.coroutines.launch

sealed class PlaceListUiState {
    object Loading : PlaceListUiState()
    object Empty : PlaceListUiState()
    data class Success(val places: List<Place>) : PlaceListUiState()
    data class Error(val message: String?) : PlaceListUiState()
}

class PlaceListViewModel(
    private val repository: PlaceRepository = PlaceRepository(),
) : ViewModel() {

    private val _uiState = MutableLiveData<PlaceListUiState>()
    val uiState: LiveData<PlaceListUiState> = _uiState

    private var currentRoomId: String? = null

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

    fun retry() {
        val roomId = currentRoomId
        if (!roomId.isNullOrBlank()) {
            loadPlaces(roomId, forceRefresh = true)
        }
    }
}

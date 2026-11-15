package com.pintrip.app.map

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pintrip.app.R
import com.pintrip.app.data.model.PlaceDetail
import com.pintrip.app.data.repository.PlaceDetailRepository
import com.pintrip.app.data.repository.RoomRepository
import kotlinx.coroutines.launch

sealed class CameraCommand {
    data class MoveToCenter(val lat: Double, val lng: Double, val zoom: Int = 13) : CameraCommand()
    data class FitPoints(val points: List<Pair<Double, Double>>) : CameraCommand()
    object MoveToKorea : CameraCommand()
}

sealed class RoomMapUiState {
    object Loading : RoomMapUiState()
    data class Success(
        val placeDetails: List<PlaceDetail>,
        val cameraCommand: CameraCommand,
    ) : RoomMapUiState()
    data class Error(val messageResId: Int) : RoomMapUiState()
}

class RoomMapViewModel(
    private val roomRepository: RoomRepository = RoomRepository(),
    private val placeDetailRepository: PlaceDetailRepository = PlaceDetailRepository(),
) : ViewModel() {

    private val _uiState = MutableLiveData<RoomMapUiState>()
    val uiState: LiveData<RoomMapUiState> = _uiState

    private var isDataLoaded = false

    fun loadRoomMap(roomId: String) {
        if (isDataLoaded) return
        isDataLoaded = true

        _uiState.value = RoomMapUiState.Loading
        viewModelScope.launch {
            try {
                val room = roomRepository.getRoom(roomId)
                if (room == null) {
                    _uiState.value = RoomMapUiState.Error(R.string.room_map_error_room_not_found)
                    return@launch
                }

                val roomRegion = room.region
                val placeDetails = placeDetailRepository.getPlaceDetails(roomId, roomRegion)

                val foundPlaces = placeDetails.filter { it.status == PlaceDetail.STATUS_FOUND && it.lat != null && it.lng != null }

                val cameraCommand = if (foundPlaces.isNotEmpty()) {
                    CameraCommand.FitPoints(foundPlaces.map { it.lat!! to it.lng!! })
                } else {
                    val center = placeDetailRepository.searchRegionCenter(roomRegion)
                    if (center != null) {
                        CameraCommand.MoveToCenter(center.lat, center.lng, 13)
                    } else {
                        CameraCommand.MoveToKorea
                    }
                }

                _uiState.value = RoomMapUiState.Success(placeDetails, cameraCommand)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load room map data: ${e.message}", e)
                _uiState.value = RoomMapUiState.Error(R.string.room_map_error_load_failed)
            }
        }
    }

    fun retry(roomId: String) {
        isDataLoaded = false
        loadRoomMap(roomId)
    }

    companion object {
        private const val TAG = "RoomMapViewModel"
    }
}

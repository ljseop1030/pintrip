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
import com.pintrip.app.place.PlaceCategory
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
        val cameraCommand: CameraCommand?,
        val roomName: String,
        val selectedCategory: PlaceCategory,
        val totalPlaceCount: Int,
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
    private var allPlaceDetails: List<PlaceDetail> = emptyList()
    private var roomName: String = ""
    private var selectedCategory: PlaceCategory = PlaceCategory.ALL

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

                roomName = room.name
                val roomRegion = room.region
                val placeDetails = placeDetailRepository.getPlaceDetails(roomId, roomRegion)
                allPlaceDetails = placeDetails

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

                val filteredList = filterPlaces(allPlaceDetails, selectedCategory)

                _uiState.value = RoomMapUiState.Success(
                    placeDetails = filteredList,
                    cameraCommand = cameraCommand,
                    roomName = roomName,
                    selectedCategory = selectedCategory,
                    totalPlaceCount = allPlaceDetails.size,
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load room map data: ${e.message}", e)
                _uiState.value = RoomMapUiState.Error(R.string.room_map_error_load_failed)
            }
        }
    }

    fun selectCategory(category: PlaceCategory) {
        if (selectedCategory == category && (_uiState.value is RoomMapUiState.Success)) {
            val currentSuccess = _uiState.value as RoomMapUiState.Success
            if (currentSuccess.selectedCategory == category) return
        }
        selectedCategory = category

        if (isDataLoaded) {
            val filteredList = filterPlaces(allPlaceDetails, selectedCategory)
            _uiState.value = RoomMapUiState.Success(
                placeDetails = filteredList,
                cameraCommand = null,
                roomName = roomName,
                selectedCategory = selectedCategory,
                totalPlaceCount = allPlaceDetails.size,
            )
        }
    }

    private fun filterPlaces(places: List<PlaceDetail>, category: PlaceCategory): List<PlaceDetail> {
        return places.filter { category.matches(it.category) }
    }

    fun retry(roomId: String) {
        isDataLoaded = false
        loadRoomMap(roomId)
    }

    companion object {
        private const val TAG = "RoomMapViewModel"
    }
}

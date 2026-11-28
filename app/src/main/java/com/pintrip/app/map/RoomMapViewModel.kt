package com.pintrip.app.map

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
import com.pintrip.app.place.InviteCodeUiState
import com.pintrip.app.place.PlaceCategory
import kotlinx.coroutines.Job
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
        val startDate: Timestamp? = null,
        val endDate: Timestamp? = null,
    ) : RoomMapUiState()
    data class Error(val messageResId: Int) : RoomMapUiState()
}

class RoomMapViewModel(
    private val roomRepository: RoomRepository = RoomRepository(),
    private val placeDetailRepository: PlaceDetailRepository = PlaceDetailRepository(),
) : ViewModel() {

    private val _uiState = MutableLiveData<RoomMapUiState>()
    val uiState: LiveData<RoomMapUiState> = _uiState

    private val _isInviteCodeDialogOpen = MutableLiveData<Boolean>(false)
    val isInviteCodeDialogOpen: LiveData<Boolean> = _isInviteCodeDialogOpen

    private val _inviteCodeUiState = MutableLiveData<InviteCodeUiState>(InviteCodeUiState.Idle)
    val inviteCodeUiState: LiveData<InviteCodeUiState> = _inviteCodeUiState

    private var isLoading = false
    private var isDataLoaded = false
    private var allPlaceDetails: List<PlaceDetail> = emptyList()
    private var roomName: String = ""
    private var roomStartDate: Timestamp? = null
    private var roomEndDate: Timestamp? = null
    private var selectedCategory: PlaceCategory = PlaceCategory.ALL

    fun getAllPlaceDetails(): List<PlaceDetail> = allPlaceDetails
    fun getSelectedCategory(): PlaceCategory = selectedCategory

    private var cachedInviteCode: String? = null
    private var fetchCodeJob: Job? = null

    fun loadRoomMap(roomId: String) {
        if (isDataLoaded || isLoading) return
        isLoading = true

        _uiState.value = RoomMapUiState.Loading
        viewModelScope.launch {
            try {
                val room = roomRepository.getRoom(roomId)
                if (room == null) {
                    isLoading = false
                    _uiState.value = RoomMapUiState.Error(R.string.room_map_error_room_not_found)
                    if (_isInviteCodeDialogOpen.value == true) {
                        _inviteCodeUiState.value = InviteCodeUiState.Error(R.string.error_load_invite_code_failed)
                    }
                    return@launch
                }

                roomName = room.name
                roomStartDate = room.startDate
                roomEndDate = room.endDate
                cachedInviteCode = room.inviteCode.takeIf { it.isNotBlank() }
                if (_isInviteCodeDialogOpen.value == true && !cachedInviteCode.isNullOrBlank()) {
                    _inviteCodeUiState.value = InviteCodeUiState.Success(cachedInviteCode!!)
                }

                val roomRegion = room.region
                val placeDetails = placeDetailRepository.getPlaceDetails(roomId, roomRegion)
                allPlaceDetails = placeDetails
                isDataLoaded = true
                isLoading = false

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
                    startDate = roomStartDate,
                    endDate = roomEndDate,
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load room map data: ${e.message}", e)
                isLoading = false
                isDataLoaded = false
                _uiState.value = RoomMapUiState.Error(R.string.room_map_error_load_failed)
                if (_isInviteCodeDialogOpen.value == true && cachedInviteCode.isNullOrBlank()) {
                    _inviteCodeUiState.value = InviteCodeUiState.Error(R.string.error_load_invite_code_failed)
                }
            }
        }
    }

    fun selectCategory(category: PlaceCategory) {
        val currentUiState = _uiState.value
        if (selectedCategory == category && currentUiState is RoomMapUiState.Success && currentUiState.selectedCategory == category) {
            return
        }
        selectedCategory = category

        if (currentUiState is RoomMapUiState.Success) {
            val filteredList = filterPlaces(allPlaceDetails, selectedCategory)
            _uiState.value = RoomMapUiState.Success(
                placeDetails = filteredList,
                cameraCommand = null,
                roomName = roomName,
                selectedCategory = selectedCategory,
                totalPlaceCount = allPlaceDetails.size,
                startDate = roomStartDate,
                endDate = roomEndDate,
            )
        }
    }

    fun onInviteCodeClick() {
        _isInviteCodeDialogOpen.value = true
        updateInviteCodeUiState()
    }

    fun onInviteCodeDialogDismissed() {
        _isInviteCodeDialogOpen.value = false
    }

    fun retryFetchInviteCode(roomId: String) {
        val existing = cachedInviteCode
        if (!existing.isNullOrBlank()) {
            _inviteCodeUiState.value = InviteCodeUiState.Success(existing)
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
                Log.e(TAG, "Retry fetch invite code failed: ${e.message}", e)
                _inviteCodeUiState.value = InviteCodeUiState.Error(R.string.error_load_invite_code_failed)
            }
        }
    }

    private fun updateInviteCodeUiState() {
        val code = cachedInviteCode
        if (!code.isNullOrBlank()) {
            _inviteCodeUiState.value = InviteCodeUiState.Success(code)
        } else if (isLoading || _uiState.value is RoomMapUiState.Loading) {
            _inviteCodeUiState.value = InviteCodeUiState.Loading
        } else {
            _inviteCodeUiState.value = InviteCodeUiState.Error(R.string.error_load_invite_code_failed)
        }
    }

    private fun filterPlaces(places: List<PlaceDetail>, category: PlaceCategory): List<PlaceDetail> {
        return places.filter { category.matches(it.category) }
    }

    fun retry(roomId: String) {
        isDataLoaded = false
        isLoading = false
        loadRoomMap(roomId)
    }

    companion object {
        private const val TAG = "RoomMapViewModel"
    }
}
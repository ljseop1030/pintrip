package com.pintrip.app.extract

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.pintrip.app.ui.common.Event

sealed interface ConfirmLocationResult {
    data class Selected(val position: Int, val updatedMatchResult: MatchResult) : ConfirmLocationResult
    data class Excluded(val position: Int, val updatedMatchResult: MatchResult) : ConfirmLocationResult
}

class ConfirmLocationViewModel : ViewModel() {

    private val _matchResult = MutableLiveData<MatchResult>()
    val matchResult: LiveData<MatchResult> = _matchResult

    private val _roomRegion = MutableLiveData<String>()
    val roomRegion: LiveData<String> = _roomRegion

    private val _selectedCandidateId = MutableLiveData<String?>()
    val selectedCandidateId: LiveData<String?> = _selectedCandidateId

    private val _resultEvent = MutableLiveData<Event<ConfirmLocationResult>>()
    val resultEvent: LiveData<Event<ConfirmLocationResult>> = _resultEvent

    private var position: Int = -1
    private var isInitialized = false

    fun init(matchResultInput: MatchResult, roomRegionInput: String, positionInput: Int) {
        if (isInitialized) return
        isInitialized = true

        _matchResult.value = matchResultInput
        _roomRegion.value = roomRegionInput
        _selectedCandidateId.value = matchResultInput.selectedPlaceId
        position = positionInput
    }

    fun selectCandidate(candidateId: String) {
        _selectedCandidateId.value = candidateId
    }

    fun onSelectThisLocationClicked() {
        val currentResult = _matchResult.value ?: return
        val currentCandidateId = _selectedCandidateId.value ?: return
        val selectedCand = currentResult.candidates.find { it.kakaoPlaceId == currentCandidateId } ?: return
        val roomRegionVal = _roomRegion.value.orEmpty()

        val isMismatch = isCandidateAddressOutsideRoomRegion(
            candidateAddress = selectedCand.address,
            roomRegion = roomRegionVal,
            geminiOutsideRoomRegion = currentResult.extracted.outsideRoomRegion,
        )

        val updatedResult = currentResult.copy(
            selectedPlaceId = selectedCand.kakaoPlaceId,
            regionMismatch = isMismatch,
            checked = !isMismatch,
        )

        _resultEvent.value = Event(ConfirmLocationResult.Selected(position, updatedResult))
    }

    fun onExcludeClicked() {
        val currentResult = _matchResult.value ?: return
        val updatedResult = currentResult.copy(
            selectedPlaceId = null,
            checked = false,
        )

        _resultEvent.value = Event(ConfirmLocationResult.Excluded(position, updatedResult))
    }
}

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

    private val _areAllCandidatesOutsideRegion = MutableLiveData<Boolean>(false)
    val areAllCandidatesOutsideRegion: LiveData<Boolean> = _areAllCandidatesOutsideRegion

    private val _resultEvent = MutableLiveData<Event<ConfirmLocationResult>>()
    val resultEvent: LiveData<Event<ConfirmLocationResult>> = _resultEvent

    private var position: Int = -1
    private var isInitialized = false

    fun init(matchResultInput: MatchResult, roomRegionInput: String, positionInput: Int) {
        if (isInitialized) return
        isInitialized = true

        _matchResult.value = matchResultInput
        _roomRegion.value = roomRegionInput
        position = positionInput

        val allOutside = matchResultInput.candidates.isNotEmpty() && matchResultInput.candidates.all { cand ->
            isCandidateAddressOutsideRoomRegion(cand.address, roomRegionInput, geminiOutsideRoomRegion = false)
        }
        _areAllCandidatesOutsideRegion.value = allOutside

        val initialSelectedId = matchResultInput.selectedPlaceId
        val initialCandidate = matchResultInput.candidates.find { it.kakaoPlaceId == initialSelectedId }
        val isInitialSelectedOutside = initialCandidate != null && isCandidateAddressOutsideRoomRegion(
            candidateAddress = initialCandidate.address,
            roomRegion = roomRegionInput,
            geminiOutsideRoomRegion = false,
        )

        _selectedCandidateId.value = if (isInitialSelectedOutside) null else initialSelectedId
    }

    fun isCandidateDisabled(candidate: MatchCandidate): Boolean {
        val region = _roomRegion.value
        return isCandidateAddressOutsideRoomRegion(
            candidateAddress = candidate.address,
            roomRegion = region,
            geminiOutsideRoomRegion = false,
        )
    }

    fun selectCandidate(candidateId: String) {
        val currentResult = _matchResult.value ?: return
        val cand = currentResult.candidates.find { it.kakaoPlaceId == candidateId } ?: return
        if (isCandidateDisabled(cand)) return

        _selectedCandidateId.value = candidateId
    }

    fun onSelectThisLocationClicked() {
        val currentResult = _matchResult.value ?: return
        val currentCandidateId = _selectedCandidateId.value ?: return
        val selectedCand = currentResult.candidates.find { it.kakaoPlaceId == currentCandidateId } ?: return
        if (isCandidateDisabled(selectedCand)) return

        val updatedResult = currentResult.copy(
            selectedPlaceId = selectedCand.kakaoPlaceId,
            regionMismatch = false,
            checked = true,
        )

        _resultEvent.value = Event(ConfirmLocationResult.Selected(position, updatedResult))
    }

    fun onManualSearchResultPicked(candidate: MatchCandidate, manualQuery: String) {
        val currentResult = _matchResult.value ?: return

        val updatedResult = currentResult.copy(
            candidates = listOf(candidate),
            selectedPlaceId = candidate.kakaoPlaceId,
            manualQuery = manualQuery,
            regionMismatch = false,
            checked = true,
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

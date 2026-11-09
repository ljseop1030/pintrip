package com.pintrip.app.extract

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.firestore.FirebaseFirestore
import com.pintrip.app.ui.common.Event
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

data class RegionMismatchNotice(
    val mismatchCount: Int,
    val roomRegion: String,
)

/**
 * [SelectPlacesActivity]의 화면 상태(매칭 결과 목록, 체크 여부, 선택된 후보)를 관리하는 ViewModel.
 *
 * docs/data-contract.md §9 규격을 준수합니다:
 * 1. CHECK 상태인 항목은 첫 번째 후보([candidates.first()])를 자동으로 선택합니다 (S08 개발 전 임시 처리).
 * 2. Firestore `rooms/{roomId}`에서 방 이름 및 대표 지역을 단건 조회합니다.
 * 3. 진입 시 지역 불일치([MatchResult.regionMismatch]) 항목이 존재하는 경우 1회성 팝업 이벤트를 발행합니다.
 * 4. 화면 회전(Configuration Change) 시에도 사용자의 선택/체크 상태 및 조회된 방 데이터가 유지됩니다.
 */
class SelectPlacesViewModel(
    private val db: FirebaseFirestore? = null,
) : ViewModel() {

    private val _roomId = MutableLiveData<String>()
    val roomId: LiveData<String> = _roomId

    private val _roomName = MutableLiveData<String>()
    val roomName: LiveData<String> = _roomName

    private val _roomRegion = MutableLiveData<String>()
    val roomRegion: LiveData<String> = _roomRegion

    private val _matchResults = MutableLiveData<List<MatchResult>>()
    val matchResults: LiveData<List<MatchResult>> = _matchResults

    private val _regionMismatchNotice = MutableLiveData<Event<RegionMismatchNotice>>()
    val regionMismatchNotice: LiveData<Event<RegionMismatchNotice>> = _regionMismatchNotice

    private var isInitialized = false

    fun init(roomIdInput: String, initialResults: List<MatchResult>) {
        if (isInitialized) return
        isInitialized = true

        _roomId.postValue(roomIdInput)

        // 1. CHECK 상태 항목의 첫 번째 후보 자동 선택
        // TODO: Temporary handling - auto-selecting the first candidate until S08 Confirm Location (candidate selection) screen is built
        val processedResults = initialResults.map { item ->
            if (item.status == MatchResult.STATUS_CHECK && item.selectedPlaceId == null && item.candidates.isNotEmpty()) {
                item.copy(selectedPlaceId = item.candidates.first().kakaoPlaceId)
            } else {
                item
            }
        }
        _matchResults.postValue(processedResults)

        // 2. Firestore rooms/{roomId} 단건 읽기 (방 이름, 대표 지역 조회)
        viewModelScope.launch {
            try {
                if (roomIdInput.isNotBlank()) {
                    val firestore = db ?: FirebaseFirestore.getInstance()
                    val snapshot = firestore.collection("rooms").document(roomIdInput).get().await()
                    if (snapshot.exists()) {
                        val name = snapshot.getString("name").orEmpty()
                        val region = snapshot.getString("region").orEmpty()
                        _roomName.postValue(name)
                        _roomRegion.postValue(region)

                        // 3. 지역 불일치 안내 (docs/data-contract.md §9): 진입 시 n개 장소가 방 지역 밖임을 팝업 이벤트로 전달
                        val mismatchCount = processedResults.count { it.regionMismatch }
                        if (mismatchCount > 0 && region.isNotBlank()) {
                            _regionMismatchNotice.postValue(
                                Event(
                                    RegionMismatchNotice(
                                        mismatchCount = mismatchCount,
                                        roomRegion = region,
                                    ),
                                ),
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load room details for roomId=$roomIdInput: ${e.message}", e)
            }
        }
    }

    fun toggleCheck(index: Int, isChecked: Boolean) {
        val currentList = _matchResults.value?.toMutableList() ?: return
        if (index in currentList.indices) {
            val item = currentList[index]
            if (item.status != MatchResult.STATUS_NOT_FOUND) {
                currentList[index] = item.copy(checked = isChecked)
                _matchResults.postValue(currentList)
            }
        }
    }

    fun getConfirmableCheckedCount(): Int {
        val currentList = _matchResults.value ?: return 0
        return currentList.count { it.checked && it.selectedCandidate != null }
    }

    companion object {
        private const val TAG = "SelectPlacesViewModel"
    }
}

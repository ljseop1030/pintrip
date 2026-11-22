package com.pintrip.app.extract

import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pintrip.app.R
import com.pintrip.app.data.model.KakaoKeywordSearchResponse
import com.pintrip.app.data.remote.KakaoLocalApiClient
import com.pintrip.app.data.remote.buildKakaoSearchQuery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

sealed class ManualSearchUiState {
    object Idle : ManualSearchUiState()
    object Loading : ManualSearchUiState()
    data class Success(
        val candidates: List<MatchCandidate>,
        val searchedQuery: String,
    ) : ManualSearchUiState()
    object Empty : ManualSearchUiState()
    data class Error(@StringRes val messageResId: Int) : ManualSearchUiState()
}

class ManualSearchViewModel(
    private val clientFactory: () -> KakaoLocalApiClient = { KakaoLocalApiClient() },
) : ViewModel() {

    companion object {
        private const val TAG = "ManualSearchViewModel"
        private const val DEBOUNCE_DELAY_MS = 300L
    }

    private val _uiState = MutableLiveData<ManualSearchUiState>(ManualSearchUiState.Idle)
    val uiState: LiveData<ManualSearchUiState> = _uiState

    private val _roomRegion = MutableLiveData<String>()
    val roomRegion: LiveData<String> = _roomRegion

    private val _currentQuery = MutableLiveData<String>("")
    val currentQuery: LiveData<String> = _currentQuery

    private var isInitialized = false
    private var searchJob: Job? = null

    fun init(roomRegionInput: String, initialQueryInput: String?) {
        if (isInitialized) return
        isInitialized = true

        _roomRegion.value = roomRegionInput
        val initQuery = initialQueryInput?.trim().orEmpty()
        if (initQuery.isNotBlank()) {
            _currentQuery.value = initQuery
            onQueryChanged(initQuery, immediate = true)
        }
    }

    fun getPickedManualQuery(candidate: MatchCandidate): String {
        val state = _uiState.value
        return if (state is ManualSearchUiState.Success && state.searchedQuery.isNotBlank()) {
            state.searchedQuery
        } else {
            _currentQuery.value?.trim().orEmpty().ifBlank { candidate.name }
        }
    }

    fun onQueryChanged(newQuery: String, immediate: Boolean = false) {
        val trimmedQuery = newQuery.trim()
        if (_currentQuery.value != newQuery) {
            _currentQuery.value = newQuery
        }

        if (trimmedQuery.isBlank()) {
            searchJob?.cancel()
            _uiState.value = ManualSearchUiState.Idle
            return
        }

        searchJob?.cancel()
        _uiState.value = ManualSearchUiState.Loading

        searchJob = viewModelScope.launch {
            if (!immediate) {
                delay(DEBOUNCE_DELAY_MS)
            }

            try {
                val region = _roomRegion.value.orEmpty()
                val kakaoQuery = buildKakaoSearchQuery(
                    name = trimmedQuery,
                    roomRegion = region,
                    outsideRoomRegion = false,
                )
                val client = clientFactory()
                val response: KakaoKeywordSearchResponse = client.searchKeyword(
                    query = kakaoQuery.query,
                    categoryGroupCode = kakaoQuery.categoryGroupCode,
                )
                val candidates = response.documents.map { MatchCandidate.fromKakaoDocument(it) }

                if (candidates.isEmpty()) {
                    _uiState.value = ManualSearchUiState.Empty
                } else {
                    _uiState.value = ManualSearchUiState.Success(
                        candidates = candidates,
                        searchedQuery = trimmedQuery,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Manual search failed for query='$trimmedQuery': ${e.message}", e)
                _uiState.value = ManualSearchUiState.Error(R.string.manual_search_error)
            }
        }
    }
}

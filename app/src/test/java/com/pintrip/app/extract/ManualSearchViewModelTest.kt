package com.pintrip.app.extract

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.pintrip.app.R
import com.pintrip.app.data.model.KakaoKeywordSearchResponse
import com.pintrip.app.data.model.KakaoPlaceDocument
import com.pintrip.app.data.model.KakaoSearchMeta
import com.pintrip.app.data.remote.KakaoLocalApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.IOException

private class FakeKakaoLocalApiClient(
    private val documentsToReturn: List<KakaoPlaceDocument> = emptyList(),
    private val exceptionToThrow: Exception? = null,
) : KakaoLocalApiClient("", "") {
    var searchCount = 0
    var lastQuery: String? = null

    override suspend fun searchKeyword(
        query: String,
        categoryGroupCode: String?,
        page: Int?,
        size: Int?,
        x: String?,
        y: String?,
        radius: Int?,
        rect: String?,
        sort: String?,
    ): KakaoKeywordSearchResponse {
        searchCount++
        lastQuery = query
        if (exceptionToThrow != null) {
            throw exceptionToThrow
        }
        return KakaoKeywordSearchResponse(
            meta = KakaoSearchMeta(totalCount = documentsToReturn.size, pageableCount = documentsToReturn.size, isEnd = true),
            documents = documentsToReturn,
        )
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ManualSearchViewModelTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testInit_skipsReinitializationOnConfigurationChange() = runTest {
        val fakeDoc = KakaoPlaceDocument(id = "1", placeName = "모모스커피", roadAddressName = "부산 오시게로 20")
        val fakeClient = FakeKakaoLocalApiClient(documentsToReturn = listOf(fakeDoc))

        val viewModel = ManualSearchViewModel(clientFactory = { fakeClient })
        viewModel.init(roomRegionInput = "부산", initialQueryInput = "모모스")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, fakeClient.searchCount)

        // Re-init (simulating rotation)
        viewModel.init(roomRegionInput = "부산", initialQueryInput = "모모스")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, fakeClient.searchCount) // Search is NOT re-triggered
    }

    @Test
    fun testQueryChanged_emptySearchResponse_setsStateToEmpty() = runTest {
        val fakeClient = FakeKakaoLocalApiClient(documentsToReturn = emptyList())
        val viewModel = ManualSearchViewModel(clientFactory = { fakeClient })

        viewModel.init(roomRegionInput = "부산", initialQueryInput = null)
        viewModel.onQueryChanged("존재하지않는장소", immediate = true)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is ManualSearchUiState.Empty)
    }

    @Test
    fun testQueryChanged_searchFailure_setsStateToError() = runTest {
        val fakeClient = FakeKakaoLocalApiClient(exceptionToThrow = IOException("Network Error"))
        val viewModel = ManualSearchViewModel(clientFactory = { fakeClient })

        viewModel.init(roomRegionInput = "부산", initialQueryInput = null)
        viewModel.onQueryChanged("이재모피자", immediate = true)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is ManualSearchUiState.Error)
        assertEquals(R.string.manual_search_error, (state as ManualSearchUiState.Error).messageResId)
    }

    @Test
    fun testQueryChanged_searchSuccess_setsStateToSuccess() = runTest {
        val fakeDoc = KakaoPlaceDocument(id = "100", placeName = "이재모피자 본점", roadAddressName = "부산 광복중앙로 31")
        val fakeClient = FakeKakaoLocalApiClient(documentsToReturn = listOf(fakeDoc))
        val viewModel = ManualSearchViewModel(clientFactory = { fakeClient })

        viewModel.init(roomRegionInput = "부산", initialQueryInput = null)
        viewModel.onQueryChanged("이재모피자", immediate = true)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is ManualSearchUiState.Success)
        val candidates = (state as ManualSearchUiState.Success).candidates
        assertEquals(1, candidates.size)
        assertEquals("100", candidates[0].kakaoPlaceId)
    }

    @Test
    fun testQueryChanged_blankQuery_resetsStateToIdle() = runTest {
        val fakeClient = FakeKakaoLocalApiClient()
        val viewModel = ManualSearchViewModel(clientFactory = { fakeClient })

        viewModel.init(roomRegionInput = "부산", initialQueryInput = null)
        viewModel.onQueryChanged("", immediate = true)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is ManualSearchUiState.Idle)
        assertEquals(0, fakeClient.searchCount)
    }

    @Test
    fun testGetPickedManualQuery_returnsSearchedQueryUsedForResults() = runTest {
        val fakeDoc = KakaoPlaceDocument(id = "100", placeName = "이재모피자 본점", roadAddressName = "부산 광복중앙로 31")
        val fakeClient = FakeKakaoLocalApiClient(documentsToReturn = listOf(fakeDoc))
        val viewModel = ManualSearchViewModel(clientFactory = { fakeClient })

        viewModel.init(roomRegionInput = "부산", initialQueryInput = null)
        viewModel.onQueryChanged("이재모피자", immediate = true)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value as ManualSearchUiState.Success
        assertEquals("이재모피자", state.searchedQuery)

        val candidate = state.candidates[0]
        val pickedQuery = viewModel.getPickedManualQuery(candidate)
        assertEquals("이재모피자", pickedQuery)
    }

    @Test
    fun testOnQueryChanged_immediatelySetsLoadingStateWhenNewTextTyped() = runTest {
        val fakeDoc = KakaoPlaceDocument(id = "100", placeName = "이재모피자 본점", roadAddressName = "부산 광복중앙로 31")
        val fakeClient = FakeKakaoLocalApiClient(documentsToReturn = listOf(fakeDoc))
        val viewModel = ManualSearchViewModel(clientFactory = { fakeClient })

        viewModel.init(roomRegionInput = "부산", initialQueryInput = null)
        viewModel.onQueryChanged("이재모피자", immediate = true)
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value is ManualSearchUiState.Success)

        // Type new letter -> UI state immediately becomes Loading before debounce completes
        viewModel.onQueryChanged("이재모피자본", immediate = false)
        assertTrue(viewModel.uiState.value is ManualSearchUiState.Loading)
    }
}

package com.pintrip.app.extract

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.pintrip.app.data.model.GeminiPlace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConfirmLocationViewModelTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testInit_setsInitialState() {
        val viewModel = ConfirmLocationViewModel()

        val cand1 = MatchCandidate(kakaoPlaceId = "101", name = "모모스 커피 본점", address = "부산 금정구 오시게로 20")
        val cand2 = MatchCandidate(kakaoPlaceId = "102", name = "모모스 커피 센텀점", address = "부산 해운대구 센텀남대로 35")
        val matchResult = MatchResult(
            localId = "img0_p0",
            extracted = GeminiPlace(name = "모모스 커피", rawText = "3. Momos Coffee"),
            status = MatchResult.STATUS_CHECK,
            candidates = listOf(cand1, cand2),
            selectedPlaceId = null,
            checked = true,
        )

        viewModel.init(matchResult, "부산", positionInput = 0)

        assertEquals(matchResult, viewModel.matchResult.value)
        assertEquals("부산", viewModel.roomRegion.value)
        assertNull(viewModel.selectedCandidateId.value)
    }

    @Test
    fun testSelectCandidate_updatesSelectedId() {
        val viewModel = ConfirmLocationViewModel()

        val cand1 = MatchCandidate(kakaoPlaceId = "101", name = "모모스 커피 본점", address = "부산 금정구 오시게로 20")
        val matchResult = MatchResult(
            localId = "img0_p0",
            extracted = GeminiPlace(name = "모모스 커피"),
            status = MatchResult.STATUS_CHECK,
            candidates = listOf(cand1),
            selectedPlaceId = null,
        )

        viewModel.init(matchResult, "부산", positionInput = 0)
        viewModel.selectCandidate("101")

        assertEquals("101", viewModel.selectedCandidateId.value)
    }

    @Test
    fun testOnSelectThisLocationClicked_insideRoomRegion_setsCheckedTrueAndRegionMismatchFalse() {
        val viewModel = ConfirmLocationViewModel()

        val cand1 = MatchCandidate(kakaoPlaceId = "101", name = "모모스 커피 본점", address = "부산 금정구 오시게로 20")
        val matchResult = MatchResult(
            localId = "img0_p0",
            extracted = GeminiPlace(name = "모모스 커피"),
            status = MatchResult.STATUS_CHECK,
            candidates = listOf(cand1),
            selectedPlaceId = null,
        )

        viewModel.init(matchResult, "부산", positionInput = 2)
        viewModel.selectCandidate("101")
        viewModel.onSelectThisLocationClicked()

        val eventResult = viewModel.resultEvent.value?.getContentIfNotHandled()
        assertNotNull(eventResult)
        assertTrue(eventResult is ConfirmLocationResult.Selected)

        val selectedResult = (eventResult as ConfirmLocationResult.Selected)
        assertEquals(2, selectedResult.position)
        assertEquals("101", selectedResult.updatedMatchResult.selectedPlaceId)
        assertFalse(selectedResult.updatedMatchResult.regionMismatch)
        assertTrue(selectedResult.updatedMatchResult.checked)
    }

    @Test
    fun testOnSelectThisLocationClicked_outsideRoomRegion_setsCheckedFalseAndRegionMismatchTrue() {
        val viewModel = ConfirmLocationViewModel()

        val cand1 = MatchCandidate(kakaoPlaceId = "201", name = "오설록 티하우스", address = "제주 서귀포시 안덕면")
        val matchResult = MatchResult(
            localId = "img0_p0",
            extracted = GeminiPlace(name = "오설록"),
            status = MatchResult.STATUS_CHECK,
            candidates = listOf(cand1),
            selectedPlaceId = null,
        )

        viewModel.init(matchResult, "부산", positionInput = 1)
        viewModel.selectCandidate("201")
        viewModel.onSelectThisLocationClicked()

        val eventResult = viewModel.resultEvent.value?.getContentIfNotHandled()
        assertNotNull(eventResult)
        assertTrue(eventResult is ConfirmLocationResult.Selected)

        val selectedResult = (eventResult as ConfirmLocationResult.Selected)
        assertEquals(1, selectedResult.position)
        assertEquals("201", selectedResult.updatedMatchResult.selectedPlaceId)
        assertTrue(selectedResult.updatedMatchResult.regionMismatch)
        assertFalse(selectedResult.updatedMatchResult.checked) // regionMismatch defaults to unchecked per §9
    }

    @Test
    fun testOnExcludeClicked_setsSelectedIdNullAndCheckedFalse() {
        val viewModel = ConfirmLocationViewModel()

        val cand1 = MatchCandidate(kakaoPlaceId = "101", name = "모모스 커피 본점", address = "부산 금정구 오시게로 20")
        val matchResult = MatchResult(
            localId = "img0_p0",
            extracted = GeminiPlace(name = "모모스 커피"),
            status = MatchResult.STATUS_CHECK,
            candidates = listOf(cand1),
            selectedPlaceId = "101",
            checked = true,
        )

        viewModel.init(matchResult, "부산", positionInput = 3)
        viewModel.onExcludeClicked()

        val eventResult = viewModel.resultEvent.value?.getContentIfNotHandled()
        assertNotNull(eventResult)
        assertTrue(eventResult is ConfirmLocationResult.Excluded)

        val excludedResult = (eventResult as ConfirmLocationResult.Excluded)
        assertEquals(3, excludedResult.position)
        assertNull(excludedResult.updatedMatchResult.selectedPlaceId)
        assertFalse(excludedResult.updatedMatchResult.checked)
    }
}

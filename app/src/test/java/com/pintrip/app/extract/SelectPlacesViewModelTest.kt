package com.pintrip.app.extract

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.pintrip.app.data.model.GeminiPlace
import com.pintrip.app.data.model.Place
import com.pintrip.app.data.model.User
import com.pintrip.app.data.repository.AddPlacesResult
import com.pintrip.app.data.repository.PlaceRepository
import com.pintrip.app.data.repository.UserRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

private open class FakePlaceRepositoryForSelect : PlaceRepository(null, null) {
    var addPlacesCallCount = 0
    var lastPlacesSaved: List<Place> = emptyList()
    var resultToReturn: AddPlacesResult = AddPlacesResult(addedCount = 1, skippedCount = 0)

    override suspend fun addPlaces(roomId: String, places: List<Place>): AddPlacesResult {
        addPlacesCallCount++
        lastPlacesSaved = places
        return resultToReturn
    }
}

private open class FakeUserRepositoryForSelect : UserRepository(null, null) {
    override suspend fun getUserProfile(uid: String, source: com.google.firebase.firestore.Source): User {
        return User(nickname = "테스터", email = "test@pintrip.com", avatarColor = "#3A4A90")
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SelectPlacesViewModelTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private lateinit var fakePlaceRepo: FakePlaceRepositoryForSelect
    private lateinit var fakeUserRepo: FakeUserRepositoryForSelect

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)

        fakePlaceRepo = FakePlaceRepositoryForSelect()
        fakeUserRepo = FakeUserRepositoryForSelect()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testInit_preservesUnconfirmedCheckItemsWithoutAutoSelection() {
        val viewModel = SelectPlacesViewModel(
            placeRepository = fakePlaceRepo,
            userRepository = fakeUserRepo,
            currentUidProvider = { "test_uid" },
        )

        val checkCandidate = MatchCandidate(kakaoPlaceId = "cand_1", name = "모모스 커피 후보 1")
        val checkItem = MatchResult(
            localId = "img0_p0",
            extracted = GeminiPlace(name = "모모스 커피"),
            status = MatchResult.STATUS_CHECK,
            candidates = listOf(checkCandidate),
            selectedPlaceId = null, // unconfirmed
            checked = true,
        )

        viewModel.init("room_123", listOf(checkItem))

        val results = viewModel.matchResults.value
        assertNotNull(results)
        assertEquals(1, results!!.size)
        assertNull(results[0].selectedPlaceId)
        assertEquals(0, viewModel.getConfirmableCheckedCount())
        assertEquals(1, viewModel.getUnconfirmedCheckedCount())
        assertTrue(viewModel.hasAnyCheckedItems())
    }

    @Test
    fun testSaveSelectedPlaces_triggersUnconfirmedNotice_whenUnconfirmedItemsExist() {
        val viewModel = SelectPlacesViewModel(
            placeRepository = fakePlaceRepo,
            userRepository = fakeUserRepo,
            currentUidProvider = { "test_uid" },
        )

        val checkCandidate = MatchCandidate(kakaoPlaceId = "cand_1", name = "모모스 커피 후보 1")
        val checkItem = MatchResult(
            localId = "img0_p0",
            extracted = GeminiPlace(name = "모모스 커피"),
            status = MatchResult.STATUS_CHECK,
            candidates = listOf(checkCandidate),
            selectedPlaceId = null,
            checked = true,
        )

        viewModel.init("room_123", listOf(checkItem))
        viewModel.saveSelectedPlaces(skipUnconfirmed = false)

        val noticeEvent = viewModel.unconfirmedCheckNotice.value?.getContentIfNotHandled()
        assertNotNull(noticeEvent)
        assertEquals(1, noticeEvent)
        assertEquals(0, fakePlaceRepo.addPlacesCallCount)
    }

    @Test
    fun testSaveSelectedPlaces_skipsUnconfirmedItems_whenSkipUnconfirmedIsTrue() {
        val viewModel = SelectPlacesViewModel(
            placeRepository = fakePlaceRepo,
            userRepository = fakeUserRepo,
            currentUidProvider = { "test_uid" },
        )

        val matchedCandidate = MatchCandidate(kakaoPlaceId = "matched_1", name = "확정 장소")
        val matchedItem = MatchResult(
            localId = "img0_p0",
            extracted = GeminiPlace(name = "확정 장소"),
            status = MatchResult.STATUS_MATCHED,
            candidates = listOf(matchedCandidate),
            selectedPlaceId = "matched_1",
            checked = true,
        )

        val checkCandidate = MatchCandidate(kakaoPlaceId = "cand_1", name = "미선택 후보")
        val checkItem = MatchResult(
            localId = "img0_p1",
            extracted = GeminiPlace(name = "미선택 후보"),
            status = MatchResult.STATUS_CHECK,
            candidates = listOf(checkCandidate),
            selectedPlaceId = null,
            checked = true,
        )

        fakePlaceRepo.resultToReturn = AddPlacesResult(addedCount = 1, skippedCount = 0)

        viewModel.init("room_123", listOf(matchedItem, checkItem))
        viewModel.saveSelectedPlaces(skipUnconfirmed = true)

        val resultState = viewModel.saveResultEvent.value?.getContentIfNotHandled()
        assertNotNull(resultState)
        assertEquals(1, resultState!!.addedCount)
        assertEquals(0, resultState.skippedCount)
        assertEquals(1, resultState.excludedCount)

        assertEquals(1, fakePlaceRepo.addPlacesCallCount)
        assertEquals(1, fakePlaceRepo.lastPlacesSaved.size)
        assertEquals("matched_1", fakePlaceRepo.lastPlacesSaved[0].kakaoPlaceId)
    }

    @Test
    fun testSaveSelectedPlaces_handlesZeroRemainingSaveTargets() {
        val viewModel = SelectPlacesViewModel(
            placeRepository = fakePlaceRepo,
            userRepository = fakeUserRepo,
            currentUidProvider = { "test_uid" },
        )

        val checkCandidate = MatchCandidate(kakaoPlaceId = "cand_1", name = "미선택 후보")
        val checkItem = MatchResult(
            localId = "img0_p0",
            extracted = GeminiPlace(name = "미선택 후보"),
            status = MatchResult.STATUS_CHECK,
            candidates = listOf(checkCandidate),
            selectedPlaceId = null,
            checked = true,
        )

        viewModel.init("room_123", listOf(checkItem))
        viewModel.saveSelectedPlaces(skipUnconfirmed = true)

        val resultState = viewModel.saveResultEvent.value?.getContentIfNotHandled()
        assertNotNull(resultState)
        assertEquals(0, resultState!!.addedCount)
        assertEquals(0, resultState.skippedCount)
        assertEquals(1, resultState.excludedCount)

        assertEquals(0, fakePlaceRepo.addPlacesCallCount)
    }

    @Test
    fun testSaveSelectedPlaces_savesConfirmablePlaces_andCalculatesCounts() {
        val viewModel = SelectPlacesViewModel(
            placeRepository = fakePlaceRepo,
            userRepository = fakeUserRepo,
            currentUidProvider = { "test_uid" },
        )

        val cand1 = MatchCandidate(kakaoPlaceId = "c1", name = "장소 1")
        val cand2 = MatchCandidate(kakaoPlaceId = "c2", name = "장소 2")

        val item1 = MatchResult(localId = "1", extracted = GeminiPlace(name = "장소 1"), status = MatchResult.STATUS_MATCHED, candidates = listOf(cand1), selectedPlaceId = "c1", checked = true)
        val item2 = MatchResult(localId = "2", extracted = GeminiPlace(name = "장소 2"), status = MatchResult.STATUS_MATCHED, candidates = listOf(cand2), selectedPlaceId = "c2", checked = true)

        fakePlaceRepo.resultToReturn = AddPlacesResult(addedCount = 1, skippedCount = 1) // 1 added, 1 duplicate skipped

        viewModel.init("room_123", listOf(item1, item2))
        viewModel.saveSelectedPlaces(skipUnconfirmed = false)

        val resultState = viewModel.saveResultEvent.value?.getContentIfNotHandled()
        assertNotNull(resultState)
        assertEquals(1, resultState!!.addedCount)
        assertEquals(1, resultState.skippedCount)
        assertEquals(0, resultState.excludedCount)
        assertEquals(1, fakePlaceRepo.addPlacesCallCount)
    }
}

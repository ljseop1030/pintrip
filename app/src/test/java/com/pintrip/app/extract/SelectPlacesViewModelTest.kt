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
import org.junit.Assert.assertFalse
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
        assertEquals(0, resultState.notFoundCount)
        assertEquals(1, fakePlaceRepo.addPlacesCallCount)
    }

    @Test
    fun testSaveSelectedPlaces_calculatesNotFoundCountCorrectly() {
        val viewModel = SelectPlacesViewModel(
            placeRepository = fakePlaceRepo,
            userRepository = fakeUserRepo,
            currentUidProvider = { "test_uid" },
        )

        val cand1 = MatchCandidate(kakaoPlaceId = "c1", name = "장소 1")
        val matchedItem = MatchResult(localId = "1", extracted = GeminiPlace(name = "장소 1"), status = MatchResult.STATUS_MATCHED, candidates = listOf(cand1), selectedPlaceId = "c1", checked = true)
        val notFoundItem = MatchResult(localId = "2", extracted = GeminiPlace(name = "찾지 못한 장소"), status = MatchResult.STATUS_NOT_FOUND, candidates = emptyList(), selectedPlaceId = null, checked = false)
        val checkItem = MatchResult(localId = "3", extracted = GeminiPlace(name = "미선택 장소"), status = MatchResult.STATUS_CHECK, candidates = listOf(cand1), selectedPlaceId = null, checked = true)

        fakePlaceRepo.resultToReturn = AddPlacesResult(addedCount = 1, skippedCount = 0)

        viewModel.init("room_123", listOf(matchedItem, notFoundItem, checkItem))
        assertEquals(1, viewModel.getNotFoundCount())

        viewModel.saveSelectedPlaces(skipUnconfirmed = true)

        val resultState = viewModel.saveResultEvent.value?.getContentIfNotHandled()
        assertNotNull(resultState)
        assertEquals(1, resultState!!.addedCount)
        assertEquals(0, resultState.skippedCount)
        assertEquals(1, resultState.excludedCount)
        assertEquals(1, resultState.notFoundCount)
    }

    @Test
    fun testInit_preservesSourceTypeForHeadline() {
        val viewModel = SelectPlacesViewModel(
            placeRepository = fakePlaceRepo,
            userRepository = fakeUserRepo,
            currentUidProvider = { "test_uid" },
        )

        val linkItem = MatchResult(localId = "1", extracted = GeminiPlace(name = "링크 장소"), status = MatchResult.STATUS_MATCHED, sourceType = "link", sourceUrl = "https://naver.me/abc")
        viewModel.init("room_123", listOf(linkItem))

        val results = viewModel.matchResults.value
        assertNotNull(results)
        assertEquals("link", results!![0].sourceType)
        assertEquals("https://naver.me/abc", results[0].sourceUrl)
    }

    @Test
    fun testSaveSelectedPlaces_manualSearchResult_setsMatchTypeManualAndNullSearchRegion() {
        val viewModel = SelectPlacesViewModel(
            placeRepository = fakePlaceRepo,
            userRepository = fakeUserRepo,
            currentUidProvider = { "test_uid" },
        )

        val manualCand = MatchCandidate(kakaoPlaceId = "manual_100", name = "이재모피자 본점", address = "부산 중구 광복중앙로 31")
        val manualItem = MatchResult(
            localId = "1",
            extracted = GeminiPlace(name = "추출명", region = "부산"),
            status = MatchResult.STATUS_CHECK,
            candidates = listOf(manualCand),
            selectedPlaceId = "manual_100",
            checked = true,
            manualQuery = "이재모피자",
        )

        fakePlaceRepo.resultToReturn = AddPlacesResult(addedCount = 1, skippedCount = 0)

        viewModel.init("room_123", listOf(manualItem))
        viewModel.saveSelectedPlaces(skipUnconfirmed = false)

        assertEquals(1, fakePlaceRepo.addPlacesCallCount)
        val savedPlace = fakePlaceRepo.lastPlacesSaved[0]
        assertEquals("manual_100", savedPlace.kakaoPlaceId)
        assertEquals("이재모피자", savedPlace.searchKeyword) // Uses manualQuery as searchKeyword
        assertEquals(null, savedPlace.searchRegion) // Manual search sets searchRegion to null
        assertEquals("manual", savedPlace.matchType) // matchType is "manual"
    }

    @Test
    fun testOnManualSearchResultPicked_appliedToNotFoundItem_makesItemConfirmableDecreasesNotFoundCountAndSavesAsManual() {
        val viewModel = SelectPlacesViewModel(
            placeRepository = fakePlaceRepo,
            userRepository = fakeUserRepo,
            currentUidProvider = { "test_uid" },
        )

        val notFoundItem = MatchResult(
            localId = "1",
            extracted = GeminiPlace(name = "찾지 못한 장소"),
            status = MatchResult.STATUS_NOT_FOUND,
            candidates = emptyList(),
            selectedPlaceId = null,
            checked = false,
        )

        viewModel.init("room_123", listOf(notFoundItem))

        // Initial checks
        assertEquals(1, viewModel.getNotFoundCount())
        assertEquals(0, viewModel.getConfirmableCheckedCount())
        assertFalse(viewModel.matchResults.value!![0].isConfirmable)

        // Apply manual search result to index 0
        val pickedCandidate = MatchCandidate(kakaoPlaceId = "manual_888", name = "수동검색 장소", address = "부산 금정구 오시게로 20")
        viewModel.onManualSearchResultPicked(index = 0, candidate = pickedCandidate, manualQuery = "수동검색어")

        // Post-picker checks
        val updatedItem = viewModel.matchResults.value!![0]
        assertTrue(updatedItem.isConfirmable)
        assertEquals("manual_888", updatedItem.selectedPlaceId)
        assertEquals("수동검색어", updatedItem.manualQuery)
        assertEquals(0, viewModel.getNotFoundCount())
        assertEquals(1, viewModel.getConfirmableCheckedCount())

        // Save check
        fakePlaceRepo.resultToReturn = AddPlacesResult(addedCount = 1, skippedCount = 0)
        viewModel.saveSelectedPlaces(skipUnconfirmed = false)

        assertEquals(1, fakePlaceRepo.addPlacesCallCount)
        val savedPlace = fakePlaceRepo.lastPlacesSaved[0]
        assertEquals("manual_888", savedPlace.kakaoPlaceId)
        assertEquals("수동검색어", savedPlace.searchKeyword)
        assertEquals(null, savedPlace.searchRegion)
        assertEquals("manual", savedPlace.matchType)
    }
}

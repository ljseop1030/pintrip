package com.pintrip.app.extract

import android.content.Context
import android.content.ContextWrapper
import com.pintrip.app.data.model.GeminiExtractionResponse
import com.pintrip.app.data.model.GeminiPlace
import com.pintrip.app.data.remote.GeminiExtractor
import com.pintrip.app.data.remote.WebPageFetcher
import com.pintrip.app.data.model.Room
import com.pintrip.app.data.repository.RoomRepository
import com.google.firebase.firestore.Source
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ShareReceiveViewModelTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private val testDispatcher = StandardTestDispatcher()

    private object DummyContext : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
    }

    private class FakeWebPageFetcher(
        private val pageTextToReturn: String? = null,
        private val exceptionToThrow: Exception? = null,
    ) : WebPageFetcher() {
        var requestedUrl: String? = null

        override suspend fun fetchPageText(url: String): String {
            requestedUrl = url
            if (exceptionToThrow != null) {
                throw exceptionToThrow
            }
            return pageTextToReturn ?: "Default Fake Page Content"
        }
    }

    private class FakeGeminiExtractor : GeminiExtractor(DummyContext) {
        var lastExtractedText: String? = null
        var lastRoomRegion: String? = null
        var responseToReturn: GeminiExtractionResponse = GeminiExtractionResponse(
            places = listOf(GeminiPlace(name = "모모스 커피", region = "금정구")),
        )

        override suspend fun extractFromText(text: String, roomRegion: String?): GeminiExtractionResponse {
            lastExtractedText = text
            lastRoomRegion = roomRegion
            return responseToReturn
        }
    }

    private class FakePlaceMatcher : PlaceMatcher() {
        override suspend fun matchPlaces(
            extractionResponses: List<GeminiExtractionResponse>,
            roomRegion: String,
            sourceType: String,
            sourceUrl: String?,
        ): List<MatchResult> {
            val places = extractionResponses.flatMap { it.places }
            return places.mapIndexed { idx, p ->
                MatchResult(
                    localId = "img0_p$idx",
                    extracted = p,
                    status = MatchResult.STATUS_MATCHED,
                    selectedPlaceId = "kakao_$idx",
                    regionMismatch = false,
                    checked = true,
                )
            }
        }
    }

    private class FakeRoomRepository(
        private val mockRegion: String = "부산"
    ) : RoomRepository(null, null, null) {
        override suspend fun getRoom(roomId: String, source: Source): Room? {
            return Room(
                id = roomId,
                name = "Test Room",
                region = mockRegion,
                icon = "sea",
                inviteCode = "ABCDEF",
                ownerId = "ownerId",
                memberIds = listOf("ownerId"),
                members = emptyMap(),
                placeCount = 0
            )
        }
        override suspend fun getMyRooms(source: Source): List<Room> {
            return listOf(getRoom("testRoomId", source)!!)
        }
    }

    private class ConfigurableFakeRoomRepository(
        val roomsToReturn: List<Room> = emptyList(),
        val exceptionToThrow: Exception? = null,
    ) : RoomRepository(null, null, null) {
        var fetchCount = 0
        override suspend fun getMyRooms(source: Source): List<Room> {
            fetchCount++
            if (exceptionToThrow != null) throw exceptionToThrow
            return roomsToReturn
        }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testStartExtractStep_linkText_combinesShareTextAndPageText() = runTest {
        val fakeWebPageFetcher = FakeWebPageFetcher(pageTextToReturn = "블로그 포스팅 본문입니다.")
        val fakeGeminiExtractor = FakeGeminiExtractor()
        val fakePlaceMatcher = FakePlaceMatcher()

        val viewModel = ShareReceiveViewModel(
            geminiExtractorFactory = { fakeGeminiExtractor },
            webPageFetcherFactory = { fakeWebPageFetcher },
            placeMatcherFactory = { fakePlaceMatcher },
            roomRepository = FakeRoomRepository("부산"),
        )
        viewModel.fetchRooms()

        val shareInput = ShareInput.LinkText(
            text = "[네이버 지도] 모모스 커피 부산 금정구 오시게로 20 https://naver.me/abc",
            url = "https://naver.me/abc",
        )

        viewModel.startExtractStep(DummyContext, "testRoomId", shareInput)
        testDispatcher.scheduler.advanceUntilIdle()

        val expectedCombinedText = "[네이버 지도] 모모스 커피 부산 금정구 오시게로 20 https://naver.me/abc\n\n블로그 포스팅 본문입니다."
        assertEquals("https://naver.me/abc", fakeWebPageFetcher.requestedUrl)
        assertEquals(expectedCombinedText, fakeGeminiExtractor.lastExtractedText)
        assertEquals("부산", fakeGeminiExtractor.lastRoomRegion)
    }

    @Test
    fun testStartExtractStep_linkText_webPageFetchFails_fallsBackToShareText() = runTest {
        val fakeWebPageFetcher = FakeWebPageFetcher(exceptionToThrow = RuntimeException("Network Error 404"))
        val fakeGeminiExtractor = FakeGeminiExtractor()
        val fakePlaceMatcher = FakePlaceMatcher()

        val viewModel = ShareReceiveViewModel(
            geminiExtractorFactory = { fakeGeminiExtractor },
            webPageFetcherFactory = { fakeWebPageFetcher },
            placeMatcherFactory = { fakePlaceMatcher },
            roomRepository = FakeRoomRepository("부산"),
        )
        viewModel.fetchRooms()

        val shareInput = ShareInput.LinkText(
            text = "[카카오맵] 모모스 커피 https://kakaomap.place/123",
            url = "https://kakaomap.place/123",
        )

        viewModel.startExtractStep(DummyContext, "testRoomId", shareInput)
        testDispatcher.scheduler.advanceUntilIdle()

        // webPageFetcher 에러 시 fallback으로 shareInput.text만 전달되어야 함 (중복 결합 없음)
        assertEquals("[카카오맵] 모모스 커피 https://kakaomap.place/123", fakeGeminiExtractor.lastExtractedText)
    }

    @Test
    fun testStartExtractStep_linkText_webPageFetchCancellationException_propagates() = runTest {
        val fakeWebPageFetcher = FakeWebPageFetcher(exceptionToThrow = CancellationException("Coroutines Cancelled"))
        val fakeGeminiExtractor = FakeGeminiExtractor()
        val fakePlaceMatcher = FakePlaceMatcher()

        val viewModel = ShareReceiveViewModel(
            geminiExtractorFactory = { fakeGeminiExtractor },
            webPageFetcherFactory = { fakeWebPageFetcher },
            placeMatcherFactory = { fakePlaceMatcher },
            roomRepository = FakeRoomRepository("부산"),
        )
        viewModel.fetchRooms()

        val shareInput = ShareInput.LinkText(
            text = "공유 텍스트",
            url = "https://naver.me/abc",
        )

        viewModel.startExtractStep(DummyContext, "testRoomId", shareInput)
        testDispatcher.scheduler.advanceUntilIdle()

        // CancellationException 발생 시 geminiExtractor.extractFromText는 호출되지 않으며 Step2Status는 IN_PROGRESS 상태 유지
        assertEquals(null, fakeGeminiExtractor.lastExtractedText)
        assertEquals(StepStatus.IN_PROGRESS, viewModel.stepsState.value?.step2Status)
    }

    @Test
    fun testStartExtractStep_linkText_longPageText_preservesShareTextIntact() = runTest {
        val longPageText = "A".repeat(30000)
        val fakeWebPageFetcher = FakeWebPageFetcher(pageTextToReturn = longPageText)
        val fakeGeminiExtractor = FakeGeminiExtractor()
        val fakePlaceMatcher = FakePlaceMatcher()

        val viewModel = ShareReceiveViewModel(
            geminiExtractorFactory = { fakeGeminiExtractor },
            webPageFetcherFactory = { fakeWebPageFetcher },
            placeMatcherFactory = { fakePlaceMatcher },
            roomRepository = FakeRoomRepository("부산"),
        )
        viewModel.fetchRooms()

        val shareInput = ShareInput.LinkText(
            text = "중요 장소 공유 텍스트",
            url = "https://naver.me/abc",
        )

        viewModel.startExtractStep(DummyContext, "testRoomId", shareInput)
        testDispatcher.scheduler.advanceUntilIdle()

        val extractedText = fakeGeminiExtractor.lastExtractedText
        assertNotNull(extractedText)
        assertTrue(extractedText!!.startsWith("중요 장소 공유 텍스트\n\n"))
        assertEquals(GeminiExtractor.MAX_TEXT_LENGTH, extractedText.length)
    }

    @Test
    fun testFetchRooms_emptyList_online_showsRoomListEmpty() = runTest {
        val repo = ConfigurableFakeRoomRepository(roomsToReturn = emptyList())
        val viewModel = ShareReceiveViewModel(
            roomRepository = repo,
            isOnline = { true }
        )
        viewModel.fetchRooms()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(0, viewModel.rooms.value?.size)
        assertEquals(com.pintrip.app.R.string.room_list_empty, viewModel.roomLoadError.value)
        assertEquals(null, viewModel.selectedRoom.value)
    }

    @Test
    fun testFetchRooms_emptyList_offline_showsNetworkUnavailable() = runTest {
        val repo = ConfigurableFakeRoomRepository(roomsToReturn = emptyList())
        val viewModel = ShareReceiveViewModel(
            roomRepository = repo,
            isOnline = { false }
        )
        viewModel.fetchRooms()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(0, viewModel.rooms.value?.size)
        assertEquals(com.pintrip.app.R.string.error_network_unavailable, viewModel.roomLoadError.value)
        assertEquals(null, viewModel.selectedRoom.value)
    }

    @Test
    fun testFetchRooms_singleRoom_autoSelectsRoom() = runTest {
        val room1 = Room(id = "r1", name = "Room 1", region = "부산", icon = "sea", inviteCode = "ABC123", ownerId = "o1", memberIds = listOf("o1"), members = emptyMap(), placeCount = 0)
        val repo = ConfigurableFakeRoomRepository(roomsToReturn = listOf(room1))
        val viewModel = ShareReceiveViewModel(
            roomRepository = repo,
            isOnline = { true }
        )
        viewModel.fetchRooms()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, viewModel.rooms.value?.size)
        assertEquals(null, viewModel.roomLoadError.value)
        assertEquals("r1", viewModel.selectedRoom.value?.id)
    }

    @Test
    fun testFetchRooms_multipleRooms_doesNotSelectRoom() = runTest {
        val room1 = Room(id = "r1", name = "Room 1", region = "부산", icon = "sea", inviteCode = "ABC123", ownerId = "o1", memberIds = listOf("o1"), members = emptyMap(), placeCount = 0)
        val room2 = Room(id = "r2", name = "Room 2", region = "서울", icon = "city", inviteCode = "DEF456", ownerId = "o1", memberIds = listOf("o1"), members = emptyMap(), placeCount = 0)
        val repo = ConfigurableFakeRoomRepository(roomsToReturn = listOf(room1, room2))
        val viewModel = ShareReceiveViewModel(
            roomRepository = repo,
            isOnline = { true }
        )
        viewModel.fetchRooms()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(2, viewModel.rooms.value?.size)
        assertEquals(null, viewModel.roomLoadError.value)
        assertEquals(null, viewModel.selectedRoom.value)
    }

    @Test
    fun testFetchRooms_notLoggedIn_showsNotLoggedInError() = runTest {
        val repo = ConfigurableFakeRoomRepository(exceptionToThrow = IllegalStateException("로그인된 사용자 정보가 없습니다. (Auth currentUser is null)"))
        val viewModel = ShareReceiveViewModel(
            roomRepository = repo,
            isOnline = { true }
        )
        viewModel.fetchRooms()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(0, viewModel.rooms.value?.size)
        assertEquals(com.pintrip.app.R.string.error_not_logged_in, viewModel.roomLoadError.value)
        assertEquals(null, viewModel.selectedRoom.value)
    }

    @Test
    fun testFetchRooms_duplicateCall_fetchesOnlyOnce() = runTest {
        val room1 = Room(id = "r1", name = "Room 1", region = "부산", icon = "sea", inviteCode = "ABC123", ownerId = "o1", memberIds = listOf("o1"), members = emptyMap(), placeCount = 0)
        val repo = ConfigurableFakeRoomRepository(roomsToReturn = listOf(room1))
        val viewModel = ShareReceiveViewModel(
            roomRepository = repo,
            isOnline = { true }
        )
        viewModel.fetchRooms()
        viewModel.fetchRooms()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, repo.fetchCount)
    }
}

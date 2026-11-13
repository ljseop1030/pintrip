package com.pintrip.app.extract

import android.content.Context
import android.content.ContextWrapper
import com.pintrip.app.data.model.GeminiExtractionResponse
import com.pintrip.app.data.model.GeminiPlace
import com.pintrip.app.data.remote.GeminiExtractor
import com.pintrip.app.data.remote.WebPageFetcher
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
            roomRegionFetcher = { "부산" },
        )

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
            roomRegionFetcher = { "부산" },
        )

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
            roomRegionFetcher = { "부산" },
        )

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
            roomRegionFetcher = { "부산" },
        )

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
}

package com.pintrip.app.extract

import com.pintrip.app.data.model.GeminiExtractionResponse
import com.pintrip.app.data.model.GeminiPlace
import com.pintrip.app.data.model.KakaoKeywordSearchResponse
import com.pintrip.app.data.model.KakaoPlaceDocument
import com.pintrip.app.data.remote.KakaoLocalApiClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class PlaceMatcherTest {

    private class FakeKakaoLocalApiClient : KakaoLocalApiClient() {
        val searchCalls: MutableList<Pair<String, String?>> = Collections.synchronizedList(mutableListOf())
        val responses = ConcurrentHashMap<Pair<String, String?>, KakaoKeywordSearchResponse>()
        val delays = ConcurrentHashMap<String, Long>()
        val exceptions = ConcurrentHashMap<String, Exception>()
        var onSearch: (suspend (query: String, categoryGroupCode: String?) -> Unit)? = null

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
            searchCalls.add(Pair(query, categoryGroupCode))

            val delayMs = delays[query]
            if (delayMs != null && delayMs > 0) {
                kotlinx.coroutines.delay(delayMs)
            }

            onSearch?.invoke(query, categoryGroupCode)

            val exc = exceptions[query]
            if (exc != null) {
                throw exc
            }

            return responses[Pair(query, categoryGroupCode)] ?: KakaoKeywordSearchResponse()
        }
    }

    private class CancellingKakaoLocalApiClient : KakaoLocalApiClient() {
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
            throw CancellationException("Cancelled in test")
        }
    }

    @Test
    fun testMatchPlaces_emptyResponses() = runBlocking {
        val fakeClient = FakeKakaoLocalApiClient()
        val matcher = PlaceMatcher(fakeClient)

        val results = matcher.matchPlaces(emptyList(), "부산")

        assertTrue(results.isEmpty())
        assertTrue(fakeClient.searchCalls.isEmpty())
    }

    @Test
    fun testMatchPlaces_singleResponseSuccess() = runBlocking {
        val fakeClient = FakeKakaoLocalApiClient()
        val queryKey = Pair("부산 금정구 모모스커피", "CE7")
        fakeClient.responses[queryKey] = KakaoKeywordSearchResponse(
            documents = listOf(
                KakaoPlaceDocument(
                    id = "12345",
                    placeName = "모모스커피 본점",
                    addressName = "부산 금정구 오시게로 20",
                    roadAddressName = "부산 금정구 오시게로 20",
                    categoryGroupCode = "CE7",
                ),
            ),
        )

        val matcher = PlaceMatcher(fakeClient)
        val extraction = GeminiExtractionResponse(
            places = listOf(
                GeminiPlace(name = "모모스커피", region = "금정구", categoryHint = "cafe"),
            ),
        )

        val results = matcher.matchPlaces(extraction, "부산")

        assertEquals(1, results.size)
        val match = results.first()
        assertEquals(MatchResult.STATUS_MATCHED, match.status)
        assertEquals("12345", match.selectedPlaceId)
        assertFalse(match.regionMismatch)
        assertTrue(match.checked)
    }

    @Test
    fun testMatchPlaces_retryWithoutCategoryWhenZeroResults() = runBlocking {
        val fakeClient = FakeKakaoLocalApiClient()
        // 1차 검색 (카테고리 CE7 포함) -> 0개 결과
        val initialKey = Pair("부산 해운대 특이한카페", "CE7")
        fakeClient.responses[initialKey] = KakaoKeywordSearchResponse(documents = emptyList())

        // 2차 재검색 (카테고리 null) -> 1개 결과
        val retryKey = Pair("부산 해운대 특이한카페", null)
        fakeClient.responses[retryKey] = KakaoKeywordSearchResponse(
            documents = listOf(
                KakaoPlaceDocument(
                    id = "99999",
                    placeName = "특이한카페",
                    addressName = "부산 해운대구 우동 100",
                    roadAddressName = "부산 해운대구 우동로 10",
                ),
            ),
        )

        val matcher = PlaceMatcher(fakeClient)
        val extraction = GeminiExtractionResponse(
            places = listOf(
                GeminiPlace(name = "특이한카페", region = "해운대", categoryHint = "cafe"),
            ),
        )

        val results = matcher.matchPlaces(extraction, "부산")

        // 총 2회 검색 호출이 이루어졌는지 확인 (1차: CE7, 2차: null)
        assertEquals(2, fakeClient.searchCalls.size)
        assertEquals(initialKey, fakeClient.searchCalls[0])
        assertEquals(retryKey, fakeClient.searchCalls[1])

        assertEquals(1, results.size)
        val match = results.first()
        assertEquals(MatchResult.STATUS_MATCHED, match.status)
        assertEquals("99999", match.selectedPlaceId)
    }

    @Test
    fun testMatchPlaces_mergesMultipleResponsesFirst() = runBlocking {
        val fakeClient = FakeKakaoLocalApiClient()
        val queryKey1 = Pair("부산 모모스 커피", "CE7")
        fakeClient.responses[queryKey1] = KakaoKeywordSearchResponse(
            documents = listOf(KakaoPlaceDocument(id = "1", placeName = "모모스커피")),
        )

        val matcher = PlaceMatcher(fakeClient)
        val res1 = GeminiExtractionResponse(places = listOf(GeminiPlace(name = "모모스 커피", categoryHint = "cafe")))
        val res2 = GeminiExtractionResponse(places = listOf(GeminiPlace(name = "모모스커피", categoryHint = "cafe"))) // 중복

        val results = matcher.matchPlaces(listOf(res1, res2), "부산")

        // 중복 병합되어 단 1회의 매칭 결과만 생성되어야 함
        assertEquals(1, results.size)
        assertEquals(1, fakeClient.searchCalls.size)
    }

    @Test
    fun testMatchPlaces_detectsRegionMismatch() = runBlocking {
        val fakeClient = FakeKakaoLocalApiClient()
        val queryKey = Pair("서울 오설록", null)
        fakeClient.responses[queryKey] = KakaoKeywordSearchResponse(
            documents = listOf(
                KakaoPlaceDocument(
                    id = "777",
                    placeName = "오설록 티하우스 명동점",
                    roadAddressName = "서울 중구 명동길 12",
                ),
            ),
        )

        val matcher = PlaceMatcher(fakeClient)
        val extraction = GeminiExtractionResponse(
            places = listOf(
                GeminiPlace(name = "오설록", region = "서울", outsideRoomRegion = true),
            ),
        )

        val results = matcher.matchPlaces(extraction, "부산") // 방 지역은 부산, 장소는 서울

        assertEquals(1, results.size)
        val match = results.first()
        assertTrue(match.regionMismatch)
        assertFalse(match.checked) // regionMismatch 시 기본 체크 해제
    }

    @Test
    fun testMatchPlaces_regionMismatchEvaluatedBySelectedCandidateNotFirstCandidate() = runBlocking {
        val fakeClient = FakeKakaoLocalApiClient()
        val queryKey = Pair("부산 모모스커피", null)
        fakeClient.responses[queryKey] = KakaoKeywordSearchResponse(
            documents = listOf(
                KakaoPlaceDocument(
                    id = "cand_1",
                    placeName = "모모스커피 센텀점",
                    roadAddressName = "부산 해운대구 센텀남대로 35", // 1번째 후보: 부산 주소 (정확 일치 아님)
                ),
                KakaoPlaceDocument(
                    id = "cand_2",
                    placeName = "모모스커피",
                    roadAddressName = "서울 강남구 테헤란로 123", // 2번째 후보: 서울 주소 (정확 일치! selectedCandidate로 지정됨)
                ),
            ),
        )

        val matcher = PlaceMatcher(fakeClient)
        val extraction = GeminiExtractionResponse(
            places = listOf(
                GeminiPlace(name = "모모스커피", outsideRoomRegion = false),
            ),
        )

        val results = matcher.matchPlaces(extraction, "부산")

        assertEquals(1, results.size)
        val match = results.first()

        assertEquals(MatchResult.STATUS_MATCHED, match.status)
        assertEquals("cand_2", match.selectedPlaceId)
        assertEquals("cand_2", match.selectedCandidate?.kakaoPlaceId)

        // 1번째 후보(부산 주소)가 아닌 선택된 후보 cand_2(서울 주소) 기반으로 regionMismatch가 판단되어야 함
        assertTrue(match.regionMismatch)
        assertFalse(match.checked) // regionMismatch 시 checked = false
    }

    @Test
    fun testMatchPlaces_whenCheckStatus_noAddressEvaluationAndKeepGeminiFlag() = runBlocking {
        val fakeClient = FakeKakaoLocalApiClient()
        val queryKey = Pair("부산 모모스커피", null)
        fakeClient.responses[queryKey] = KakaoKeywordSearchResponse(
            documents = listOf(
                KakaoPlaceDocument(
                    id = "cand_1",
                    placeName = "모모스커피 센텀점",
                    roadAddressName = "서울 중구 명동길 12", // 1번째 후보가 서울 주소지만, CHECK 상태라 선택된 후보 없음
                ),
                KakaoPlaceDocument(
                    id = "cand_2",
                    placeName = "모모스커피 마린시티점",
                    roadAddressName = "부산 해운대구 해운대해변로 12",
                ),
            ),
        )

        val matcher = PlaceMatcher(fakeClient)
        val extraction = GeminiExtractionResponse(
            places = listOf(
                GeminiPlace(name = "모모스커피", outsideRoomRegion = false),
            ),
        )

        val results = matcher.matchPlaces(extraction, "부산")

        assertEquals(1, results.size)
        val match = results.first()

        assertEquals(MatchResult.STATUS_CHECK, match.status)
        assertNull(match.selectedPlaceId)
        assertNull(match.selectedCandidate)

        // CHECK 상태라 주소 판정을 수행하지 않으며, Gemini의 outsideRoomRegion = false 가 그대로 유지됨
        assertFalse(match.regionMismatch)
        assertTrue(match.checked)
    }

    @Test
    fun testMatchPlaces_parallelExecutionPreservesOrder() = runBlocking {
        val fakeClient = FakeKakaoLocalApiClient()
        val places = (1..10).map { idx ->
            GeminiPlace(name = "장소 $idx", rawText = "장소 $idx")
        }

        places.forEachIndexed { idx, p ->
            val queryKey = Pair("부산 ${p.name}", null)
            fakeClient.responses[queryKey] = KakaoKeywordSearchResponse(
                documents = listOf(
                    KakaoPlaceDocument(id = "id_${idx + 1}", placeName = p.name),
                ),
            )
        }

        val matcher = PlaceMatcher(fakeClient, concurrencyLimit = 3)
        val results = matcher.matchPlaces(GeminiExtractionResponse(places = places), "부산")

        assertEquals(10, results.size)
        results.forEachIndexed { idx, result ->
            assertEquals("장소 ${idx + 1}", result.extracted.name)
            assertEquals("img0_p$idx", result.localId)
            assertEquals("id_${idx + 1}", result.selectedPlaceId)
        }
    }

    @Test
    fun testMatchPlaces_invertedCompletionOrder_preservesResultOrderAndLocalId() = runBlocking {
        val fakeClient = FakeKakaoLocalApiClient()

        val places = listOf(
            GeminiPlace(name = "느린장소0", rawText = "느린장소0"),
            GeminiPlace(name = "중간장소1", rawText = "중간장소1"),
            GeminiPlace(name = "빠른장소2", rawText = "빠른장소2"),
        )

        // 장소0: 200ms 지연, 장소1: 100ms 지연, 장소2: 10ms 지연 (완료 순서: 장소2 -> 장소1 -> 장소0)
        fakeClient.delays["부산 느린장소0"] = 200L
        fakeClient.delays["부산 중간장소1"] = 100L
        fakeClient.delays["부산 빠른장소2"] = 10L

        places.forEachIndexed { idx, p ->
            val queryKey = Pair("부산 ${p.name}", null)
            fakeClient.responses[queryKey] = KakaoKeywordSearchResponse(
                documents = listOf(
                    KakaoPlaceDocument(id = "id_$idx", placeName = p.name),
                ),
            )
        }

        val matcher = PlaceMatcher(fakeClient, concurrencyLimit = 5)
        val results = matcher.matchPlaces(GeminiExtractionResponse(places = places), "부산")

        // 완료 순서가 뒤집혀도 입력 순서 및 localId가 유지되는지 검증
        assertEquals(3, results.size)

        assertEquals("느린장소0", results[0].extracted.name)
        assertEquals("img0_p0", results[0].localId)
        assertEquals("id_0", results[0].selectedPlaceId)

        assertEquals("중간장소1", results[1].extracted.name)
        assertEquals("img0_p1", results[1].localId)
        assertEquals("id_1", results[1].selectedPlaceId)

        assertEquals("빠른장소2", results[2].extracted.name)
        assertEquals("img0_p2", results[2].localId)
        assertEquals("id_2", results[2].selectedPlaceId)
    }

    @Test
    fun testMatchPlaces_activeRequestsDoNotExceedConcurrencyLimitAndRunsInParallel() = runBlocking {
        val fakeClient = FakeKakaoLocalApiClient()
        val concurrencyLimit = 3
        val places = (0..9).map { idx ->
            GeminiPlace(name = "장소_$idx", rawText = "장소_$idx")
        }

        places.forEachIndexed { idx, p ->
            val queryKey = Pair("부산 ${p.name}", null)
            fakeClient.responses[queryKey] = KakaoKeywordSearchResponse(
                documents = listOf(
                    KakaoPlaceDocument(id = "id_$idx", placeName = p.name),
                ),
            )
        }

        val activeRequests = AtomicInteger(0)
        val maxActiveRequests = AtomicInteger(0)

        fakeClient.onSearch = { _, _ ->
            val current = activeRequests.incrementAndGet()
            maxActiveRequests.accumulateAndGet(current) { maxVal, curr -> maxOf(maxVal, curr) }
            kotlinx.coroutines.delay(50L) // 동시 요청 수를 측정하기 위한 인위적 지연
            activeRequests.decrementAndGet()
        }

        val matcher = PlaceMatcher(fakeClient, concurrencyLimit = concurrencyLimit)
        val results = matcher.matchPlaces(GeminiExtractionResponse(places = places), "부산")

        assertEquals(10, results.size)
        // 최대 동시 요청 수가 concurrencyLimit(3)을 초과하지 않는지 검증
        assertTrue(
            "최대 동시 요청 수(${maxActiveRequests.get()})가 concurrencyLimit($concurrencyLimit) 이하이어야 함",
            maxActiveRequests.get() <= concurrencyLimit,
        )
        // 실제 병렬로 실행되었는지 확인하기 위해 최대 동시 요청 수가 1보다 큰지 검증
        assertTrue(
            "실제 병렬 실행 여부 확인: 최대 동시 요청 수(${maxActiveRequests.get()})가 1보다 커야 함",
            maxActiveRequests.get() > 1,
        )
    }

    @Test
    fun testMatchPlaces_ioExceptionOnSpecificQuery_onlyThatPlaceIsNotFoundAndOthersSucceed() = runBlocking {
        val fakeClient = FakeKakaoLocalApiClient()

        val places = listOf(
            GeminiPlace(name = "성공장소0", rawText = "성공장소0"),
            GeminiPlace(name = "에러장소1", rawText = "에러장소1"),
            GeminiPlace(name = "성공장소2", rawText = "성공장소2"),
        )

        // 성공하는 장소 0, 2의 응답 등록
        fakeClient.responses[Pair("부산 성공장소0", null)] = KakaoKeywordSearchResponse(
            documents = listOf(KakaoPlaceDocument(id = "id_0", placeName = "성공장소0")),
        )
        fakeClient.responses[Pair("부산 성공장소2", null)] = KakaoKeywordSearchResponse(
            documents = listOf(KakaoPlaceDocument(id = "id_2", placeName = "성공장소2")),
        )

        // 특정 쿼리("부산 에러장소1")에서만 IOException 발생 설정
        fakeClient.exceptions["부산 에러장소1"] = IOException("Network connection error for 에러장소1")

        val matcher = PlaceMatcher(fakeClient)
        val results = matcher.matchPlaces(GeminiExtractionResponse(places = places), "부산")

        assertEquals(3, results.size)

        // 장소 0: 정상 매칭 성공
        assertEquals("성공장소0", results[0].extracted.name)
        assertEquals("img0_p0", results[0].localId)
        assertEquals(MatchResult.STATUS_MATCHED, results[0].status)
        assertEquals("id_0", results[0].selectedPlaceId)

        // 장소 1: 예외 발생으로 해당 장소만 NOT_FOUND 처리
        assertEquals("에러장소1", results[1].extracted.name)
        assertEquals("img0_p1", results[1].localId)
        assertEquals(MatchResult.STATUS_NOT_FOUND, results[1].status)
        assertNull(results[1].selectedPlaceId)
        assertTrue(results[1].candidates.isEmpty())

        // 장소 2: 정상 매칭 성공 (영향 받지 않음)
        assertEquals("성공장소2", results[2].extracted.name)
        assertEquals("img0_p2", results[2].localId)
        assertEquals(MatchResult.STATUS_MATCHED, results[2].status)
        assertEquals("id_2", results[2].selectedPlaceId)
    }

    @Test(expected = CancellationException::class)
    fun testMatchPlaces_cancellationExceptionPropagated() {
        runBlocking {
            val matcher = PlaceMatcher(CancellingKakaoLocalApiClient())
            val extraction = GeminiExtractionResponse(
                places = listOf(GeminiPlace(name = "테스트")),
            )
            matcher.matchPlaces(extraction, "부산")
        }
    }
}

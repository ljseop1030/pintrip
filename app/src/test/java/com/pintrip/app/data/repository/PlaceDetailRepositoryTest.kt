package com.pintrip.app.data.repository

import com.pintrip.app.data.model.KakaoKeywordSearchResponse
import com.pintrip.app.data.model.KakaoPlaceDocument
import com.pintrip.app.data.model.Place
import com.pintrip.app.data.model.PlaceDetail
import com.pintrip.app.data.remote.KakaoLocalApiClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

class PlaceDetailRepositoryTest {

    private data class SearchKey(val query: String, val page: Int = 1)
    private data class SearchCall(val query: String, val categoryGroupCode: String?, val page: Int?, val size: Int?)

    private class FakeKakaoLocalApiClient : KakaoLocalApiClient() {
        val calls: MutableList<SearchCall> = Collections.synchronizedList(mutableListOf())
        val responses = ConcurrentHashMap<SearchKey, KakaoKeywordSearchResponse>()
        val exceptions = ConcurrentHashMap<String, Exception>()
        val delays = ConcurrentHashMap<String, Long>()

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
            val currentPage = page ?: 1
            calls.add(SearchCall(query, categoryGroupCode, currentPage, size))

            val delayMs = delays[query]
            if (delayMs != null && delayMs > 0) {
                delay(delayMs)
            }

            val exc = exceptions[query]
            if (exc != null) {
                throw exc
            }

            val key = SearchKey(query, currentPage)
            return responses[key] ?: KakaoKeywordSearchResponse()
        }
    }

    @Test
    fun testStep1Found_StatusFoundAndSingleCall() = runBlocking {
        val fakeKakao = FakeKakaoLocalApiClient()
        val place = Place(
            kakaoPlaceId = "101",
            searchKeyword = "모모스커피",
            searchRegion = "해운대",
        )
        val loadPlaces: suspend (String) -> List<Place> = { listOf(place) }
        val repository = PlaceDetailRepository(loadPlaces = loadPlaces, kakaoLocalApiClient = fakeKakao)

        val expectedDoc = KakaoPlaceDocument(
            id = "101",
            placeName = "모모스커피 본점",
            roadAddressName = "부산 해운대구 오시게로 20",
            x = "129.08",
            y = "35.22",
            categoryGroupCode = "CE7",
            phone = "051-123-4567",
        )
        fakeKakao.responses[SearchKey("부산 해운대 모모스커피", 1)] = KakaoKeywordSearchResponse(
            documents = listOf(expectedDoc),
        )

        val result = repository.getPlaceDetails("room1", "부산")

        assertEquals(1, result.size)
        val detail = result[0]
        assertEquals(PlaceDetail.STATUS_FOUND, detail.status)
        assertEquals("모모스커피 본점", detail.name)
        assertEquals("부산 해운대구 오시게로 20", detail.address)
        assertEquals(35.22, detail.lat!!, 0.0001)
        assertEquals(129.08, detail.lng!!, 0.0001)
        assertEquals("cafe", detail.category)
        assertEquals("051-123-4567", detail.phone)

        assertEquals(1, fakeKakao.calls.size)
        assertEquals("부산 해운대 모모스커피", fakeKakao.calls[0].query)
        assertEquals(1, fakeKakao.calls[0].page)
        assertNull(fakeKakao.calls[0].categoryGroupCode)
    }

    @Test
    fun testStep1NotFound_Step2Found_StatusFound() = runBlocking {
        val fakeKakao = FakeKakaoLocalApiClient()
        val place = Place(
            kakaoPlaceId = "102",
            searchKeyword = "맛있는집",
            searchRegion = "해운대",
        )
        val loadPlaces: suspend (String) -> List<Place> = { listOf(place) }
        val repository = PlaceDetailRepository(loadPlaces = loadPlaces, kakaoLocalApiClient = fakeKakao)

        // 1단계: empty response
        fakeKakao.responses[SearchKey("부산 해운대 맛있는집", 1)] = KakaoKeywordSearchResponse()

        // 2단계: found
        val expectedDoc = KakaoPlaceDocument(
            id = "102",
            placeName = "해운대 맛있는집",
            roadAddressName = "부산 해운대구 우동 100",
        )
        fakeKakao.responses[SearchKey("해운대 맛있는집", 1)] = KakaoKeywordSearchResponse(
            documents = listOf(expectedDoc),
        )

        val result = repository.getPlaceDetails("room1", "부산")

        assertEquals(1, result.size)
        assertEquals(PlaceDetail.STATUS_FOUND, result[0].status)
        assertEquals("해운대 맛있는집", result[0].name)

        assertEquals(2, fakeKakao.calls.size)
        assertEquals("부산 해운대 맛있는집", fakeKakao.calls[0].query)
        assertEquals(1, fakeKakao.calls[0].page)
        assertEquals("해운대 맛있는집", fakeKakao.calls[1].query)
        assertEquals(1, fakeKakao.calls[1].page)
    }

    @Test
    fun testNotFound_CallsUpTo3Times_ReturnsNotFound() = runBlocking {
        val fakeKakao = FakeKakaoLocalApiClient()
        val place = Place(
            kakaoPlaceId = "999",
            searchKeyword = "없는장소",
            searchRegion = "해운대",
        )
        val loadPlaces: suspend (String) -> List<Place> = { listOf(place) }
        val repository = PlaceDetailRepository(loadPlaces = loadPlaces, kakaoLocalApiClient = fakeKakao)

        // All 3 steps return empty
        fakeKakao.responses[SearchKey("부산 해운대 없는장소", 1)] = KakaoKeywordSearchResponse()
        fakeKakao.responses[SearchKey("해운대 없는장소", 1)] = KakaoKeywordSearchResponse()
        fakeKakao.responses[SearchKey("부산 해운대 없는장소", 2)] = KakaoKeywordSearchResponse()

        val result = repository.getPlaceDetails("room1", "부산")

        assertEquals(1, result.size)
        val detail = result[0]
        assertEquals(PlaceDetail.STATUS_NOT_FOUND, detail.status)
        assertNull(detail.name)
        assertNull(detail.address)
        assertNull(detail.lat)
        assertNull(detail.lng)
        assertNull(detail.category)
        assertNull(detail.phone)

        assertEquals(3, fakeKakao.calls.size)
        assertEquals("부산 해운대 없는장소", fakeKakao.calls[0].query)
        assertEquals(1, fakeKakao.calls[0].page)
        assertEquals("해운대 없는장소", fakeKakao.calls[1].query)
        assertEquals(1, fakeKakao.calls[1].page)
        assertEquals("부산 해운대 없는장소", fakeKakao.calls[2].query)
        assertEquals(2, fakeKakao.calls[2].page)
    }

    @Test
    fun testNullSearchRegion_SkipsStep2() = runBlocking {
        val fakeKakao = FakeKakaoLocalApiClient()
        val place = Place(
            kakaoPlaceId = "103",
            searchKeyword = "카페A",
            searchRegion = null,
        )
        val loadPlaces: suspend (String) -> List<Place> = { listOf(place) }
        val repository = PlaceDetailRepository(loadPlaces = loadPlaces, kakaoLocalApiClient = fakeKakao)

        // 1단계 (page 1): empty
        fakeKakao.responses[SearchKey("부산 카페A", 1)] = KakaoKeywordSearchResponse()

        // 3단계 (page 2): found
        val expectedDoc = KakaoPlaceDocument(
            id = "103",
            placeName = "카페A 부산점",
        )
        fakeKakao.responses[SearchKey("부산 카페A", 2)] = KakaoKeywordSearchResponse(
            documents = listOf(expectedDoc),
        )

        val result = repository.getPlaceDetails("room1", "부산")

        assertEquals(1, result.size)
        assertEquals(PlaceDetail.STATUS_FOUND, result[0].status)
        assertEquals("카페A 부산점", result[0].name)

        // Step 2 was skipped, so only 2 calls were made (page 1 then page 2)
        assertEquals(2, fakeKakao.calls.size)
        assertEquals("부산 카페A", fakeKakao.calls[0].query)
        assertEquals(1, fakeKakao.calls[0].page)
        assertEquals("부산 카페A", fakeKakao.calls[1].query)
        assertEquals(2, fakeKakao.calls[1].page)
    }

    @Test
    fun testSinglePlaceException_OnlyThatPlaceIsNotFound_OthersNormal() = runBlocking {
        val fakeKakao = FakeKakaoLocalApiClient()
        val placeErr = Place(
            kakaoPlaceId = "ERR",
            searchKeyword = "에러장소",
        )
        val placeOk = Place(
            kakaoPlaceId = "200",
            searchKeyword = "정상장소",
        )
        val loadPlaces: suspend (String) -> List<Place> = { listOf(placeErr, placeOk) }
        val repository = PlaceDetailRepository(loadPlaces = loadPlaces, kakaoLocalApiClient = fakeKakao)

        // Exception for ERR
        fakeKakao.exceptions["부산 에러장소"] = IOException("네트워크 오류")

        // Success for 200
        val okDoc = KakaoPlaceDocument(
            id = "200",
            placeName = "정상장소 식당",
        )
        fakeKakao.responses[SearchKey("부산 정상장소", 1)] = KakaoKeywordSearchResponse(
            documents = listOf(okDoc),
        )

        val result = repository.getPlaceDetails("room1", "부산")

        assertEquals(2, result.size)

        assertEquals("ERR", result[0].place.kakaoPlaceId)
        assertEquals(PlaceDetail.STATUS_NOT_FOUND, result[0].status)
        assertNull(result[0].name)

        assertEquals("200", result[1].place.kakaoPlaceId)
        assertEquals(PlaceDetail.STATUS_FOUND, result[1].status)
        assertEquals("정상장소 식당", result[1].name)
    }

    @Test
    fun testResultOrderPreservedSameAsOriginal() = runBlocking {
        val fakeKakao = FakeKakaoLocalApiClient()
        val places = (1..5).map { i ->
            Place(
                kakaoPlaceId = "id_$i",
                searchKeyword = "장소_$i",
            )
        }
        val loadPlaces: suspend (String) -> List<Place> = { places }
        val repository = PlaceDetailRepository(
            loadPlaces = loadPlaces,
            kakaoLocalApiClient = fakeKakao,
            concurrencyLimit = 5,
        )

        // Add artificial delays in reverse order (id_1 delays 100ms, id_5 delays 0ms)
        fakeKakao.delays["부산 장소_1"] = 100L
        fakeKakao.delays["부산 장소_2"] = 75L
        fakeKakao.delays["부산 장소_3"] = 50L
        fakeKakao.delays["부산 장소_4"] = 25L
        fakeKakao.delays["부산 장소_5"] = 0L

        (1..5).forEach { i ->
            fakeKakao.responses[SearchKey("부산 장소_$i", 1)] = KakaoKeywordSearchResponse(
                documents = listOf(KakaoPlaceDocument(id = "id_$i", placeName = "장소_$i 이름")),
            )
        }

        val result = repository.getPlaceDetails("room1", "부산")

        assertEquals(5, result.size)
        val resultIds = result.map { it.place.kakaoPlaceId }
        val originalIds = places.map { it.kakaoPlaceId }
        assertEquals(originalIds, resultIds)
    }

    @Test
    fun testSearchRegionCenter_found() = runBlocking {
        val fakeKakao = FakeKakaoLocalApiClient()
        val repository = PlaceDetailRepository(kakaoLocalApiClient = fakeKakao)

        val doc = KakaoPlaceDocument(
            id = "region_1",
            placeName = "부산광역시",
            x = "129.0756",
            y = "35.1796",
        )
        fakeKakao.responses[SearchKey("부산", 1)] = KakaoKeywordSearchResponse(
            documents = listOf(doc),
        )

        val coord = repository.searchRegionCenter("부산")
        assertNotNull(coord)
        assertEquals(35.1796, coord!!.lat, 0.0001)
        assertEquals(129.0756, coord.lng, 0.0001)
    }

    @Test
    fun testSearchRegionCenter_notFound() = runBlocking {
        val fakeKakao = FakeKakaoLocalApiClient()
        val repository = PlaceDetailRepository(kakaoLocalApiClient = fakeKakao)

        fakeKakao.responses[SearchKey("없는곳", 1)] = KakaoKeywordSearchResponse(documents = emptyList())

        val coord = repository.searchRegionCenter("없는곳")
        assertNull(coord)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testGetPlacesFailure_PropagatesException() {
        runBlocking {
            val fakeKakao = FakeKakaoLocalApiClient()
            val loadPlaces: suspend (String) -> List<Place> = {
                throw IllegalArgumentException("존재하지 않는 방입니다: $it")
            }
            val repository = PlaceDetailRepository(loadPlaces = loadPlaces, kakaoLocalApiClient = fakeKakao)

            repository.getPlaceDetails("invalid_room", "부산")
        }
    }
}

package com.pintrip.app.data.remote

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

class KakaoLocalApiClientTest {

    private lateinit var mockWebServer: MockWebServer

    @Before
    fun setUp() {
        mockWebServer = MockWebServer()
        mockWebServer.start()
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
    }

    @Test(expected = IllegalArgumentException::class)
    fun testSearchKeyword_blankQueryThrowsException() = runBlocking {
        val client = KakaoLocalApiClient(apiKey = "dummy_key")
        client.searchKeyword(query = "   ")
        Unit
    }

    @Test(expected = IllegalArgumentException::class)
    fun testSearchKeyword_blankApiKeyThrowsException() = runBlocking {
        val client = KakaoLocalApiClient(apiKey = "")
        client.searchKeyword(query = "모모스커피")
        Unit
    }

    @Test
    fun testSearchKeyword_successWithRetrofit() = runBlocking {
        val jsonResponseBody = """
            {
              "meta": {
                "total_count": 1,
                "pageable_count": 1,
                "is_end": true
              },
              "documents": [
                {
                  "id": "18616828",
                  "place_name": "모모스커피 본점",
                  "category_name": "음식점 > 카페 > 커피전문점 > 모모스커피",
                  "category_group_code": "CE7",
                  "category_group_name": "카페",
                  "phone": "051-512-7034",
                  "address_name": "부산 금정구 부곡동 873-3",
                  "road_address_name": "부산 금정구 오시게로 20",
                  "x": "129.086389",
                  "y": "35.221667",
                  "place_url": "http://place.map.kakao.com/18616828",
                  "distance": "120"
                }
              ]
            }
        """.trimIndent()

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(jsonResponseBody)
                .addHeader("Content-Type", "application/json;charset=UTF-8"),
        )

        val client = KakaoLocalApiClient(
            apiKey = "test_api_key",
            baseUrl = mockWebServer.url("/").toString(),
        )

        val response = client.searchKeyword(
            query = "모모스커피",
            categoryGroupCode = "CE7",
            page = 1,
            size = 15,
            x = "129.086389",
            y = "35.221667",
            sort = "accuracy",
        )

        assertEquals(1, response.meta.totalCount)
        assertEquals(1, response.documents.size)
        val doc = response.documents[0]
        assertEquals("18616828", doc.id)
        assertEquals("모모스커피 본점", doc.placeName)

        val recordedRequest = mockWebServer.takeRequest()
        assertEquals("GET", recordedRequest.method)
        assertEquals("KakaoAK test_api_key", recordedRequest.getHeader("Authorization"))
        val requestUrl = recordedRequest.requestUrl
        assertNotNull(requestUrl)
        assertEquals("/v2/local/search/keyword.json", requestUrl?.encodedPath)
        assertEquals("모모스커피", requestUrl?.queryParameter("query"))
        assertEquals("CE7", requestUrl?.queryParameter("category_group_code"))
        assertEquals("1", requestUrl?.queryParameter("page"))
        assertEquals("15", requestUrl?.queryParameter("size"))
        assertEquals("129.086389", requestUrl?.queryParameter("x"))
        assertEquals("35.221667", requestUrl?.queryParameter("y"))
        assertEquals("accuracy", requestUrl?.queryParameter("sort"))
    }

    @Test
    fun testSearchKeyword_httpErrorThrowsIOException() = runBlocking {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setBody("""{"errorType":"AccessDeniedError","message":"invalid kakao app key"}"""),
        )

        val client = KakaoLocalApiClient(
            apiKey = "invalid_key",
            baseUrl = mockWebServer.url("/").toString(),
        )

        try {
            client.searchKeyword(query = "테스트")
            assertTrue("IOException should have been thrown", false)
        } catch (e: IOException) {
            assertTrue(e.message?.contains("401") == true)
            assertTrue(e.message?.contains("invalid kakao app key") == true)
        }
    }
}

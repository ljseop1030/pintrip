package com.pintrip.app.data.remote

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pintrip.app.data.model.parseKakaoKeywordSearchResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KakaoLocalApiClientTest {

    @Test
    fun testParseKakaoKeywordSearchResponse_validJson() {
        val sampleJson = """
            {
              "meta": {
                "total_count": 2,
                "pageable_count": 2,
                "is_end": true,
                "same_name": {
                  "region": ["부산", "금정구"],
                  "keyword": "모모스커피",
                  "selected_region": "부산 금정구"
                }
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
                },
                {
                  "id": "99999999",
                  "place_name": "해운대 식당",
                  "category_name": "음식점 > 한식",
                  "category_group_code": "FD6",
                  "category_group_name": "음식점",
                  "phone": "051-740-0000",
                  "address_name": "부산 해운대구 우동 123",
                  "road_address_name": "",
                  "x": "129.160000",
                  "y": "35.160000",
                  "place_url": "http://place.map.kakao.com/99999999",
                  "distance": ""
                }
              ]
            }
        """.trimIndent()

        val response = parseKakaoKeywordSearchResponse(sampleJson)

        assertEquals(2, response.meta.totalCount)
        assertEquals(2, response.meta.pageableCount)
        assertTrue(response.meta.isEnd)
        assertNotNull(response.meta.sameName)
        assertEquals(listOf("부산", "금정구"), response.meta.sameName?.region)
        assertEquals("모모스커피", response.meta.sameName?.keyword)
        assertEquals("부산 금정구", response.meta.sameName?.selectedRegion)

        assertEquals(2, response.documents.size)

        // Item 1 verification
        val doc1 = response.documents[0]
        assertEquals("18616828", doc1.id)
        assertEquals("모모스커피 본점", doc1.placeName)
        assertEquals("CE7", doc1.categoryGroupCode)
        assertEquals("cafe", doc1.mappedCategory)
        assertEquals("부산 금정구 오시게로 20", doc1.displayAddress)
        assertEquals(35.221667, doc1.lat!!, 0.00001)
        assertEquals(129.086389, doc1.lng!!, 0.00001)
        assertEquals("http://place.map.kakao.com/18616828", doc1.placeUrl)

        // Item 2 verification (road_address_name is empty, fallback to address_name)
        val doc2 = response.documents[1]
        assertEquals("99999999", doc2.id)
        assertEquals("FD6", doc2.categoryGroupCode)
        assertEquals("dining", doc2.mappedCategory)
        assertEquals("부산 해운대구 우동 123", doc2.displayAddress)
    }

    @Test
    fun testParseKakaoKeywordSearchResponse_emptyJson() {
        val emptyJson = """
            {
              "meta": {
                "total_count": 0,
                "pageable_count": 0,
                "is_end": true
              },
              "documents": []
            }
        """.trimIndent()

        val response = parseKakaoKeywordSearchResponse(emptyJson)

        assertEquals(0, response.meta.totalCount)
        assertTrue(response.meta.isEnd)
        assertNull(response.meta.sameName)
        assertTrue(response.documents.isEmpty())
    }

    @Test
    fun testCategoryMapping() {
        val jsonPattern = { code: String ->
            """
            {
              "meta": { "total_count": 1, "pageable_count": 1, "is_end": true },
              "documents": [
                {
                  "id": "1",
                  "place_name": "Test Place",
                  "category_group_code": "$code",
                  "address_name": "Addr",
                  "road_address_name": "",
                  "x": "127.0",
                  "y": "37.0",
                  "place_url": "http://place.map.kakao.com/1"
                }
              ]
            }
            """.trimIndent()
        }

        assertEquals("dining", parseKakaoKeywordSearchResponse(jsonPattern("FD6")).documents[0].mappedCategory)
        assertEquals("cafe", parseKakaoKeywordSearchResponse(jsonPattern("CE7")).documents[0].mappedCategory)
        assertEquals("sights", parseKakaoKeywordSearchResponse(jsonPattern("AT4")).documents[0].mappedCategory)
        assertEquals("sights", parseKakaoKeywordSearchResponse(jsonPattern("CT1")).documents[0].mappedCategory)
        assertEquals("stay", parseKakaoKeywordSearchResponse(jsonPattern("AD5")).documents[0].mappedCategory)
        assertEquals("etc", parseKakaoKeywordSearchResponse(jsonPattern("SOMETHING_ELSE")).documents[0].mappedCategory)
    }
}

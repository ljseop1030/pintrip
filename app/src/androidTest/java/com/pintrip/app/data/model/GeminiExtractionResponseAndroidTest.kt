package com.pintrip.app.data.model

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GeminiExtractionResponseAndroidTest {

    @Test
    fun testParseGeminiExtractionResponse_fullValidJson() {
        val json = """
            {
              "places": [
                {
                  "name": "모모스커피",
                  "region": "부산 금정구",
                  "categoryHint": "cafe",
                  "rawText": "3. Momos Coffee",
                  "outsideRoomRegion": false
                },
                {
                  "name": "오설록",
                  "region": "제주",
                  "categoryHint": "stay",
                  "rawText": "Osulloc Tea House",
                  "outsideRoomRegion": true
                }
              ],
              "regionHint": "부산",
              "hasMore": false
            }
        """.trimIndent()

        val result = parseGeminiExtractionResponse(json)

        assertEquals("부산", result.regionHint)
        assertFalse(result.hasMore)
        assertEquals(2, result.places.size)

        val place1 = result.places[0]
        assertEquals("모모스커피", place1.name)
        assertEquals("부산 금정구", place1.region)
        assertEquals("cafe", place1.categoryHint)
        assertEquals("3. Momos Coffee", place1.rawText)
        assertFalse(place1.outsideRoomRegion)

        val place2 = result.places[1]
        assertEquals("오설록", place2.name)
        assertEquals("제주", place2.region)
        assertEquals("stay", place2.categoryHint)
        assertEquals("Osulloc Tea House", place2.rawText)
        assertTrue(place2.outsideRoomRegion)
    }

    @Test
    fun testParseGeminiExtractionResponse_optionalNullFields() {
        val json = """
            {
              "places": [
                {
                  "name": "이재모피자 본점",
                  "region": null,
                  "categoryHint": null,
                  "rawText": "이재모피자 본점",
                  "outsideRoomRegion": false
                }
              ],
              "regionHint": null,
              "hasMore": true
            }
        """.trimIndent()

        val result = parseGeminiExtractionResponse(json)

        assertNull(result.regionHint)
        assertTrue(result.hasMore)
        assertEquals(1, result.places.size)

        val place = result.places[0]
        assertEquals("이재모피자 본점", place.name)
        assertNull(place.region)
        assertNull(place.categoryHint)
        assertEquals("이재모피자 본점", place.rawText)
        assertFalse(place.outsideRoomRegion)
    }

    @Test
    fun testParseGeminiExtractionResponse_markdownWrappedJson() {
        val json = """
            ```json
            {
              "places": [],
              "regionHint": null,
              "hasMore": false
            }
            ```
        """.trimIndent()

        val result = parseGeminiExtractionResponse(json)

        assertNull(result.regionHint)
        assertFalse(result.hasMore)
        assertTrue(result.places.isEmpty())
    }

    @Test
    fun testGeminiExtractionResponseSchema_initialized() {
        assertNotNull(GEMINI_EXTRACTION_RESPONSE_SCHEMA)
        assertEquals("OBJECT", GEMINI_EXTRACTION_RESPONSE_SCHEMA.type)
    }
}

package com.pintrip.app.extract

import com.pintrip.app.data.model.GeminiExtractionResponse
import com.pintrip.app.data.model.GeminiPlace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtractUtilsTest {

    @Test
    fun testNormalizePlaceName() {
        assertEquals("모모스커피", normalizePlaceName(" 모모스  커피 "))
        assertEquals("momoscoffee", normalizePlaceName("Momos Coffee"))
        assertEquals("이재모피자본점", normalizePlaceName("이재모 피자 본점"))
    }

    @Test
    fun testMergeGeminiExtractionResponses_deduplicatesNameIgnoringSpacesAndCase() {
        val res1 = GeminiExtractionResponse(
            places = listOf(
                GeminiPlace(name = "모모스 커피", rawText = "1. 모모스 커피"),
                GeminiPlace(name = "이재모 피자", rawText = "2. 이재모 피자"),
            ),
            regionHint = "부산",
        )
        val res2 = GeminiExtractionResponse(
            places = listOf(
                GeminiPlace(name = "모모스커피", rawText = "Momos Coffee"), // duplicate of 모모스 커피
                GeminiPlace(name = "해운대암소갈비", rawText = "해운대암소갈비"),
            ),
            regionHint = "부산 해운대",
        )

        val merged = mergeGeminiExtractionResponses(listOf(res1, res2))

        assertEquals("부산", merged.regionHint)
        assertEquals(3, merged.places.size)
        assertEquals("모모스 커피", merged.places[0].name)
        assertEquals("이재모 피자", merged.places[1].name)
        assertEquals("해운대암소갈비", merged.places[2].name)
    }

    @Test
    fun testMergeGeminiExtractionResponses_mergesAllPlacesWithoutCapping() {
        val places = (1..7).map { idx ->
            GeminiPlace(name = "장소 $idx", rawText = "장소 $idx")
        }
        val res = GeminiExtractionResponse(places = places)

        val merged = mergeGeminiExtractionResponses(listOf(res))

        assertEquals(7, merged.places.size)
        assertEquals("장소 1", merged.places[0].name)
        assertEquals("장소 7", merged.places[6].name)
    }

    @Test
    fun testIsCandidateAddressOutsideRoomRegion_insideRegion_Busan() {
        val address = "부산 금정구 오시게로 20"
        val roomRegion = "부산"

        val result = isCandidateAddressOutsideRoomRegion(
            candidateAddress = address,
            roomRegion = roomRegion,
            geminiOutsideRoomRegion = false,
        )

        assertFalse(result) // 부산 주소이므로 false (지역 내)
    }

    @Test
    fun testIsCandidateAddressOutsideRoomRegion_insideRegion_Gangneung() {
        val address = "강원특별자치도 강릉시 경포로 365"
        val roomRegion = "강릉"

        val result = isCandidateAddressOutsideRoomRegion(
            candidateAddress = address,
            roomRegion = roomRegion,
            geminiOutsideRoomRegion = false,
        )

        assertFalse(result) // "강원특별자치도 강릉시 ..." 주소에 방 지역 "강릉"이 포함되어 있으므로 false (지역 내)
    }

    @Test
    fun testIsCandidateAddressOutsideRoomRegion_outsideRegion() {
        val address = "서울 노원구 공릉로 232"
        val roomRegion = "부산"

        val result = isCandidateAddressOutsideRoomRegion(
            candidateAddress = address,
            roomRegion = roomRegion,
            geminiOutsideRoomRegion = false,
        )

        assertTrue(result) // 부산 방에 서울 주소이므로 true (지역 밖)
    }

    @Test
    fun testIsCandidateAddressOutsideRoomRegion_geminiOutsideFlagTrue() {
        val address = "부산 해운대구 우동"
        val roomRegion = "부산"

        val result = isCandidateAddressOutsideRoomRegion(
            candidateAddress = address,
            roomRegion = roomRegion,
            geminiOutsideRoomRegion = true,
        )

        assertTrue(result) // geminiOutsideRoomRegion이 true이므로 주소와 무관하게 true
    }

    @Test
    fun testIsCandidateAddressOutsideRoomRegion_nullOrBlankHandling() {
        assertFalse(
            isCandidateAddressOutsideRoomRegion(
                candidateAddress = null,
                roomRegion = "부산",
            ),
        )
        assertFalse(
            isCandidateAddressOutsideRoomRegion(
                candidateAddress = "부산 해운대구 우동",
                roomRegion = null,
            ),
        )
    }

    @Test
    fun testCombineShareAndPageText_bothPresent_combinesBoth() {
        val shareText = "[네이버 지도] 모모스 커피 부산 금정구 오시게로 20 https://naver.me/abc"
        val pageText = "모모스 커피 블로그 리뷰 포스팅 본문입니다."

        val combined = combineShareAndPageText(shareText, pageText)

        assertEquals("[네이버 지도] 모모스 커피 부산 금정구 오시게로 20 https://naver.me/abc\n\n모모스 커피 블로그 리뷰 포스팅 본문입니다.", combined)
    }

    @Test
    fun testCombineShareAndPageText_identical_noDuplication() {
        val shareText = "https://naver.me/abc 모모스 커피"
        val pageText = "https://naver.me/abc 모모스 커피"

        val combined = combineShareAndPageText(shareText, pageText)

        assertEquals("https://naver.me/abc 모모스 커피", combined)
    }

    @Test
    fun testCombineShareAndPageText_truncation_preservesShareTextIntact() {
        val shareText = "Share Text Info" // length = 15
        val longPageText = "A".repeat(100) // length = 100
        val maxLength = 30 // separator "\n\n" is length 2, remaining page budget = 30 - 15 - 2 = 13

        val combined = combineShareAndPageText(shareText, longPageText, maxLength = maxLength)

        assertEquals(30, combined.length)
        assertTrue(combined.startsWith("Share Text Info\n\n"))
        assertEquals("A".repeat(13), combined.substring(17))
    }

    @Test
    fun testCombineShareAndPageText_blankShareText_returnsPageText() {
        val combined = combineShareAndPageText("   ", "본문 텍스트", maxLength = 50)
        assertEquals("본문 텍스트", combined)
    }

    @Test
    fun testCombineShareAndPageText_blankPageText_returnsShareText() {
        val combined = combineShareAndPageText("공유 텍스트", "   ", maxLength = 50)
        assertEquals("공유 텍스트", combined)
    }
}


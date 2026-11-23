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
    fun testNormalizeRegionName() {
        assertEquals("부산", normalizeRegionName("부산광역시"))
        assertEquals("제주", normalizeRegionName("제주특별자치도"))
        assertEquals("전남", normalizeRegionName("전라남도"))
        assertEquals("경북", normalizeRegionName("경상북도"))
        assertEquals("충남", normalizeRegionName("충청남도"))
        assertEquals("서울", normalizeRegionName("서울특별시"))
        assertEquals("강릉", normalizeRegionName("강릉시"))
        assertEquals("세종", normalizeRegionName("세종특별자치시"))
        assertEquals("경기", normalizeRegionName("경기도"))
        assertEquals("강원", normalizeRegionName("강원도"))
    }

    @Test
    fun testNormalizeRegionName_preservesShortNames() {
        assertEquals("영도", normalizeRegionName("영도"))
        assertEquals("영도", normalizeRegionName("영도구"))
        assertEquals("진도", normalizeRegionName("진도"))
        assertEquals("진도", normalizeRegionName("진도군"))
        assertEquals("완도", normalizeRegionName("완도"))
        assertEquals("완도", normalizeRegionName("완도군"))
    }

    @Test
    fun testIsCandidateAddressOutsideRoomRegion_counterExamplesFromCodeReview() {
        // 1. "영도" 방 vs "서울 영등포구" -> 영등포구에 "영"이 들어가더라도 영도 방 밖으로 판정되어야 함
        assertTrue(
            isCandidateAddressOutsideRoomRegion(
                candidateAddress = "서울 영등포구 여의도동 1",
                roomRegion = "영도",
            ),
        )
        assertFalse(
            isCandidateAddressOutsideRoomRegion(
                candidateAddress = "부산 영도구 태종로 1",
                roomRegion = "영도",
            ),
        )

        // 2. "세종" 방 vs "서울 중구 세종대로" -> 도로명에 "세종"이 있더라도 서울 주소이므로 방 밖으로 판정되어야 함
        assertTrue(
            isCandidateAddressOutsideRoomRegion(
                candidateAddress = "서울 중구 세종대로 110",
                roomRegion = "세종특별자치시",
            ),
        )
        assertFalse(
            isCandidateAddressOutsideRoomRegion(
                candidateAddress = "세종 도움8로 11",
                roomRegion = "세종",
            ),
        )

        // 3. "광주광역시" 방 vs "경기 광주시" -> 경기도 광주는 광주광역시 방 밖으로 판정되어야 함
        assertTrue(
            isCandidateAddressOutsideRoomRegion(
                candidateAddress = "경기 광주시 경안로 1",
                roomRegion = "광주광역시",
            ),
        )
        assertFalse(
            isCandidateAddressOutsideRoomRegion(
                candidateAddress = "광주 동구 서석로 56",
                roomRegion = "광주광역시",
            ),
        )
    }

    @Test
    fun testIsCandidateAddressOutsideRoomRegion_normalizedMatches() {
        // 1. 부산광역시 ↔ 부산
        assertFalse(
            isCandidateAddressOutsideRoomRegion(
                candidateAddress = "부산 해운대구 달맞이길 62",
                roomRegion = "부산광역시",
            ),
        )

        // 2. 제주특별자치도 ↔ 제주
        assertFalse(
            isCandidateAddressOutsideRoomRegion(
                candidateAddress = "제주 서귀포시 안덕면 신화역사로",
                roomRegion = "제주특별자치도",
            ),
        )

        // 3. 전라남도 ↔ 전남
        assertFalse(
            isCandidateAddressOutsideRoomRegion(
                candidateAddress = "전남 여수시 시청로 1",
                roomRegion = "전라남도",
            ),
        )

        // 4. 부산 ↔ 서울 (다른 지역)
        assertTrue(
            isCandidateAddressOutsideRoomRegion(
                candidateAddress = "서울 종로구 사직로 161",
                roomRegion = "부산",
            ),
        )
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


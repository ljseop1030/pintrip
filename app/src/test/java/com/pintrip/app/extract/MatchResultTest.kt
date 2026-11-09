package com.pintrip.app.extract

import com.pintrip.app.data.model.GeminiPlace
import com.pintrip.app.data.model.KakaoPlaceDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchResultTest {

    @Test
    fun testMatchCandidateFromKakaoDocument() {
        val doc = KakaoPlaceDocument(
            id = "12345",
            placeName = "모모스커피 본점",
            categoryGroupCode = "CE7",
            phone = "051-512-7034",
            addressName = "부산 금정구 부곡동 873-3",
            roadAddressName = "부산 금정구 오시게로 20",
            x = "129.086389",
            y = "35.221667",
            placeUrl = "https://place.map.kakao.com/12345",
        )

        val candidate = MatchCandidate.fromKakaoDocument(doc)

        assertEquals("12345", candidate.kakaoPlaceId)
        assertEquals("https://place.map.kakao.com/12345", candidate.kakaoPlaceUrl)
        assertEquals("모모스커피 본점", candidate.name)
        assertEquals("부산 금정구 오시게로 20", candidate.address)
        assertEquals(35.221667, candidate.lat!!, 0.00001)
        assertEquals(129.086389, candidate.lng!!, 0.00001)
        assertEquals("cafe", candidate.category)
        assertEquals("051-512-7034", candidate.phone)
    }

    @Test
    fun testCreate_notFoundWhenCandidatesEmpty() {
        val extracted = GeminiPlace(name = "모모스커피", rawText = "모모스커피")
        val result = MatchResult.create(
            localId = "img0_p0",
            extracted = extracted,
            candidates = emptyList(),
        )

        assertEquals(MatchResult.STATUS_NOT_FOUND, result.status)
        assertNull(result.selectedPlaceId)
        assertFalse(result.checked)
        assertFalse(result.isConfirmable)
    }

    @Test
    fun testCreate_matchedWhenSingleCandidate() {
        val extracted = GeminiPlace(name = "모모스커피", rawText = "모모스커피")
        val candidate = MatchCandidate(kakaoPlaceId = "12345", name = "모모스커피 본점")

        val result = MatchResult.create(
            localId = "img0_p0",
            extracted = extracted,
            candidates = listOf(candidate),
        )

        assertEquals(MatchResult.STATUS_MATCHED, result.status)
        assertEquals("12345", result.selectedPlaceId)
        assertTrue(result.checked)
        assertTrue(result.isConfirmable)
        assertEquals(candidate, result.selectedCandidate)
    }

    @Test
    fun testCreate_matchedWhenExactNameMatchInMultipleCandidates() {
        val extracted = GeminiPlace(name = "모모스 커피", rawText = "모모스 커피")
        val cand1 = MatchCandidate(kakaoPlaceId = "101", name = "모모스커피 센텀점")
        val cand2 = MatchCandidate(kakaoPlaceId = "102", name = "모모스커피") // exact match (ignore space)
        val cand3 = MatchCandidate(kakaoPlaceId = "103", name = "모모스커피 마린시티점")

        val result = MatchResult.create(
            localId = "img0_p0",
            extracted = extracted,
            candidates = listOf(cand1, cand2, cand3),
        )

        assertEquals(MatchResult.STATUS_MATCHED, result.status)
        assertEquals("102", result.selectedPlaceId)
        assertTrue(result.checked)
        assertEquals(cand2, result.selectedCandidate)
    }

    @Test
    fun testCreate_checkWhenMultipleCandidatesAndNoExactMatch() {
        val extracted = GeminiPlace(name = "모모스커피", rawText = "모모스커피")
        val cand1 = MatchCandidate(kakaoPlaceId = "101", name = "모모스커피 본점")
        val cand2 = MatchCandidate(kakaoPlaceId = "102", name = "모모스커피 센텀점")

        val result = MatchResult.create(
            localId = "img0_p0",
            extracted = extracted,
            candidates = listOf(cand1, cand2),
        )

        assertEquals(MatchResult.STATUS_CHECK, result.status)
        assertNull(result.selectedPlaceId)
        assertTrue(result.checked)
        assertFalse(result.isConfirmable) // selectedPlaceId not set yet
    }

    @Test
    fun testCreate_regionMismatch() {
        val extracted = GeminiPlace(name = "오설록", outsideRoomRegion = true)
        val cand = MatchCandidate(kakaoPlaceId = "201", name = "오설록 티하우스")

        val result = MatchResult.create(
            localId = "img0_p0",
            extracted = extracted,
            candidates = listOf(cand),
            regionMismatch = false,
        )

        assertTrue(result.regionMismatch)
        assertFalse(result.checked) // regionMismatch defaults checked to false
    }

    @Test
    fun testCreate_exactMatchBeyondTopFiveIsKept() {
        val extracted = GeminiPlace(name = "모모스커피")
        val others = (1..7).map { MatchCandidate(kakaoPlaceId = "$it", name = "모모스커피 지점$it") }
        val exact = MatchCandidate(kakaoPlaceId = "99", name = "모모스커피")

        val result = MatchResult.create(
            localId = "img0_p0",
            extracted = extracted,
            candidates = others + exact,
        )

        assertEquals(MatchResult.STATUS_MATCHED, result.status)
        assertEquals(5, result.candidates.size)
        assertEquals(exact, result.selectedCandidate)
    }
}

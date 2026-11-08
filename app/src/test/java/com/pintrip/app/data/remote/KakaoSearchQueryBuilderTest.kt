package com.pintrip.app.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KakaoSearchQueryBuilderTest {

    @Test
    fun testBuildKakaoSearchQuery_insideRoomRegion_withPlaceRegion() {
        val result = buildKakaoSearchQuery(
            name = "모모스커피",
            roomRegion = "부산",
            region = "금정구",
            categoryHint = "cafe",
            outsideRoomRegion = false,
        )

        assertEquals("부산 금정구 모모스커피", result.query)
        assertEquals("CE7", result.categoryGroupCode)
    }

    @Test
    fun testBuildKakaoSearchQuery_insideRoomRegion_withoutPlaceRegion() {
        val result = buildKakaoSearchQuery(
            name = "모모스커피",
            roomRegion = "부산",
            region = null,
            categoryHint = "dining",
            outsideRoomRegion = false,
        )

        assertEquals("부산 모모스커피", result.query)
        assertEquals("FD6", result.categoryGroupCode)
    }

    @Test
    fun testBuildKakaoSearchQuery_placeRegionAlreadyContainsRoomRegion() {
        val result = buildKakaoSearchQuery(
            name = "모모스커피",
            roomRegion = "부산",
            region = "부산 금정구",
            categoryHint = "cafe",
            outsideRoomRegion = false,
        )
        assertEquals("부산 금정구 모모스커피", result.query)
    }

    @Test
    fun testBuildKakaoSearchQuery_outsideRoomRegion_withPlaceRegion() {
        val result = buildKakaoSearchQuery(
            name = "오설록",
            roomRegion = "부산",
            region = "제주",
            categoryHint = "stay",
            outsideRoomRegion = true,
            regionHint = "제주도",
        )

        assertEquals("제주 오설록", result.query)
        assertEquals("AD5", result.categoryGroupCode)
    }

    @Test
    fun testBuildKakaoSearchQuery_outsideRoomRegion_withRegionHintFallback() {
        val result = buildKakaoSearchQuery(
            name = "오설록",
            roomRegion = "부산",
            region = null,
            categoryHint = "etc",
            outsideRoomRegion = true,
            regionHint = "제주",
        )

        assertEquals("제주 오설록", result.query)
        assertNull(result.categoryGroupCode)
    }

    @Test
    fun testMapCategoryHintToGroupCode() {
        assertEquals("FD6", mapCategoryHintToGroupCode("dining"))
        assertEquals("CE7", mapCategoryHintToGroupCode("cafe"))
        assertEquals("AT4", mapCategoryHintToGroupCode("sights"))
        assertEquals("AD5", mapCategoryHintToGroupCode("stay"))
        assertNull(mapCategoryHintToGroupCode("etc"))
        assertNull(mapCategoryHintToGroupCode(null))
        assertNull(mapCategoryHintToGroupCode(""))
    }
}

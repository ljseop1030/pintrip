package com.pintrip.app.travel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class NearbyPlaceFinderTest {

    @Test
    fun findNewNearby_includesCandidatesWithinRadius() {
        val centerLat = 35.1587
        val centerLng = 129.1604
        val insideCandidate = NearbyCandidate("1", "해운대", 35.1590, 129.1604)
        val candidates = listOf(insideCandidate)
        val alreadyNotified = emptySet<String>()

        val result = NearbyPlaceFinder.findNewNearby(centerLat, centerLng, candidates, alreadyNotified, 300.0)

        assertEquals(1, result.size)
        assertEquals("1", result[0].first.kakaoPlaceId)
    }

    @Test
    fun findNewNearby_excludesCandidatesOutsideRadius() {
        val centerLat = 35.1587
        val centerLng = 129.1604
        val outsideCandidate = NearbyCandidate("2", "광안리", 35.1532, 129.1186)
        val candidates = listOf(outsideCandidate)
        val alreadyNotified = emptySet<String>()

        val result = NearbyPlaceFinder.findNewNearby(centerLat, centerLng, candidates, alreadyNotified, 300.0)

        assertTrue(result.isEmpty())
    }

    @Test
    fun findNewNearby_excludesAlreadyNotifiedPlaces() {
        val centerLat = 35.1587
        val centerLng = 129.1604
        val candidate = NearbyCandidate("1", "해운대", 35.1590, 129.1604)
        val candidates = listOf(candidate)
        val alreadyNotified = setOf("1")

        val result = NearbyPlaceFinder.findNewNearby(centerLat, centerLng, candidates, alreadyNotified, 300.0)

        assertTrue(result.isEmpty())
    }

    @Test
    fun findNewNearby_sortsByNearestFirst() {
        val centerLat = 35.1587
        val centerLng = 129.1604
        val far = NearbyCandidate("far", "멀리", 35.1605, 129.1604)
        val near = NearbyCandidate("near", "가까이", 35.1589, 129.1604)
        val mid = NearbyCandidate("mid", "중간", 35.1596, 129.1604)

        val candidates = listOf(far, near, mid)
        val alreadyNotified = emptySet<String>()

        val result = NearbyPlaceFinder.findNewNearby(centerLat, centerLng, candidates, alreadyNotified, 300.0)

        assertEquals(3, result.size)
        assertEquals("near", result[0].first.kakaoPlaceId)
        assertEquals("mid", result[1].first.kakaoPlaceId)
        assertEquals("far", result[2].first.kakaoPlaceId)
        assertTrue(result[0].second <= result[1].second)
        assertTrue(result[1].second <= result[2].second)
    }

    @Test
    fun distanceAccuracy_haeundaeToGwangalli() {
        val haeundaeLat = 35.1587
        val haeundaeLng = 129.1604
        val gwangalliLat = 35.1532
        val gwangalliLng = 129.1186

        val expectedMeters = 3800.0

        val calculatedMeters = NearbyPlaceFinder.calculateDistanceMeters(
            haeundaeLat,
            haeundaeLng,
            gwangalliLat,
            gwangalliLng,
        )

        val errorRatio = abs(calculatedMeters - expectedMeters) / expectedMeters
        assertTrue(
            "Expected distance ~3800m within 5% error, but was $calculatedMeters (error ratio: $errorRatio)",
            errorRatio <= 0.05
        )
    }
}

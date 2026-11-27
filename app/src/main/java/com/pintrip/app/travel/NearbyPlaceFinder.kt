package com.pintrip.app.travel

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

data class NearbyCandidate(
    val kakaoPlaceId: String,
    val name: String,
    val lat: Double,
    val lng: Double,
)

object NearbyPlaceFinder {

    /**
     * Haversine 공식을 이용해 두 위경도 좌표 간 거리(미터)를 계산합니다.
     * 안드로이드 Location API를 사용하지 않는 순수 Kotlin 함수입니다.
     */
    fun calculateDistanceMeters(
        lat1: Double,
        lng1: Double,
        lat2: Double,
        lng2: Double,
    ): Double {
        val earthRadiusMeters = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = (sin(dLat / 2.0) * sin(dLat / 2.0)) +
                (cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2.0) * sin(dLng / 2.0))
        val c = 2.0 * asin(sqrt(a.coerceIn(0.0, 1.0)))
        return earthRadiusMeters * c
    }

    /**
     * 현재 위치(lat, lng) 기준으로 반경(radiusMeters) 이내에 존재하고,
     * 아직 알리지 않은(alreadyNotifiedIds에 포함되지 않은) 장소들을 가까운 순으로 정렬하여 반환합니다.
     *
     * @return List<Pair<NearbyCandidate, Int>> (후보 장소, 거리 m)
     */
    fun findNewNearby(
        lat: Double,
        lng: Double,
        candidates: List<NearbyCandidate>,
        alreadyNotifiedIds: Set<String>,
        radiusMeters: Double,
    ): List<Pair<NearbyCandidate, Int>> {
        val results = mutableListOf<Pair<NearbyCandidate, Int>>()
        for (candidate in candidates) {
            if (candidate.kakaoPlaceId in alreadyNotifiedIds) continue

            val distanceMeters = calculateDistanceMeters(lat, lng, candidate.lat, candidate.lng)
            if (distanceMeters <= radiusMeters) {
                results.add(candidate to distanceMeters.roundToInt())
            }
        }
        return results.sortedBy { it.second }
    }
}

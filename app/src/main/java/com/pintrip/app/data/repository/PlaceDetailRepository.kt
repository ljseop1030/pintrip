package com.pintrip.app.data.repository

import android.util.Log
import com.pintrip.app.data.model.Place
import com.pintrip.app.data.model.PlaceDetail
import com.pintrip.app.data.remote.KakaoLocalApiClient
import com.pintrip.app.data.remote.buildKakaoSearchQuery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class MapCoordinate(
    val lat: Double,
    val lng: Double,
)

/**
 * 저장된 장소를 카카오 Local API에서 재조회하여 [PlaceDetail] 리스트로 반환하는 Repository.
 *
 * docs/data-contract.md §1, §4, §9 규격을 엄격히 준수합니다:
 * 1. [loadPlaces] 함수로 장소 목록을 조회한 후 장소마다 카카오 재조회 수행.
 * 2. 카카오 응답 데이터(이름, 주소, 좌표, 카테고리, 전화번호)는 DB나 파일에 저장하지 않고 메모리 반환값으로만 넘김.
 * 3. [Semaphore]를 사용하여 카카오 API 병렬 재조회 동시 요청 수를 제한(기본 5개)하고 원래 장소 목록 순서 유지.
 * 4. 장소 하나의 카카오 호출이 실패하더라도 전체를 실패시키지 않고 notFound로 처리하며, [CancellationException]은 다시 던짐.
 * 5. [loadPlaces] 자체가 실패하면 예외를 상위로 전파.
 *
 * @property loadPlaces roomId에 해당하는 장소 목록을 가져오는 suspend 함수 (기본값: [PlaceRepository.getPlaces])
 * @property kakaoLocalApiClient 카카오 Local API 통신 클라이언트
 * @property concurrencyLimit 동시 요청 수 제한 (기본값: 5)
 */
open class PlaceDetailRepository(
    private val loadPlaces: suspend (roomId: String) -> List<Place> = { PlaceRepository().getPlaces(it) },
    private val kakaoLocalApiClient: KakaoLocalApiClient = KakaoLocalApiClient(),
    private val concurrencyLimit: Int = DEFAULT_CONCURRENCY_LIMIT,
) {

    /**
     * 지정한 방(roomId)의 장소 목록을 가져와 각 장소를 카카오 Local API에서 재조회하고 [PlaceDetail] 목록으로 반환합니다.
     *
     * @param roomId 대상 방 ID
     * @param roomRegion 방 대표 지역명 (예: "부산")
     * @return 재조회된 [PlaceDetail] 목록 (원래 장소 순서 유지)
     * @throws Exception [loadPlaces] 실패 시 예외 발생
     */
    open suspend fun getPlaceDetails(roomId: String, roomRegion: String): List<PlaceDetail> {
        val places = loadPlaces(roomId)
        if (places.isEmpty()) return emptyList()

        return coroutineScope {
            val semaphore = Semaphore(concurrencyLimit)

            places.map { place ->
                async {
                    semaphore.withPermit {
                        reFetchSinglePlace(place, roomRegion)
                    }
                }
            }.awaitAll()
        }
    }

    /**
     * 방 지역명으로 카카오 키워드 검색을 수행하여 첫 번째 결과의 좌표(위도, 경도)를 반환합니다.
     * (docs/data-contract.md §4 Map camera: found 장소가 0개일 때 사용)
     *
     * @param region 검색할 지역명 (예: "부산")
     * @return [MapCoordinate] (위도, 경도) 또는 결과가 없으면 null
     */
    open suspend fun searchRegionCenter(region: String): MapCoordinate? {
        val trimmed = region.trim()
        if (trimmed.isBlank()) return null
        return try {
            val response = kakaoLocalApiClient.searchKeyword(
                query = trimmed,
                categoryGroupCode = null,
                page = 1,
                size = 1,
            )
            val doc = response.documents.firstOrNull()
            if (doc != null) {
                val lat = doc.y.toDoubleOrNull()
                val lng = doc.x.toDoubleOrNull()
                if (lat != null && lng != null) {
                    MapCoordinate(lat, lng)
                } else {
                    null
                }
            } else {
                null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to search region center for '$trimmed': ${e.message}", e)
            null
        }
    }

    private suspend fun reFetchSinglePlace(
        place: Place,
        roomRegion: String,
    ): PlaceDetail {
        return try {
            // 1단계: buildKakaoSearchQuery(name = searchKeyword, roomRegion = roomRegion, region = searchRegion), page=1, size=15
            val searchQuery1 = buildKakaoSearchQuery(
                name = place.searchKeyword,
                roomRegion = roomRegion,
                region = place.searchRegion,
            )
            val resp1 = kakaoLocalApiClient.searchKeyword(
                query = searchQuery1.query,
                categoryGroupCode = null,
                page = 1,
                size = KAKAO_SEARCH_SIZE,
            )
            val matchedDoc1 = resp1.documents.firstOrNull { it.id == place.kakaoPlaceId }
            if (matchedDoc1 != null) {
                return PlaceDetail.fromKakaoDocument(place, matchedDoc1)
            }

            // 2단계: 1단계 실패 & searchRegion이 비어있지 않으면 "{searchRegion} {searchKeyword}", page=1, size=15
            val searchRegion = place.searchRegion?.trim().orEmpty()
            if (searchRegion.isNotBlank()) {
                val query2 = "$searchRegion ${place.searchKeyword.trim()}"
                val resp2 = kakaoLocalApiClient.searchKeyword(
                    query = query2,
                    categoryGroupCode = null,
                    page = 1,
                    size = KAKAO_SEARCH_SIZE,
                )
                val matchedDoc2 = resp2.documents.firstOrNull { it.id == place.kakaoPlaceId }
                if (matchedDoc2 != null) {
                    return PlaceDetail.fromKakaoDocument(place, matchedDoc2)
                }
            }

            // 3단계: 2단계에서도 실패 시 1단계 검색어로 page=2, size=15
            val resp3 = kakaoLocalApiClient.searchKeyword(
                query = searchQuery1.query,
                categoryGroupCode = null,
                page = 2,
                size = KAKAO_SEARCH_SIZE,
            )
            val matchedDoc3 = resp3.documents.firstOrNull { it.id == place.kakaoPlaceId }
            if (matchedDoc3 != null) {
                return PlaceDetail.fromKakaoDocument(place, matchedDoc3)
            }

            // 4단계: 끝까지 못 찾음 -> notFound
            PlaceDetail.createNotFound(place)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to re-fetch Kakao place detail for place '${place.kakaoPlaceId}': ${e.message}", e)
            PlaceDetail.createNotFound(place)
        }
    }

    companion object {
        private const val TAG = "PlaceDetailRepository"
        const val DEFAULT_CONCURRENCY_LIMIT = 5
        private const val KAKAO_SEARCH_SIZE = 15
    }
}

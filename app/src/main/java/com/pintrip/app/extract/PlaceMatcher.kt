package com.pintrip.app.extract

import android.util.Log
import com.pintrip.app.data.model.GeminiExtractionResponse
import com.pintrip.app.data.model.GeminiPlace
import com.pintrip.app.data.remote.KakaoLocalApiClient
import com.pintrip.app.data.remote.buildKakaoSearchQuery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Gemini 추출 결과와 방 지역을 기반으로 카카오 장소 검색어를 생성하고,
 * 카카오 Local API 검색 및 결과 판정을 거쳐 [MatchResult] 리스트를 반환하는 매칭 클래스.
 *
 * docs/data-contract.md §8 및 §9 규격을 엄격히 준수합니다:
 * 1. 여러 스크린샷 추출 결과는 [mergeGeminiExtractionResponses]로 먼저 병합 (중복 제거).
 * 2. [buildKakaoSearchQuery]로 카카오 키워드 검색어 및 카테고리 그룹 코드 생성.
 * 3. [Semaphore]를 통해 동시 요청 수를 제한하면서 병렬로 카카오 장소 검색 수행. (결과 순서 유지)
 * 4. 카카오 검색 결과가 0개이고 카테고리 그룹 코드가 지정되어 있었던 경우, 카테고리 없이 1회 재검색 (§8 규칙).
 * 5. [CancellationException]을 제외한 예외 발생 시 [Log.w]에 기록 후 [MatchResult.STATUS_NOT_FOUND]로 처리.
 * 6. 선택된 후보([MatchResult.selectedCandidate])가 있는 경우 그 주소로 [regionMismatch] 판정 (§9 규칙).
 *    선택된 후보가 없으면(CHECK 등) Gemini의 [GeminiPlace.outsideRoomRegion]만 반영.
 *
 * @property kakaoLocalApiClient 카카오 Local API 검색 클라이언트
 * @property concurrencyLimit 카카오 API 병렬 검색 동시 요청 제한 수 (기본값: 5)
 */
class PlaceMatcher(
    private val kakaoLocalApiClient: KakaoLocalApiClient = KakaoLocalApiClient(),
    private val concurrencyLimit: Int = DEFAULT_CONCURRENCY_LIMIT,
) {

    /**
     * 여러 스크린샷의 Gemini 추출 결과 응답 리스트와 방 지역 정보를 입력받아 [MatchResult] 리스트를 반환합니다.
     *
     * @param extractionResponses Gemini 추출 결과 응답 리스트
     * @param roomRegion 방의 대표 지역명 (예: "부산")
     * @return 파싱 및 검색 매칭이 완료된 [MatchResult] 리스트
     */
    suspend fun matchPlaces(
        extractionResponses: List<GeminiExtractionResponse>,
        roomRegion: String,
        sourceType: String = "screenshot",
        sourceUrl: String? = null,
    ): List<MatchResult> {
        if (extractionResponses.isEmpty()) return emptyList()

        // 1. 여러 스크린샷/텍스트 결과를 하나로 병합 (중복 제거)
        val mergedResponse = mergeGeminiExtractionResponses(extractionResponses)
        if (mergedResponse.places.isEmpty()) return emptyList()

        return matchMergedPlaces(
            mergedResponse = mergedResponse,
            roomRegion = roomRegion,
            sourceType = sourceType,
            sourceUrl = sourceUrl,
        )
    }

    /**
     * 단일 Gemini 추출 결과 응답과 방 지역 정보를 입력받아 [MatchResult] 리스트를 반환합니다.
     */
    suspend fun matchPlaces(
        extractionResponse: GeminiExtractionResponse,
        roomRegion: String,
        sourceType: String = "screenshot",
        sourceUrl: String? = null,
    ): List<MatchResult> {
        return matchPlaces(listOf(extractionResponse), roomRegion, sourceType, sourceUrl)
    }

    private suspend fun matchMergedPlaces(
        mergedResponse: GeminiExtractionResponse,
        roomRegion: String,
        sourceType: String,
        sourceUrl: String?,
    ): List<MatchResult> = coroutineScope {
        val semaphore = Semaphore(concurrencyLimit)

        mergedResponse.places.mapIndexed { index, place ->
            async {
                semaphore.withPermit {
                    matchSinglePlace(
                        index = index,
                        place = place,
                        roomRegion = roomRegion,
                        regionHint = mergedResponse.regionHint,
                        sourceType = sourceType,
                        sourceUrl = sourceUrl,
                    )
                }
            }
        }.awaitAll()
    }

    private suspend fun matchSinglePlace(
        index: Int,
        place: GeminiPlace,
        roomRegion: String,
        regionHint: String?,
        sourceType: String,
        sourceUrl: String?,
    ): MatchResult {
        val localId = "img0_p$index"

        // 2. 검색어 생성 (docs/data-contract.md §8)
        val searchQuery = buildKakaoSearchQuery(
            name = place.name,
            roomRegion = roomRegion,
            region = place.region,
            categoryHint = place.categoryHint,
            outsideRoomRegion = place.outsideRoomRegion,
            regionHint = regionHint,
        )

        // 3. 카카오 키워드 검색 수행 (CancellationException은 예외로 다시 전파)
        var documents = try {
            val kakaoResp = kakaoLocalApiClient.searchKeyword(
                query = searchQuery.query,
                categoryGroupCode = searchQuery.categoryGroupCode,
            )
            kakaoResp.documents
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Kakao search failed for query '${searchQuery.query}' with category '${searchQuery.categoryGroupCode}': ${e.message}", e)
            emptyList()
        }

        // 4. 검색 결과가 0개이고 카테고리가 지정되어 있었을 경우 카테고리 없이 1회 재검색 (docs/data-contract.md §8)
        if (documents.isEmpty() && (searchQuery.categoryGroupCode != null)) {
            documents = try {
                val retryResp = kakaoLocalApiClient.searchKeyword(
                    query = searchQuery.query,
                    categoryGroupCode = null,
                )
                retryResp.documents
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Kakao retry search failed for query '${searchQuery.query}': ${e.message}", e)
                emptyList()
            }
        }

        // 5. 카카오 문서를 MatchCandidate로 변환
        val candidates = documents.map { MatchCandidate.fromKakaoDocument(it) }

        // 6. MatchResult.create()를 통해 1차 매칭 상태(MATCHED/CHECK/NOT_FOUND) 및 selectedCandidate 판단
        val initialMatchResult = MatchResult.create(
            localId = localId,
            extracted = place,
            candidates = candidates,
            regionMismatch = place.outsideRoomRegion,
            sourceType = sourceType,
            sourceUrl = sourceUrl,
        )

        // 7. 선택된 후보(selectedCandidate)가 존재하는 경우 그 주소로 regionMismatch 최종 판정 (docs/data-contract.md §9)
        //    선택이 없으면(CHECK 등) Gemini의 outsideRoomRegion만 유지
        val selectedCand = initialMatchResult.selectedCandidate
        val finalRegionMismatch = if (selectedCand != null) {
            isCandidateAddressOutsideRoomRegion(
                candidateAddress = selectedCand.address,
                roomRegion = roomRegion,
                geminiOutsideRoomRegion = place.outsideRoomRegion,
            )
        } else {
            place.outsideRoomRegion
        }

        return if (finalRegionMismatch != initialMatchResult.regionMismatch) {
            initialMatchResult.copy(
                regionMismatch = finalRegionMismatch,
                checked = if (finalRegionMismatch) false else initialMatchResult.checked,
            )
        } else {
            initialMatchResult
        }
    }

    companion object {
        private const val TAG = "PlaceMatcher"
        const val DEFAULT_CONCURRENCY_LIMIT = 5
    }
}

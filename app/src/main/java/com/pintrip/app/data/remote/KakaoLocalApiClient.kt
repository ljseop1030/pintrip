package com.pintrip.app.data.remote

import com.pintrip.app.BuildConfig
import com.pintrip.app.data.model.KakaoKeywordSearchResponse
import com.pintrip.app.data.model.parseKakaoKeywordSearchResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 카카오 Local API와 통신하여 장소 키워드 검색 등을 수행하는 Remote Client.
 *
 * docs/data-contract.md 및 AGENTS.md 규격을 준수합니다:
 * 1. REST API 키는 [BuildConfig.KAKAO_REST_API_KEY]를 통해 읽어오며, 코드에 하드코딩하거나 로그에 남기지 않습니다.
 * 2. 카카오 키워드 검색 API (`GET /v2/local/search/keyword.json`) 호출 suspend 함수를 제공합니다.
 * 3. 외부 네트워크 통신은 [Dispatchers.IO]에서 실행됩니다.
 *
 * @property apiKey 카카오 REST API 키 (기본값: [BuildConfig.KAKAO_REST_API_KEY])
 * @property baseUrl 카카오 API Base URL (기본값: "https://dapi.kakao.com")
 */
class KakaoLocalApiClient(
    private val apiKey: String = BuildConfig.KAKAO_REST_API_KEY,
    private val baseUrl: String = DEFAULT_BASE_URL,
) {

    companion object {
        private const val DEFAULT_BASE_URL = "https://dapi.kakao.com"
    }

    /**
     * 카카오 Local 키워드 검색 API를 호출합니다 (`GET /v2/local/search/keyword.json`).
     *
     * @param query 검색할 키워드 (예: "부산 해운대 모모스커피")
     * @param categoryGroupCode 카테고리 그룹 코드 (선택: FD6, CE7, AT4, AD5 등)
     * @param page 페이지 번호 (1 ~ 45, 기본값: Kakao API 기본 1)
     * @param size 한 페이지 문서 수 (1 ~ 15, 기본값: Kakao API 기본 15)
     * @param x 중심 좌표의 경도(Longitude, 선택)
     * @param y 중심 좌표의 위도(Latitude, 선택)
     * @param radius 중심 좌표 기준 검색 반경(미터, 0 ~ 20000, 선택)
     * @param rect 사각형 영역 좌표 `min_x,min_y,max_x,max_y` (선택)
     * @param sort 정렬 순서 (`accuracy` | `distance`, 선택)
     * @return [KakaoKeywordSearchResponse] 파싱된 검색 결과 응답 모델
     * @throws IllegalArgumentException query가 빈 문자열이거나, API 키가 비어있는 경우
     * @throws IOException 네트워크 통신 실패 또는 HTTP 비정상 코드(Non-2xx) 수신 시
     */
    suspend fun searchKeyword(
        query: String,
        categoryGroupCode: String? = null,
        page: Int? = null,
        size: Int? = null,
        x: String? = null,
        y: String? = null,
        radius: Int? = null,
        rect: String? = null,
        sort: String? = null,
    ): KakaoKeywordSearchResponse = withContext(Dispatchers.IO) {
        require(query.isNotBlank()) { "검색어(query)는 비어 있을 수 없습니다." }
        require(apiKey.isNotBlank()) { "카카오 REST API 키가 설정되지 않았습니다. (BuildConfig.KAKAO_REST_API_KEY가 비어 있음)" }

        val urlString = buildString {
            append(baseUrl)
            append("/v2/local/search/keyword.json?")
            append("query=").append(URLEncoder.encode(query, "UTF-8"))
            if (!categoryGroupCode.isNullOrBlank()) {
                append("&category_group_code=").append(URLEncoder.encode(categoryGroupCode, "UTF-8"))
            }
            if (page != null) {
                append("&page=").append(page)
            }
            if (size != null) {
                append("&size=").append(size)
            }
            if (!x.isNullOrBlank()) {
                append("&x=").append(URLEncoder.encode(x, "UTF-8"))
            }
            if (!y.isNullOrBlank()) {
                append("&y=").append(URLEncoder.encode(y, "UTF-8"))
            }
            if (radius != null) {
                append("&radius=").append(radius)
            }
            if (!rect.isNullOrBlank()) {
                append("&rect=").append(URLEncoder.encode(rect, "UTF-8"))
            }
            if (!sort.isNullOrBlank()) {
                append("&sort=").append(URLEncoder.encode(sort, "UTF-8"))
            }
        }

        val url = URL(urlString)
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            // 헤더 설정: API 키는 절대 로그에 남기거나 하드코딩하지 않음
            setRequestProperty("Authorization", "KakaoAK $apiKey")
            connectTimeout = 10000
            readTimeout = 10000
        }

        try {
            val responseCode = connection.responseCode
            val inputStream = if (responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            val responseBody = inputStream?.bufferedReader()?.use { it.readText() }.orEmpty()

            if (responseCode !in 200..299) {
                // API 키나 인증 헤더 정보는 예외 메시지에 노출시키지 않습니다.
                throw IOException("카카오 키워드 검색 API 호출 실패 [HTTP $responseCode]: $responseBody")
            }

            parseKakaoKeywordSearchResponse(responseBody)
        } finally {
            connection.disconnect()
        }
    }
}

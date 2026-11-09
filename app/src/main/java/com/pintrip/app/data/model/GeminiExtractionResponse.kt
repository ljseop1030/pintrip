package com.pintrip.app.data.model

import androidx.annotation.Keep
import com.google.firebase.ai.type.Schema
import org.json.JSONObject
import java.io.Serializable

/**
 * Gemini 장소 추출 결과 중 개별 장소 정보 데이터 모델.
 *
 * docs/data-contract.md §8 규격을 준수합니다:
 * - name: Kakao 검색 쿼리로 사용할 장소명 (지점명 포함 "이재모피자 본점", 번호/이모지/따옴표 제거)
 * - region: 이미지 내 장소 근처 작성된 지역명 (null인 경우 추측하지 않음)
 * - categoryHint: 카테고리 힌트 ("dining", "cafe", "sights", "stay", "etc" 또는 null)
 * - rawText: 이미지에서 인식된 원본 텍스트 ("인식된 텍스트" 표출용)
 * - outsideRoomRegion: 방 지역 외 장소 여부 (명확한 증거가 있을 때만 true)
 */
@Keep
data class GeminiPlace(
    val name: String = "",
    val region: String? = null,
    val categoryHint: String? = null,
    val rawText: String = "",
    val outsideRoomRegion: Boolean = false,
) : Serializable

/**
 * Gemini 장소 추출 결과 응답 루트 데이터 모델.
 *
 * docs/data-contract.md §8 규격을 준수합니다:
 * - places: 추출된 장소 목록 (최대 5개)
 * - regionHint: 이미지/텍스트 전체 지역 힌트
 * - hasMore: 5개 초과 추출로 잘렸는지 여부 (true 시 "최대 5개 장소까지 추출되었습니다" 안내)
 */
@Keep
data class GeminiExtractionResponse(
    val places: List<GeminiPlace> = emptyList(),
    val regionHint: String? = null,
    val hasMore: Boolean = false,
)

/**
 * Firebase AI Logic / Gemini API 호출 시 전달할 Structured Output Schema ([Schema]).
 *
 * docs/data-contract.md §8 규격에 따른 JSON 응답 구조를 정의합니다.
 */
val GEMINI_EXTRACTION_RESPONSE_SCHEMA: Schema = Schema.obj(
    properties = mapOf(
        "places" to Schema.array(
            items = Schema.obj(
                properties = mapOf(
                    "name" to Schema.string(
                        description = "Kakao search query place name. Keep branch names (e.g. '이재모피자 본점'). Strip numbering, emoji, quotes.",
                    ),
                    "region" to Schema.string(
                        description = "Region written near the place in the image (e.g. '부산 금정구'). null if absent. No guessing.",
                        nullable = true,
                    ),
                    "categoryHint" to Schema.enumeration(
                        values = listOf("dining", "cafe", "sights", "stay", "etc"),
                        description = "Category hint: dining, cafe, sights, stay, or etc.",
                        nullable = true,
                    ),
                    "rawText" to Schema.string(
                        description = "Original text recognized in the image.",
                    ),
                    "outsideRoomRegion" to Schema.boolean(
                        description = "true only with clear evidence the place is outside the room region.",
                    ),
                ),
                optionalProperties = listOf("region", "categoryHint"),
                description = "Extracted place item.",
            ),
            description = "List of extracted places (max 5).",
        ),
        "regionHint" to Schema.string(
            description = "Region context of the whole image or text. null if absent.",
            nullable = true,
        ),
        "hasMore" to Schema.boolean(
            description = "true if more than 5 places were extracted and cut off.",
        ),
    ),
    optionalProperties = listOf("regionHint"),
    description = "Root response schema for place extraction.",
)

/**
 * Gemini 추출 결과 JSON 문자열을 [GeminiExtractionResponse] 객체로 파싱합니다.
 *
 * 외부 라이브러리 없이 안드로이드 프레임워크 표준 [JSONObject]를 활용합니다.
 *
 * @param jsonString Gemini가 반환한 JSON 문자열
 * @return 파싱된 [GeminiExtractionResponse]
 * @throws org.json.JSONException JSON 구조가 유효하지 않은 경우
 */
fun parseGeminiExtractionResponse(jsonString: String): GeminiExtractionResponse {
    val cleanJson = jsonString.trim()
        .removePrefix("```json")
        .removePrefix("```")
        .removeSuffix("```")
        .trim()

    val jsonObject = JSONObject(cleanJson)

    val regionHint = if (jsonObject.isNull("regionHint")) {
        null
    } else {
        jsonObject.optString("regionHint", "").ifBlank { null }
    }

    val hasMore = jsonObject.optBoolean("hasMore", false)

    val placesArray = jsonObject.optJSONArray("places")
    val placesList = mutableListOf<GeminiPlace>()

    if (placesArray != null) {
        for (i in 0 until placesArray.length()) {
            val placeObj = placesArray.optJSONObject(i) ?: continue

            val name = placeObj.optStringNonNull("name")
            val region = if (placeObj.isNull("region")) null else placeObj.optString("region", "").ifBlank { null }
            val categoryHint = if (placeObj.isNull("categoryHint")) null else placeObj.optString("categoryHint", "").ifBlank { null }
            val rawText = placeObj.optStringNonNull("rawText")
            val outsideRoomRegion = placeObj.optBoolean("outsideRoomRegion", false)

            if (name.isBlank()) continue
            placesList.add(
                GeminiPlace(
                    name = name,
                    region = region,
                    categoryHint = categoryHint,
                    rawText = rawText,
                    outsideRoomRegion = outsideRoomRegion,
                ),
            )
        }
    }

    return GeminiExtractionResponse(
        places = placesList,
        regionHint = regionHint,
        hasMore = hasMore,
    )
}

private fun JSONObject.optStringNonNull(key: String, defaultValue: String = ""): String {
    if (isNull(key)) return defaultValue
    return optString(key, defaultValue)
}

package com.pintrip.app.data.remote

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import com.pintrip.app.data.model.GEMINI_EXTRACTION_RESPONSE_SCHEMA
import com.pintrip.app.data.model.GeminiExtractionResponse
import com.pintrip.app.data.model.parseGeminiExtractionResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Firebase AI Logic을 활용하여 스크린샷 이미지에서 장소 정보를 추출하는 클래스.
 *
 * docs/data-contract.md §8 규격을 준수합니다:
 * - 백엔드: Gemini Developer API (GenerativeBackend.googleAI())
 * - 모델: gemini-3.5-flash-lite
 * - temperature: 0
 * - responseMimeType: "application/json"
 * - responseSchema: GEMINI_EXTRACTION_RESPONSE_SCHEMA
 */
class GeminiExtractor(
    private val context: Context,
) {
    private val generativeModel by lazy {
        val firebaseAi = Firebase.ai(backend = GenerativeBackend.googleAI())
        firebaseAi.generativeModel(
            modelName = MODEL_NAME,
            generationConfig = generationConfig {
                temperature = 0f
                responseMimeType = "application/json"
                responseSchema = GEMINI_EXTRACTION_RESPONSE_SCHEMA
            },
        )
    }

    /**
     * 단일 스크린샷 Uri를 받아 Gemini에 전송하고, 장소 추출 결과를 파싱하여 반환합니다.
     *
     * @param imageUri 스크린샷 이미지 Uri
     * @param roomRegion 여행 방의 대표 지역 (예: "부산"). 지정되지 않은 경우 null
     * @return 파싱된 [GeminiExtractionResponse]
     * @throws Exception 비트맵 로드 실패, Gemini API 호출 실패 또는 JSON 파싱 실패 시
     */
    suspend fun extractFromScreenshot(
        imageUri: Uri,
        roomRegion: String? = null,
    ): GeminiExtractionResponse = withContext(Dispatchers.IO) {
        val bitmap = loadBitmap(context, imageUri)
        val promptText = buildPromptText(roomRegion)

        val inputContent = content {
            image(bitmap)
            text(promptText)
        }

        val response = generativeModel.generateContent(inputContent)
        val jsonText = response.text
            ?: throw IllegalStateException("Gemini response text is null")

        parseGeminiExtractionResponse(jsonText)
    }

    /**
     * 텍스트(웹페이지 본문 또는 공유된 텍스트)를 받아 Gemini에 전송하고, 장소 추출 결과를 파싱하여 반환합니다.
     *
     * docs/data-contract.md §8 규격을 준수합니다:
     * - 광고, 관련 글/추천 글에 기재된 장소는 제외
     * - 장소명과 위치(주소/지역)가 함께 기재된 경우만 추출
     *
     * @param text 추출 대상 본문/텍스트
     * @param roomRegion 여행 방의 대표 지역 (예: "부산"). 지정되지 않은 경우 null
     * @return 파싱된 [GeminiExtractionResponse]
     * @throws Exception Gemini API 호출 실패 또는 JSON 파싱 실패 시
     */
    suspend fun extractFromText(
        text: String,
        roomRegion: String? = null,
    ): GeminiExtractionResponse = withContext(Dispatchers.IO) {
        val sanitizedText = if (text.length > MAX_TEXT_LENGTH) {
            text.take(MAX_TEXT_LENGTH)
        } else {
            text
        }
        val promptText = buildTextPromptText(text = sanitizedText, roomRegion = roomRegion)

        val inputContent = content {
            text(promptText)
        }

        val response = generativeModel.generateContent(inputContent)
        val jsonText = response.text
            ?: throw IllegalStateException("Gemini response text is null")

        parseGeminiExtractionResponse(jsonText)
    }

    private fun loadBitmap(context: Context, uri: Uri): Bitmap {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.isMutableRequired = true
            val currentWidth = info.size.width
            if (currentWidth > MAX_IMAGE_WIDTH) {
                val targetWidth = MAX_IMAGE_WIDTH
                val targetHeight = ((info.size.height.toFloat() * targetWidth) / currentWidth.toFloat()).toInt()
                decoder.setTargetSize(targetWidth, targetHeight)
            }
        }
    }

    private fun buildPromptText(roomRegion: String?): String {
        val regionContext = if (!roomRegion.isNullOrBlank()) {
            "This trip room's region is \"${roomRegion.trim()}\"."
        } else {
            "No specific trip room region is specified."
        }

        return """
            $regionContext
            
            Analyze the provided screenshot image and extract real shop, restaurant, cafe, accommodation, or tourist attraction names according to these rules:
            1. Extract only shop/attraction names actually written in the image. Never invent places.
            2. Do not extract addresses, menu items, hashtags, person names, or bare region names as places.
            3. 'name': the Korean name used for Kakao Map search. If the image shows an English or romanized name (e.g. "Momos Coffee"), write the Korean name ("모모스커피"). Keep branch names (e.g. "이재모피자 본점"). Strip numbering, emojis, and quotes.
            4. 'rawText': the exact original text written in the image, unchanged.
            5. 'region': a region or district written near the place, including one read from its address (e.g. "부산 금정구"). Otherwise null. Never fill it with the room region.
            6. 'categoryHint': one of ["dining", "cafe", "sights", "stay", "etc"], or null.
            7. 'outsideRoomRegion': true ONLY with clear evidence the place is outside the room region. Otherwise false.
            8. 'regionHint': the region the whole image is about, if clear. Otherwise null.
            9. Deduplicate repeated places.
        """.trimIndent()
    }

    private fun buildTextPromptText(text: String, roomRegion: String?): String {
        val regionContext = if (!roomRegion.isNullOrBlank()) {
            "This trip room's region is \"${roomRegion.trim()}\"."
        } else {
            "No specific trip room region is specified."
        }

        return """
            $regionContext
            
            Analyze the provided web page / blog text and extract real shop, restaurant, cafe, accommodation, or tourist attraction names according to these strict rules:
            1. Extract ONLY places where both the place name AND its specific location, address, or region are mentioned together in the text.
            2. EXCLUDE any places that appear in advertisements, sponsorships, promotional links, sidebars, or recommended/related posts sections (e.g., "관련글", "추천글", "광고", "협찬", "이 블로그의 다른 글").
            3. Extract only real shop/attraction names actually written in the text. Never invent places.
            4. Do not extract addresses, menu items, hashtags, person names, or bare region names as places.
            5. 'name': the Korean name used for Kakao Map search. If the text shows an English or romanized name, write the Korean name. Keep branch names (e.g., "이재모피자 본점"). Strip numbering, emojis, and quotes.
            6. 'rawText': the snippet of original text where the place name and its location were mentioned together.
            7. 'region': a region or district written near the place or read from its location/address (e.g. "부산 금정구"). Otherwise null. Never fill it with the room region.
            8. 'categoryHint': one of ["dining", "cafe", "sights", "stay", "etc"], or null.
            9. 'outsideRoomRegion': true ONLY with clear evidence the place is outside the room region. Otherwise false.
            10. 'regionHint': the region context the whole text is about, if clear. Otherwise null.
            11. Deduplicate repeated places.
            
            Source Text:
            $text
        """.trimIndent()
    }

    companion object {
        const val MODEL_NAME = "gemini-3.5-flash-lite"
        const val MAX_IMAGE_WIDTH = 1080
        const val MAX_TEXT_LENGTH = 8000
    }
}

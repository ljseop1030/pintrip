package com.pintrip.app.room

import android.content.Intent
import android.net.Uri

/**
 * 초대 딥링크 URI 생성 및 추출 유틸리티.
 *
 * 규격: `pintrip://join?code=ABC234`
 * - scheme: pintrip (대소문자 미구분)
 * - host: join (대소문자 미구분)
 * - path: 비어 있거나 "/"
 * - query param: code (단 1개만 허용, 6자리 영문 대문자 및 숫자, 0/O/1/I 제외)
 * - userInfo, port, fragment 등 비표준 요소가 포함된 경우 거부
 */
object DeepLinkUtils {
    const val SCHEME_PINTRIP = "pintrip"
    const val HOST_JOIN = "join"
    const val PARAM_CODE = "code"

    private val CODE_REGEX = Regex("^[23456789ABCDEFGHJKLMNPQRSTUVWXYZ]{6}$")

    /**
     * 초대 코드로 표준 딥링크 문자열을 생성합니다.
     */
    fun buildInviteLink(code: String): String {
        val normalizedCode = code.trim().uppercase()
        return "$SCHEME_PINTRIP://$HOST_JOIN?$PARAM_CODE=$normalizedCode"
    }

    /**
     * [Intent]에서 초대 코드를 안전하게 추출합니다.
     * `ACTION_VIEW` 액션이 아니거나 유효하지 않은 딥링크는 null을 반환합니다.
     */
    fun extractInviteCode(intent: Intent?): String? {
        if (intent == null || intent.action != Intent.ACTION_VIEW) return null
        return extractInviteCode(intent.dataString)
    }

    /**
     * [Uri]에서 scheme, host, code 파라미터를 검사하여 유효한 초대 코드를 추출합니다.
     */
    fun extractInviteCode(uri: Uri?): String? {
        if (uri == null) return null
        return extractInviteCode(uri.toString())
    }

    /**
     * URI 문자열 표현에서 엄격한 구문 검사를 수행하여 유효한 초대 코드를 추출합니다.
     */
    fun extractInviteCode(uriString: String?): String? {
        if (uriString.isNullOrBlank()) return null
        return try {
            val javaUri = java.net.URI(uriString)
            val scheme = javaUri.scheme?.lowercase() ?: return null
            val host = javaUri.host?.lowercase() ?: return null

            if (scheme != SCHEME_PINTRIP || host != HOST_JOIN) return null

            // 비표준 경로, 포트, 사용자 정보, 해시(fragment)가 포함된 경우 거부
            val path = javaUri.path
            if (!path.isNullOrEmpty() && path != "/") return null
            if (!javaUri.userInfo.isNullOrBlank()) return null
            if (javaUri.port != -1) return null
            if (!javaUri.fragment.isNullOrBlank()) return null

            val query = javaUri.rawQuery ?: return null
            val codeParams = query.split("&")
                .map { it.split("=") }
                .filter { it.size == 2 && it[0].equals(PARAM_CODE, ignoreCase = true) }

            // code 파라미터가 없거나 중복(2개 이상) 정의된 경우 거부
            if (codeParams.size != 1) return null

            val rawCodeVal = codeParams[0][1]
            val decodedCode = java.net.URLDecoder.decode(rawCodeVal, "UTF-8")
            val normalizedCode = decodedCode.trim().uppercase()

            if (normalizedCode.matches(CODE_REGEX)) {
                normalizedCode
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }
}

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
 * - userInfo, port, fragment, 중복 키, 불완전 파라미터가 포함된 비표준 URI 거부
 */
object DeepLinkUtils {
    const val SCHEME_PINTRIP = "pintrip"
    const val HOST_JOIN = "join"
    const val PARAM_CODE = "code"

    private val CODE_REGEX = Regex("^[23456789ABCDEFGHJKLMNPQRSTUVWXYZ]{6}$")

    /**
     * 초대 코드로 표준 딥링크 문자열을 생성합니다. (기존 호환성 유지)
     */
    fun buildInviteLink(code: String): String {
        return buildInviteDeepLink(code)
    }

    /**
     * 초대 코드로 표준 커스텀 딥링크 문자열을 생성합니다 (`pintrip://join?code=ABC234`).
     */
    fun buildInviteDeepLink(code: String): String {
        val normalizedCode = code.trim().uppercase()
        return "$SCHEME_PINTRIP://$HOST_JOIN?$PARAM_CODE=$normalizedCode"
    }

    /**
     * 카카오톡 등 외부 웹 공유용 HTTPS URL을 생성합니다 (`https://<domain>/join?code=ABC234`).
     *
     * @param code 6자리 초대 코드
     * @param baseUrl 웹 공유 기본 URL (기본값: strings.xml의 R.string.invite_web_share_base_url)
     * @return 유효한 HTTPS 공유 URL 또는 null (코드 또는 URL 형식 불일치 시)
     */
    fun buildWebShareUrl(code: String, baseUrl: String): String? {
        val normalizedCode = code.trim().uppercase()
        if (!normalizedCode.matches(CODE_REGEX)) return null
        if (baseUrl.isBlank() || baseUrl.contains("#")) return null
        return try {
            val javaUri = java.net.URI(baseUrl)
            val scheme = javaUri.scheme?.lowercase() ?: return null
            if (scheme != "https") return null

            val host = javaUri.host
            if (host.isNullOrBlank()) return null
            if (javaUri.userInfo != null || javaUri.rawAuthority?.contains("@") == true) return null

            val separator = if (baseUrl.contains("?")) "&" else "?"
            val param = "$PARAM_CODE=${java.net.URLEncoder.encode(normalizedCode, "UTF-8")}"
            "$baseUrl$separator$param"
        } catch (_: Exception) {
            null
        }
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
            // # (fragment) 구문 표기가 포함되어 있으면 즉시 거부 (빈 fragment 포함)
            if (uriString.contains("#")) return null

            val javaUri = java.net.URI(uriString)
            val scheme = javaUri.scheme?.lowercase() ?: return null
            val host = javaUri.host?.lowercase() ?: return null

            if (scheme != SCHEME_PINTRIP || host != HOST_JOIN) return null

            // 비표준 경로, 포트, 사용자 정보가 포함된 경우 거부
            val path = javaUri.path
            if (!path.isNullOrEmpty() && path != "/") return null
            if (javaUri.userInfo != null || javaUri.rawAuthority?.contains("@") == true) return null
            if (javaUri.port != -1 || javaUri.rawAuthority?.contains(":") == true) return null

            val query = javaUri.rawQuery ?: return null
            val paramTokens = query.split("&")

            // code 파라미터 키의 등장 횟수 검사 (불완전 파라미터 포함)
            val codeKeyCount = paramTokens.count { token ->
                val key = if (token.contains("=")) token.substringBefore("=") else token
                key.equals(PARAM_CODE, ignoreCase = true)
            }

            // code 키가 전혀 없거나 2개 이상(중복) 정의된 경우 거부
            if (codeKeyCount != 1) return null

            // 단일 code 파라미터 형식이 key=value 문법을 정확히 만족하는지 확인
            val codeToken = paramTokens.first { token ->
                val key = if (token.contains("=")) token.substringBefore("=") else token
                key.equals(PARAM_CODE, ignoreCase = true)
            }

            val parts = codeToken.split("=")
            if (parts.size != 2) return null

            val rawCodeVal = parts[1]
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

package com.pintrip.app.ui.common

object KoreanJosa {

    /**
     * 단어의 마지막 글자에 받침이 있으면 "이", 없으면 "가", 한글이 아니거나 빈 문자열이면 "이(가)"를 반환합니다.
     */
    fun subjectParticle(word: String): String {
        if (word.isEmpty()) return "이(가)"
        val lastChar = word.last()
        val code = lastChar.code
        if (code in 0xAC00..0xD7A3) {
            val hasBatchim = (code - 0xAC00) % 28 != 0
            return if (hasBatchim) "이" else "가"
        }
        return "이(가)"
    }
}

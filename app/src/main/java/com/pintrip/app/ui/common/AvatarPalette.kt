package com.pintrip.app.ui.common

import androidx.annotation.ColorRes
import com.pintrip.app.R
import com.pintrip.app.data.repository.UserRepository

/**
 * 아바타에 쓰는 색 쌍 (파스텔 배경 + 어두운 글자). 시안 12 장소 상세의 좋아요 스택 규격이다.
 *
 * `users/{uid}.avatarColor`에는 배경 hex만 저장되므로(docs/data-contract.md §3),
 * 글자색은 [UserRepository.AVATAR_COLORS]와 같은 순서의 쌍에서 찾아 쓴다.
 *
 * 팔레트를 바꾸기 전에 가입한 사용자는 저장된 색이 목록에 없다. 이때는 비워 두지 않고
 * 안정적인 키(uid 등)의 해시로 한 쌍을 골라 화면 전체가 같은 톤을 유지하게 한다.
 */
object AvatarPalette {

    data class Colors(
        @param:ColorRes val backgroundRes: Int,
        @param:ColorRes val textRes: Int,
    )

    private val PAIRS = listOf(
        Colors(R.color.avatar_bg_blue, R.color.avatar_text_blue),
        Colors(R.color.avatar_bg_green, R.color.avatar_text_green),
        Colors(R.color.avatar_bg_purple, R.color.avatar_text_purple),
        Colors(R.color.avatar_bg_orange, R.color.avatar_text_orange),
    )

    /**
     * @param storedHex `avatarColor`에 저장된 배경 hex. 팔레트에 있으면 그 쌍을 쓴다.
     * @param key 저장된 색이 팔레트에 없을 때 쓸 안정적인 식별자 (uid, 없으면 닉네임).
     */
    fun colorsFor(storedHex: String?, key: String): Colors {
        val normalized = storedHex?.trim().orEmpty()
        if (normalized.isNotEmpty()) {
            val index = UserRepository.AVATAR_COLORS.indexOfFirst { it.equals(normalized, ignoreCase = true) }
            if (index in PAIRS.indices) return PAIRS[index]
        }
        return PAIRS[key.hashCode().mod(PAIRS.size)]
    }
}

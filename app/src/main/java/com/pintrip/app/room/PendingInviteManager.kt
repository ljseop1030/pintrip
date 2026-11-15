package com.pintrip.app.room

import android.content.Context
import android.content.SharedPreferences

/**
 * 로그인 전/후 대기 중인 초대 코드를 관리하는 저장소.
 *
 * 화면 전환, 회원가입/프로필 설정, 프로세스 재생성, 화면 회전 중에도
 * 대기 중 초대를 안전하게 보존하기 위해 SharedPreferences를 사용합니다.
 */
object PendingInviteManager {
    private const val PREF_NAME = "pintrip_invite_prefs"
    private const val KEY_PENDING_CODE = "pending_invite_code"

    @Volatile
    internal var preferenceOverride: SharedPreferences? = null

    private fun getPrefs(context: Context?): SharedPreferences? {
        val override = preferenceOverride
        if (override != null) return override
        return context?.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    /**
     * 유효한 초대 코드를 임시 보관합니다.
     * 유효하지 않은 코드 형식인 경우 저장하지 않습니다.
     */
    fun savePendingCode(context: Context?, code: String) {
        val normalized = code.trim().uppercase()
        if (normalized.matches(Regex("^[23456789ABCDEFGHJKLMNPQRSTUVWXYZ]{6}$"))) {
            getPrefs(context)?.edit()?.putString(KEY_PENDING_CODE, normalized)?.apply()
        }
    }

    /**
     * 보관 중인 초대 코드가 있는지 확인합니다.
     * 저장된 코드가 유효하지 않은 경우 자동으로 삭제하고 null을 반환합니다.
     * 단순히 읽는 용도이며, 처리 완료/취소/무효 확정 전까지는 삭제되지 않습니다.
     */
    fun getPendingCode(context: Context?): String? {
        val code = getPrefs(context)?.getString(KEY_PENDING_CODE, null) ?: return null
        val normalized = code.trim().uppercase()
        return if (normalized.matches(Regex("^[23456789ABCDEFGHJKLMNPQRSTUVWXYZ]{6}$"))) {
            normalized
        } else {
            clearPendingCode(context)
            null
        }
    }

    /**
     * 보관된 초대 코드를 명시적으로 삭제합니다.
     */
    fun clearPendingCode(context: Context?) {
        getPrefs(context)?.edit()?.remove(KEY_PENDING_CODE)?.apply()
    }

    /**
     * 보관된 초대 코드가 지정한 코드와 일치하는 경우에만 선택적으로 삭제합니다.
     */
    fun clearPendingCodeIf(context: Context?, code: String) {
        val current = getPendingCode(context)
        if (current != null && current.equals(code.trim(), ignoreCase = true)) {
            clearPendingCode(context)
        }
    }
}

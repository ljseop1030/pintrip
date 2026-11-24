package com.pintrip.app.auth

import com.google.firebase.auth.FirebaseUser

/**
 * 로그인 화면 UI 상태.
 */
sealed interface LoginUiState {
    /** 초기 또는 대기 상태 */
    object Idle : LoginUiState

    /** 로그인/프로필 처리 중 */
    object Loading : LoginUiState

    /** 필드별 입력값 검증 에러 (이메일 미입력, 비밀번호 미입력 등) */
    data class InputError(
        val emailErrorResId: Int? = null,
        val passwordErrorResId: Int? = null,
    ) : LoginUiState

    /** 폼 전체 에러 (자격 증명 불일치, 네트워크/서버 오류 등) */
    data class FormError(
        val messageResId: Int,
    ) : LoginUiState

    /** 로그인 성공 후 프로필 문서 누락 (닉네임 설정 필요) */
    data class ProfileMissing(
        val user: FirebaseUser,
    ) : LoginUiState

    /** 프로필 조회 실패 */
    data class ProfileLoadError(
        val user: FirebaseUser,
    ) : LoginUiState

    /** 프로필 저장 실패 */
    data class ProfileSaveError(
        val user: FirebaseUser,
    ) : LoginUiState

    /** 로그인 및 프로필 확인 완료 (RoomListActivity 이동) */
    object Success : LoginUiState
}

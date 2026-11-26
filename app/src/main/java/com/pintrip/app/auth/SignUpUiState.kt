package com.pintrip.app.auth

import com.google.firebase.auth.FirebaseUser

/**
 * 회원가입 화면 지속 UI 상태 (입력 폼 및 버튼 활성화용).
 */
sealed interface SignUpUiState {
    object Idle : SignUpUiState
    object Loading : SignUpUiState

    data class InputError(
        val nicknameErrorResId: Int? = null,
        val emailErrorResId: Int? = null,
        val passwordErrorResId: Int? = null,
        val confirmPasswordErrorResId: Int? = null,
        val termsErrorResId: Int? = null,
    ) : SignUpUiState

    data class FormError(
        val messageResId: Int,
    ) : SignUpUiState
}

/**
 * 회원가입 화면 일회성 이벤트 (다이얼로그 노출 및 이동용).
 */
sealed interface SignUpEvent {
    data class ShowProfileLoadError(
        val user: FirebaseUser?,
        val uid: String,
        val nickname: String,
        val email: String,
        val isExistingAuthAccount: Boolean,
    ) : SignUpEvent

    data class ShowProfileSaveError(
        val user: FirebaseUser?,
        val uid: String,
        val nickname: String,
        val email: String,
        val isExistingAuthAccount: Boolean,
    ) : SignUpEvent

    data class ShowCompletionCheckError(
        val uid: String,
    ) : SignUpEvent

    object ShowProfileCorrupted : SignUpEvent

    data class ShowSignUpComplete(
        val isExistingAccount: Boolean,
        val isProfileRestored: Boolean = false,
    ) : SignUpEvent

    object NavigateToRoomList : SignUpEvent
}

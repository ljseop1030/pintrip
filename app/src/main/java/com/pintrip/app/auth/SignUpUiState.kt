package com.pintrip.app.auth

import com.google.firebase.auth.FirebaseUser

/**
 * 회원가입 화면 UI 상태.
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

    data class ProfileLoadError(
        val user: FirebaseUser?,
        val uid: String,
        val nickname: String,
        val email: String,
        val isExistingAuthAccount: Boolean,
    ) : SignUpUiState

    data class ProfileSaveError(
        val user: FirebaseUser?,
        val uid: String,
        val nickname: String,
        val email: String,
        val isExistingAuthAccount: Boolean,
    ) : SignUpUiState

    data class ProfileCorrupted(
        val user: FirebaseUser?,
    ) : SignUpUiState

    data class SignUpComplete(
        val isExistingAccount: Boolean,
        val isProfileRestored: Boolean = false,
    ) : SignUpUiState
}

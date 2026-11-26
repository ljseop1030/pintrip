package com.pintrip.app.auth

import android.util.Patterns
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.Source
import com.pintrip.app.R
import com.pintrip.app.data.repository.UserRepository
import com.pintrip.app.ui.common.Event
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * 회원가입 화면 ViewModel.
 *
 * 닉네임, 이메일, 비밀번호, 비밀번호 확인 및 약관 동의 검증,
 * Firebase Auth 계정 생성/로그인 및 Firestore 프로필 생성을 처리합니다.
 */
class SignUpViewModel(
    private val auth: FirebaseAuth? = null,
    private val userRepository: UserRepository? = null,
    private val createUserAction: (suspend (String, String) -> FirebaseUser?)? = null,
    private val signInAction: (suspend (String, String) -> FirebaseUser?)? = null,
    private val authUserProvider: (suspend (String, String, Boolean) -> Pair<String, String?>?)? = null,
    private val currentUidProvider: (() -> String?)? = null,
    private val savedStateHandle: SavedStateHandle = SavedStateHandle(),
) : ViewModel() {

    companion object {
        private const val KEY_COMPLETED_UID = "key_completed_uid"
        private const val KEY_IS_SIGNUP_COMPLETED = "key_is_signup_completed"
    }

    data class PendingErrorContext(
        val user: FirebaseUser?,
        val uid: String,
        val nickname: String,
        val email: String,
        val isExistingAuthAccount: Boolean,
    )

    private val firebaseAuth: FirebaseAuth
        get() = auth ?: FirebaseAuth.getInstance()

    private val userRepo: UserRepository
        get() = userRepository ?: UserRepository(auth = firebaseAuth)

    private fun getCurrentAuthUid(): String? {
        return if (currentUidProvider != null) {
            currentUidProvider.invoke()
        } else {
            try { firebaseAuth.currentUser?.uid } catch (_: Exception) { null }
        }
    }

    private val _uiState = MutableLiveData<SignUpUiState>(SignUpUiState.Idle)
    val uiState: LiveData<SignUpUiState> get() = _uiState

    private val _events = MutableLiveData<Event<SignUpEvent>?>()
    val events: LiveData<Event<SignUpEvent>?> get() = _events

    private var isSignUpInProgress = false
    private var sessionToken = 0L

    var isSignUpCompleted: Boolean
        get() = savedStateHandle.get<Boolean>(KEY_IS_SIGNUP_COMPLETED) ?: false
        private set(value) {
            savedStateHandle[KEY_IS_SIGNUP_COMPLETED] = value
        }

    var completedUid: String?
        get() = savedStateHandle.get<String>(KEY_COMPLETED_UID)
        private set(value) {
            savedStateHandle[KEY_COMPLETED_UID] = value
        }

    var pendingErrorContext: PendingErrorContext? = null
        private set

    /**
     * 비동기 작업 완료 후 세션 토큰 및 현재 Auth UID 검증.
     * @return true면 작업 계속 진행 가능.
     *         false면 작업 중단됨 (세션이 구버전이면 노이즈 무시, 세션은 같으나 UID가 달라졌거나 로그아웃되었으면 Loading 해제 및 FormError 처리).
     */
    private fun validateSessionAndUid(token: Long, targetUid: String): Boolean {
        if (token != sessionToken) {
            // 오래된 무효화된 요청: 현재 세션 상태나 flags를 무조건 터치하지 않고 silent return
            return false
        }
        val currentUid = getCurrentAuthUid()
        if (currentUid.isNullOrBlank() || currentUid != targetUid) {
            // 현재 활성 토큰 요청이지만 UID가 달라졌거나 로그아웃됨:
            // Loading 락 해제 및 재시도 가능한 상태로 안전 복구
            isSignUpInProgress = false
            _uiState.value = SignUpUiState.FormError(R.string.signup_error_generic)
            return false
        }
        return true
    }

    fun performSignUp(
        nicknameRaw: String,
        emailRaw: String,
        passwordRaw: String,
        confirmPasswordRaw: String,
        isTermsChecked: Boolean,
    ) {
        if (_uiState.value is SignUpUiState.Loading || isSignUpInProgress || isLogoutInProgress) return

        val nickname = nicknameRaw.trim()
        val email = emailRaw.trim()
        val password = passwordRaw
        val confirmPassword = confirmPasswordRaw

        val isNicknameEmpty = nickname.isEmpty()
        val isEmailEmpty = email.isEmpty()
        val isPasswordEmpty = password.isEmpty()
        val isConfirmPasswordEmpty = confirmPassword.isEmpty()

        // 1단계: 필수 입력란 미입력 우선 검사
        if (isNicknameEmpty || isEmailEmpty || isPasswordEmpty || isConfirmPasswordEmpty) {
            _uiState.value = SignUpUiState.InputError(
                nicknameErrorResId = if (isNicknameEmpty) R.string.signup_error_empty_nickname else null,
                emailErrorResId = if (isEmailEmpty) R.string.signup_error_empty_email else null,
                passwordErrorResId = if (isPasswordEmpty) R.string.signup_error_empty_password else null,
                confirmPasswordErrorResId = if (isConfirmPasswordEmpty) R.string.signup_error_empty_confirm_password else null,
                termsErrorResId = null,
            )
            return
        }

        // 2단계: 형식, 길이, 불일치, 약관 동의 검사
        val isEmailInvalid = !isEmailValid(email)
        val isPasswordTooShort = password.length < 8
        val isPasswordMismatch = (password != confirmPassword)
        val isTermsNotChecked = !isTermsChecked

        if (isEmailInvalid || isPasswordTooShort || isPasswordMismatch || isTermsNotChecked) {
            _uiState.value = SignUpUiState.InputError(
                nicknameErrorResId = null,
                emailErrorResId = if (isEmailInvalid) R.string.signup_error_invalid_email else null,
                passwordErrorResId = if (isPasswordTooShort) R.string.signup_error_password_too_short else null,
                confirmPasswordErrorResId = if (isPasswordMismatch) R.string.signup_error_password_mismatch else null,
                termsErrorResId = if (isTermsNotChecked) R.string.signup_error_terms_required else null,
            )
            return
        }

        _uiState.value = SignUpUiState.Loading
        isSignUpInProgress = true
        isSignUpCompleted = false
        completedUid = null
        pendingErrorContext = null
        _events.value = null
        val token = ++sessionToken

        viewModelScope.launch {
            try {
                var targetUser: FirebaseUser? = null
                var uid: String? = null
                var userEmail: String? = null
                var isExistingAuthAccount = false

                try {
                    if (authUserProvider != null) {
                        val resultPair = authUserProvider.invoke(email, password, true)
                        uid = resultPair?.first
                        userEmail = resultPair?.second ?: email
                    } else if (createUserAction != null) {
                        targetUser = createUserAction.invoke(email, password)
                        uid = targetUser?.uid
                        userEmail = targetUser?.email ?: email
                    } else {
                        val authResult = firebaseAuth.createUserWithEmailAndPassword(email, password).await()
                        targetUser = authResult.user
                        uid = targetUser?.uid
                        userEmail = targetUser?.email ?: email
                    }
                } catch (e: FirebaseAuthUserCollisionException) {
                    try {
                        if (authUserProvider != null) {
                            val resultPair = authUserProvider.invoke(email, password, false)
                            uid = resultPair?.first
                            userEmail = resultPair?.second ?: email
                            isExistingAuthAccount = true
                        } else if (signInAction != null) {
                            targetUser = signInAction.invoke(email, password)
                            uid = targetUser?.uid
                            userEmail = targetUser?.email ?: email
                            isExistingAuthAccount = true
                        } else {
                            val loginResult = firebaseAuth.signInWithEmailAndPassword(email, password).await()
                            targetUser = loginResult.user
                            uid = targetUser?.uid
                            userEmail = targetUser?.email ?: email
                            isExistingAuthAccount = true
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (token != sessionToken) return@launch
                        isSignUpInProgress = false
                        if (isCredentialFailure(e)) {
                            _uiState.value = SignUpUiState.FormError(R.string.signup_error_email_already_in_use)
                        } else {
                            _uiState.value = SignUpUiState.FormError(R.string.signup_error_generic)
                        }
                        return@launch
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    if (token != sessionToken) return@launch
                    isSignUpInProgress = false
                    _uiState.value = SignUpUiState.FormError(R.string.signup_error_generic)
                    return@launch
                }

                if (token != sessionToken) return@launch

                if (!uid.isNullOrBlank()) {
                    performProfileStepInternal(targetUser, uid, nickname, userEmail, isExistingAuthAccount, token)
                } else {
                    isSignUpInProgress = false
                    _uiState.value = SignUpUiState.FormError(R.string.signup_error_generic)
                }
            } catch (e: CancellationException) {
                isSignUpInProgress = false
                throw e
            } catch (_: Exception) {
                if (token != sessionToken) return@launch
                isSignUpInProgress = false
                _uiState.value = SignUpUiState.FormError(R.string.signup_error_generic)
            }
        }
    }

    fun retryProfileStep() {
        val ctx = pendingErrorContext
        if (ctx != null) {
            retryProfileStep(ctx.user, ctx.uid, ctx.nickname, ctx.email, ctx.isExistingAuthAccount)
        } else {
            _uiState.value = SignUpUiState.FormError(R.string.signup_error_generic)
        }
    }

    fun retryProfileStep(
        user: FirebaseUser?,
        uid: String,
        nickname: String,
        email: String,
        isExistingAuthAccount: Boolean,
    ) {
        if (_uiState.value is SignUpUiState.Loading || uid.isBlank()) return

        val activeUid = getCurrentAuthUid()
        if (activeUid == null || activeUid != uid) {
            _uiState.value = SignUpUiState.FormError(R.string.signup_error_generic)
            return
        }

        pendingErrorContext = PendingErrorContext(user, uid, nickname, email, isExistingAuthAccount)

        _uiState.value = SignUpUiState.Loading
        isSignUpInProgress = true
        isSignUpCompleted = false
        completedUid = null
        _events.value = null
        val token = ++sessionToken

        performProfileStepInternal(user, uid, nickname, email, isExistingAuthAccount, token)
    }

    private fun performProfileStepInternal(
        user: FirebaseUser?,
        uid: String,
        nickname: String,
        email: String,
        isExistingAuthAccount: Boolean,
        token: Long,
    ) {
        if (uid.isBlank()) return

        viewModelScope.launch {
            try {
                if (!validateSessionAndUid(token, uid)) return@launch

                val existingProfile = try {
                    userRepo.getUserProfile(uid, source = Source.SERVER)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    if (!validateSessionAndUid(token, uid)) return@launch
                    isSignUpInProgress = false
                    pendingErrorContext = PendingErrorContext(user, uid, nickname, email, isExistingAuthAccount)
                    _uiState.value = SignUpUiState.Idle
                    _events.value = Event(
                        SignUpEvent.ShowProfileLoadError(
                            user = user,
                            uid = uid,
                            nickname = nickname,
                            email = email,
                            isExistingAuthAccount = isExistingAuthAccount,
                        )
                    )
                    return@launch
                }

                if (!validateSessionAndUid(token, uid)) return@launch

                if (existingProfile != null) {
                    isSignUpInProgress = false
                    _uiState.value = SignUpUiState.Idle
                    if (existingProfile.nickname.isNotBlank()) {
                        isSignUpCompleted = true
                        completedUid = uid
                        _events.value = Event(
                            SignUpEvent.ShowSignUpComplete(
                                isExistingAccount = true,
                                isProfileRestored = false,
                            )
                        )
                    } else {
                        _events.value = Event(SignUpEvent.ShowProfileCorrupted)
                    }
                } else {
                    try {
                        userRepo.createUserProfile(
                            uid = uid,
                            nickname = nickname,
                            email = email,
                        )
                        if (!validateSessionAndUid(token, uid)) return@launch

                        isSignUpInProgress = false
                        isSignUpCompleted = true
                        completedUid = uid
                        _uiState.value = SignUpUiState.Idle
                        _events.value = Event(
                            SignUpEvent.ShowSignUpComplete(
                                isExistingAccount = isExistingAuthAccount,
                                isProfileRestored = isExistingAuthAccount,
                            )
                        )
                    } catch (e: CancellationException) {
                        isSignUpInProgress = false
                        throw e
                    } catch (_: Exception) {
                        if (!validateSessionAndUid(token, uid)) return@launch
                        isSignUpInProgress = false
                        pendingErrorContext = PendingErrorContext(user, uid, nickname, email, isExistingAuthAccount)
                        _uiState.value = SignUpUiState.Idle
                        _events.value = Event(
                            SignUpEvent.ShowProfileSaveError(
                                user = user,
                                uid = uid,
                                nickname = nickname,
                                email = email,
                                isExistingAuthAccount = isExistingAuthAccount,
                            )
                        )
                    }
                }
            } catch (e: CancellationException) {
                if (token == sessionToken) {
                    isSignUpInProgress = false
                }
                throw e
            } catch (_: Exception) {
                if (!validateSessionAndUid(token, uid)) return@launch
                isSignUpInProgress = false
                _uiState.value = SignUpUiState.FormError(R.string.signup_error_generic)
            }
        }
    }

    fun retryCompletionCheck() {
        if (completedUid.isNullOrBlank()) {
            _uiState.value = SignUpUiState.FormError(R.string.signup_error_generic)
            return
        }
        isSignUpCompleted = true
        onGetStartedClicked()
    }

    fun onGetStartedClicked() {
        if (!isSignUpCompleted || completedUid.isNullOrBlank()) {
            isSignUpCompleted = false
            completedUid = null
            _events.value = null
            _uiState.value = SignUpUiState.FormError(R.string.signup_error_generic)
            return
        }

        val targetUid = completedUid.orEmpty()
        val activeUid = getCurrentAuthUid()

        if (activeUid.isNullOrBlank() || targetUid.isBlank() || activeUid != targetUid) {
            isSignUpCompleted = false
            completedUid = null
            _events.value = null
            _uiState.value = SignUpUiState.FormError(R.string.signup_error_generic)
            return
        }

        if (_uiState.value is SignUpUiState.Loading) return
        _uiState.value = SignUpUiState.Loading
        val token = ++sessionToken

        viewModelScope.launch {
            try {
                if (!validateSessionAndUid(token, targetUid)) return@launch

                val profile = userRepo.getUserProfile(targetUid, source = Source.SERVER)
                if (!validateSessionAndUid(token, targetUid)) return@launch

                _uiState.value = SignUpUiState.Idle

                if (profile != null && profile.nickname.isNotBlank()) {
                    isSignUpCompleted = false
                    completedUid = null
                    _events.value = Event(SignUpEvent.NavigateToRoomList)
                } else if (profile != null && profile.nickname.isBlank()) {
                    isSignUpCompleted = false
                    completedUid = null
                    _events.value = Event(SignUpEvent.ShowProfileCorrupted)
                } else {
                    _events.value = Event(SignUpEvent.ShowCompletionCheckError(targetUid))
                }
            } catch (e: CancellationException) {
                if (token == sessionToken) {
                    _uiState.value = SignUpUiState.Idle
                }
                throw e
            } catch (_: Exception) {
                if (!validateSessionAndUid(token, targetUid)) return@launch
                _uiState.value = SignUpUiState.Idle
                _events.value = Event(SignUpEvent.ShowCompletionCheckError(targetUid))
            }
        }
    }

    private var isLogoutInProgress = false

    fun signOut() {
        performLogout()
    }

    fun performLogout(tokenRepo: com.pintrip.app.data.repository.DeviceTokenRepository? = null, onComplete: (() -> Unit)? = null) {
        if (isLogoutInProgress) return
        isLogoutInProgress = true

        val token = ++sessionToken
        isSignUpInProgress = false
        isSignUpCompleted = false
        completedUid = null
        pendingErrorContext = null
        _events.value = null
        _uiState.value = SignUpUiState.Idle

        val previousUid = getCurrentAuthUid()
        val targetSessionGen = com.pintrip.app.data.repository.DeviceTokenRepository.invalidateSession()

        viewModelScope.launch {
            try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    try {
                        if (!previousUid.isNullOrBlank() && tokenRepo != null) {
                            try {
                                tokenRepo.cleanupTokenOnLogout(previousUid)
                            } catch (_: Exception) {
                                // cleanup error or cancellation should not block sign out
                            }
                        }
                        if (token == sessionToken && getCurrentAuthUid() == previousUid) {
                            try { firebaseAuth.signOut() } catch (_: Exception) {}
                        }
                    } finally {
                        if (com.pintrip.app.data.repository.DeviceTokenRepository.currentSessionGeneration() == targetSessionGen) {
                            com.pintrip.app.data.repository.DeviceTokenRepository.setLoggingOut(false)
                        }
                        if (token == sessionToken) {
                            _uiState.value = SignUpUiState.Idle
                        }
                        onComplete?.invoke()
                    }
                }
            } finally {
                isLogoutInProgress = false
            }
        }
    }

    fun clearNicknameError() {
        clearFieldErrors { it.copy(nicknameErrorResId = null) }
    }

    fun clearEmailError() {
        clearFieldErrors { it.copy(emailErrorResId = null) }
    }

    fun clearPasswordError(passwordCurrent: String = "", confirmPasswordCurrent: String = "") {
        clearFieldErrors { current ->
            val isMismatchError = (current.confirmPasswordErrorResId == R.string.signup_error_password_mismatch)
            val isNowMatchingOrEmpty = (passwordCurrent == confirmPasswordCurrent || confirmPasswordCurrent.isEmpty())
            val newConfirmError = if (isMismatchError && isNowMatchingOrEmpty) null else current.confirmPasswordErrorResId

            current.copy(
                passwordErrorResId = null,
                confirmPasswordErrorResId = newConfirmError,
            )
        }
    }

    fun clearConfirmPasswordError() {
        clearFieldErrors { it.copy(confirmPasswordErrorResId = null) }
    }

    fun clearTermsError() {
        clearFieldErrors { it.copy(termsErrorResId = null) }
    }

    private inline fun clearFieldErrors(update: (SignUpUiState.InputError) -> SignUpUiState.InputError) {
        val current = _uiState.value
        if (current is SignUpUiState.InputError) {
            val updated = update(current)
            if (updated.nicknameErrorResId == null &&
                updated.emailErrorResId == null &&
                updated.passwordErrorResId == null &&
                updated.confirmPasswordErrorResId == null &&
                updated.termsErrorResId == null
            ) {
                _uiState.value = SignUpUiState.Idle
            } else {
                _uiState.value = updated
            }
        } else if (current is SignUpUiState.FormError) {
            _uiState.value = SignUpUiState.Idle
        }
    }

    fun clearError() {
        if (_uiState.value is SignUpUiState.InputError || _uiState.value is SignUpUiState.FormError) {
            _uiState.value = SignUpUiState.Idle
        }
    }

    private fun isCredentialFailure(e: Exception): Boolean {
        if (e is FirebaseAuthException) {
            return when (e.errorCode) {
                "ERROR_INVALID_EMAIL",
                "ERROR_WRONG_PASSWORD",
                "ERROR_USER_NOT_FOUND",
                "ERROR_INVALID_CREDENTIAL" -> true
                else -> false
            }
        }
        return false
    }

    private fun isEmailValid(email: String): Boolean {
        return try {
            Patterns.EMAIL_ADDRESS.matcher(email).matches()
        } catch (_: Exception) {
            email.contains("@") && email.contains(".")
        }
    }
}

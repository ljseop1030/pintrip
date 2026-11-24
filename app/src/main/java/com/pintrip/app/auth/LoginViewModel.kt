package com.pintrip.app.auth

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.Source
import com.pintrip.app.R
import com.pintrip.app.data.repository.UserRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * 로그인 화면 ViewModel.
 *
 * 이메일/비밀번호 로그인, 프로필 조회 및 복구 비동기 로직을 처리하며
 * 예외를 감싸 단순화된 UI 상태([LoginUiState])로 노출합니다.
 */
class LoginViewModel(
    private val auth: FirebaseAuth? = null,
    private val userRepository: UserRepository? = null,
    private val signInAction: (suspend (String, String) -> FirebaseUser?)? = null,
) : ViewModel() {

    private val firebaseAuth: FirebaseAuth
        get() = auth ?: FirebaseAuth.getInstance()

    private val userRepo: UserRepository
        get() = userRepository ?: UserRepository(auth = firebaseAuth)

    private val _uiState = MutableLiveData<LoginUiState>(LoginUiState.Idle)
    val uiState: LiveData<LoginUiState> get() = _uiState

    private var isSessionChecked = false
    private var sessionToken = 0L

    /**
     * 사용자가 이미 로그인되어 있는지 체크하고 프로필 검증 진행
     */
    fun checkExistingSession() {
        if (isSessionChecked) return
        isSessionChecked = true

        val currentUser = try { firebaseAuth.currentUser } catch (_: Exception) { null }
        if (currentUser != null) {
            checkProfileAndNavigate(currentUser)
        } else {
            _uiState.value = LoginUiState.Idle
        }
    }

    /**
     * 이메일/비밀번호 정식 로그인 수행
     */
    fun performLogin(emailRaw: String, passwordRaw: String) {
        if (_uiState.value is LoginUiState.Loading) return

        val email = emailRaw.trim()
        val isEmailEmpty = email.isEmpty()
        val isPasswordEmpty = passwordRaw.isEmpty()

        if (isEmailEmpty || isPasswordEmpty) {
            _uiState.value = LoginUiState.InputError(
                emailErrorResId = if (isEmailEmpty) R.string.login_error_empty_email else null,
                passwordErrorResId = if (isPasswordEmpty) R.string.login_error_empty_password else null,
            )
            return
        }

        _uiState.value = LoginUiState.Loading
        val token = ++sessionToken

        viewModelScope.launch {
            try {
                val user = if (signInAction != null) {
                    signInAction.invoke(email, passwordRaw)
                } else {
                    val authResult = firebaseAuth.signInWithEmailAndPassword(email, passwordRaw).await()
                    authResult.user
                }
                if (token != sessionToken) return@launch

                if (user != null) {
                    checkProfileAndNavigateInternal(user, token)
                } else {
                    _uiState.value = LoginUiState.FormError(R.string.login_error_generic)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (token != sessionToken) return@launch
                if (isInvalidCredentialsException(e)) {
                    _uiState.value = LoginUiState.FormError(R.string.login_error_check_credentials)
                } else {
                    _uiState.value = LoginUiState.FormError(R.string.login_error_generic)
                }
            }
        }
    }

    /**
     * 로그인 성공 후 Firestore 프로필 확인 및 이동 경로 결정 (공개 API / 재시도용)
     */
    fun checkProfileAndNavigate(user: FirebaseUser) {
        if (_uiState.value is LoginUiState.Loading && isSessionChecked) return
        val token = sessionToken
        _uiState.value = LoginUiState.Loading
        checkProfileAndNavigateInternal(user, token)
    }

    private fun checkProfileAndNavigateInternal(user: FirebaseUser, token: Long) {
        viewModelScope.launch {
            try {
                val profile = userRepo.getUserProfile(user.uid, source = Source.SERVER)
                if (token != sessionToken) return@launch

                if (profile != null) {
                    if (profile.nickname.isNotBlank()) {
                        _uiState.value = LoginUiState.Success
                    } else {
                        _uiState.value = LoginUiState.ProfileLoadError(user)
                    }
                } else {
                    _uiState.value = LoginUiState.ProfileMissing(user)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (token != sessionToken) return@launch
                _uiState.value = LoginUiState.ProfileLoadError(user)
            }
        }
    }

    /**
     * 누락된 프로필 닉네임 설정 및 생성
     */
    fun saveProfileNickname(user: FirebaseUser, nicknameRaw: String) {
        if (_uiState.value is LoginUiState.Loading) return

        val nickname = nicknameRaw.trim()
        if (nickname.isBlank()) {
            _uiState.value = LoginUiState.ProfileMissing(user)
            return
        }

        _uiState.value = LoginUiState.Loading
        val token = sessionToken

        viewModelScope.launch {
            try {
                userRepo.createUserProfile(
                    uid = user.uid,
                    nickname = nickname,
                    email = user.email.orEmpty(),
                )
                if (token != sessionToken) return@launch

                _uiState.value = LoginUiState.Success
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (token != sessionToken) return@launch
                _uiState.value = LoginUiState.ProfileSaveError(user)
            }
        }
    }

    /**
     * 로그아웃 (이전 수신 응답을 무효화함)
     */
    fun signOut() {
        sessionToken++
        try { firebaseAuth.signOut() } catch (_: Exception) {}
        _uiState.value = LoginUiState.Idle
    }

    /**
     * 이메일 입력 필드 수정 시 이메일 에러 및 폼 에러 제거
     */
    fun clearEmailError() {
        val current = _uiState.value
        if (current is LoginUiState.InputError) {
            val newPasswordError = current.passwordErrorResId
            if (newPasswordError == null) {
                _uiState.value = LoginUiState.Idle
            } else {
                _uiState.value = LoginUiState.InputError(emailErrorResId = null, passwordErrorResId = newPasswordError)
            }
        } else if (current is LoginUiState.FormError) {
            _uiState.value = LoginUiState.Idle
        }
    }

    /**
     * 비밀번호 입력 필드 수정 시 비밀번호 에러 및 폼 에러 제거
     */
    fun clearPasswordError() {
        val current = _uiState.value
        if (current is LoginUiState.InputError) {
            val newEmailError = current.emailErrorResId
            if (newEmailError == null) {
                _uiState.value = LoginUiState.Idle
            } else {
                _uiState.value = LoginUiState.InputError(emailErrorResId = newEmailError, passwordErrorResId = null)
            }
        } else if (current is LoginUiState.FormError) {
            _uiState.value = LoginUiState.Idle
        }
    }

    /**
     * 전체 에러 초기화
     */
    fun clearError() {
        if (_uiState.value is LoginUiState.InputError || _uiState.value is LoginUiState.FormError) {
            _uiState.value = LoginUiState.Idle
        }
    }

    private fun isInvalidCredentialsException(e: Exception): Boolean {
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
}

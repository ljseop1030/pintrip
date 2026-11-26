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
import com.pintrip.app.ui.common.Event
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * 로그인 화면 ViewModel.
 *
 * 이메일/비밀번호 로그인 처리 및 로그인 후 Firestore 프로필(`users/{uid}`) 상태
 * (정상, 누락, 손상, 조회 실패)에 따른 navigation 방향을 관리합니다.
 */
class LoginViewModel(
    private val auth: FirebaseAuth? = null,
    private val userRepository: UserRepository? = null,
    private val signInAction: (suspend (String, String) -> FirebaseUser?)? = null,
    private val currentUidProvider: (() -> String?)? = null,
) : ViewModel() {

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

    private val _uiState = MutableLiveData<LoginUiState>(LoginUiState.Idle)
    val uiState: LiveData<LoginUiState> get() = _uiState

    private val _events = MutableLiveData<Event<LoginEvent>?>()
    val events: LiveData<Event<LoginEvent>?> get() = _events

    private var isSessionChecked = false
    private var sessionToken = 0L

    var pendingUser: FirebaseUser? = null
        private set
    var pendingUid: String? = null
        private set

    /**
     * 앱 시작 시 기존 Auth 세션 자동 확인
     */
    fun checkExistingSession() {
        if (isSessionChecked) return
        val current = try { firebaseAuth.currentUser } catch (_: Exception) { null }
        if (current != null) {
            isSessionChecked = true
            checkProfileAndNavigate(current)
        }
    }

    /**
     * 이메일/비밀번호 로그인 수행
     */
    fun performLogin(emailRaw: String, passwordRaw: String) {
        if (_uiState.value is LoginUiState.Loading) return

        val email = emailRaw.trim()
        val password = passwordRaw

        val isEmailEmpty = email.isEmpty()
        val isPasswordEmpty = password.isEmpty()

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
                val user: FirebaseUser? = if (signInAction != null) {
                    signInAction.invoke(email, password)
                } else {
                    val authResult = firebaseAuth.signInWithEmailAndPassword(email, password).await()
                    authResult.user
                }

                if (token != sessionToken) return@launch

                if (user != null) {
                    isSessionChecked = true
                    val uid = user.uid
                    pendingUser = user
                    pendingUid = uid
                    checkProfileAndNavigateInternal(user, uid, token)
                } else {
                    _uiState.value = LoginUiState.FormError(R.string.login_error_check_credentials)
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
    fun checkProfileAndNavigate(user: FirebaseUser?, uidOverride: String? = null) {
        if (_uiState.value is LoginUiState.Loading && isSessionChecked) return
        val uid = uidOverride ?: user?.uid ?: pendingUid.orEmpty()
        if (uid.isBlank()) {
            _uiState.value = LoginUiState.FormError(R.string.login_error_generic)
            return
        }

        val activeUid = getCurrentAuthUid()
        if (activeUid.isNullOrBlank() || activeUid != uid) {
            pendingUser = null
            pendingUid = null
            _uiState.value = LoginUiState.FormError(R.string.login_error_generic)
            return
        }

        pendingUser = user ?: pendingUser
        pendingUid = uid

        val token = sessionToken
        _uiState.value = LoginUiState.Loading
        checkProfileAndNavigateInternal(user ?: pendingUser, uid, token)
    }

    fun retryProfileCheck() {
        val uid = pendingUid ?: pendingUser?.uid.orEmpty()
        if (uid.isNotBlank()) {
            checkProfileAndNavigate(pendingUser, uid)
        } else {
            _uiState.value = LoginUiState.FormError(R.string.login_error_generic)
        }
    }

    private fun checkProfileAndNavigateInternal(user: FirebaseUser?, uid: String, token: Long) {
        viewModelScope.launch {
            try {
                val activeUid = getCurrentAuthUid()
                if (activeUid.isNullOrBlank() || activeUid != uid) {
                    if (token != sessionToken) return@launch
                    pendingUser = null
                    pendingUid = null
                    _uiState.value = LoginUiState.FormError(R.string.login_error_generic)
                    return@launch
                }

                val profile = userRepo.getUserProfile(uid, source = Source.SERVER)
                if (token != sessionToken) return@launch

                val currentActiveUid = getCurrentAuthUid()
                if (currentActiveUid.isNullOrBlank() || currentActiveUid != uid) {
                    pendingUser = null
                    pendingUid = null
                    _uiState.value = LoginUiState.FormError(R.string.login_error_generic)
                    return@launch
                }

                _uiState.value = LoginUiState.Idle

                val targetUser = user ?: pendingUser

                if (profile != null) {
                    if (profile.nickname.isNotBlank()) {
                        _events.value = Event(LoginEvent.NavigateToRoomList)
                    } else {
                        _events.value = Event(LoginEvent.ShowProfileCorrupted(targetUser))
                    }
                } else {
                    _events.value = Event(LoginEvent.ShowProfileMissing(targetUser))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (token != sessionToken) return@launch
                val currentActiveUid = getCurrentAuthUid()
                if (currentActiveUid.isNullOrBlank() || currentActiveUid != uid) {
                    pendingUser = null
                    pendingUid = null
                    _uiState.value = LoginUiState.FormError(R.string.login_error_generic)
                    return@launch
                }
                _uiState.value = LoginUiState.Idle
                _events.value = Event(LoginEvent.ShowProfileLoadError(user ?: pendingUser))
            }
        }
    }

    /**
     * 누락된 프로필 닉네임 설정 및 생성
     */
    fun saveProfileNickname(nicknameRaw: String) {
        saveProfileNickname(pendingUser, nicknameRaw, pendingUid)
    }

    fun saveProfileNickname(user: FirebaseUser?, nicknameRaw: String, uidOverride: String? = null) {
        if (_uiState.value is LoginUiState.Loading) return

        val uid = uidOverride ?: user?.uid ?: pendingUid.orEmpty()
        if (uid.isBlank()) {
            _uiState.value = LoginUiState.FormError(R.string.login_error_generic)
            return
        }

        val activeUid = getCurrentAuthUid()
        if (activeUid.isNullOrBlank() || activeUid != uid) {
            pendingUser = null
            pendingUid = null
            _uiState.value = LoginUiState.FormError(R.string.login_error_generic)
            return
        }

        val nickname = nicknameRaw.trim()
        if (nickname.isBlank()) {
            _events.value = Event(LoginEvent.ShowProfileMissing(user ?: pendingUser))
            return
        }

        pendingUser = user ?: pendingUser
        pendingUid = uid

        _uiState.value = LoginUiState.Loading
        val token = sessionToken

        viewModelScope.launch {
            try {
                userRepo.createUserProfile(
                    uid = uid,
                    nickname = nickname,
                    email = (user ?: pendingUser)?.email.orEmpty(),
                )
                if (token != sessionToken) return@launch

                val currentActiveUid = getCurrentAuthUid()
                if (currentActiveUid.isNullOrBlank() || currentActiveUid != uid) {
                    pendingUser = null
                    pendingUid = null
                    _uiState.value = LoginUiState.FormError(R.string.login_error_generic)
                    return@launch
                }

                _uiState.value = LoginUiState.Idle
                _events.value = Event(LoginEvent.NavigateToRoomList)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (token != sessionToken) return@launch
                val currentActiveUid = getCurrentAuthUid()
                if (currentActiveUid.isNullOrBlank() || currentActiveUid != uid) {
                    pendingUser = null
                    pendingUid = null
                    _uiState.value = LoginUiState.FormError(R.string.login_error_generic)
                    return@launch
                }
                _uiState.value = LoginUiState.Idle
                _events.value = Event(LoginEvent.ShowProfileSaveError(user ?: pendingUser))
            }
        }
    }

    private var isLogoutInProgress = false

    /**
     * 로그아웃 처리 및 세션 토큰 무효화
     */
    fun signOut() {
        performLogout()
    }

    fun performLogout(tokenRepo: com.pintrip.app.data.repository.DeviceTokenRepository? = null, onComplete: (() -> Unit)? = null) {
        if (isLogoutInProgress) return
        isLogoutInProgress = true

        val token = ++sessionToken
        isSessionChecked = false
        pendingUser = null
        pendingUid = null
        _events.value = null
        _uiState.value = LoginUiState.Idle

        val previousUid = getCurrentAuthUid()
        val targetSessionGen = com.pintrip.app.data.repository.DeviceTokenRepository.invalidateSession()

        viewModelScope.launch {
            try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    try {
                        if (!previousUid.isNullOrBlank() && tokenRepo != null) {
                            try {
                                tokenRepo.cleanupTokenOnLogout(previousUid)
                            } catch (e: Exception) {
                                // cleanup error or cancellation should not block sign out
                            }
                        }
                        if (getCurrentAuthUid() == previousUid) {
                            try { firebaseAuth.signOut() } catch (_: Exception) {}
                        }
                    } finally {
                        if (com.pintrip.app.data.repository.DeviceTokenRepository.currentSessionGeneration() == targetSessionGen) {
                            com.pintrip.app.data.repository.DeviceTokenRepository.setLoggingOut(false)
                        }
                        if (token == sessionToken) {
                            _uiState.value = LoginUiState.Idle
                        }
                        onComplete?.invoke()
                    }
                }
            } finally {
                isLogoutInProgress = false
            }
        }
    }

    fun clearEmailError() {
        clearFieldErrors { it.copy(emailErrorResId = null) }
    }

    fun clearPasswordError() {
        clearFieldErrors { it.copy(passwordErrorResId = null) }
    }

    private inline fun clearFieldErrors(update: (LoginUiState.InputError) -> LoginUiState.InputError) {
        val current = _uiState.value
        if (current is LoginUiState.InputError) {
            val updated = update(current)
            if (updated.emailErrorResId == null && updated.passwordErrorResId == null) {
                _uiState.value = LoginUiState.Idle
            } else {
                _uiState.value = updated
            }
        } else if (current is LoginUiState.FormError) {
            _uiState.value = LoginUiState.Idle
        }
    }

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

package com.pintrip.app.auth

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.pintrip.app.R
import com.pintrip.app.data.model.User
import com.pintrip.app.data.repository.UserRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SignUpViewModelTest {

    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeUserRepository(
        private val profileToReturn: User? = null,
        private val shouldThrowOnGet: Boolean = false,
        private val shouldThrowOnCreate: Boolean = false,
    ) : UserRepository(null, null) {
        var getUserProfileCallCount = 0
        var createUserProfileCallCount = 0

        override suspend fun getUserProfile(uid: String, source: com.google.firebase.firestore.Source): User? {
            getUserProfileCallCount++
            if (shouldThrowOnGet) throw RuntimeException("Firestore get error")
            return profileToReturn
        }

        override suspend fun createUserProfile(uid: String, nickname: String, email: String, avatarColor: String) {
            createUserProfileCallCount++
            if (shouldThrowOnCreate) throw RuntimeException("Firestore create error")
        }
    }

    @Test
    fun `performSignUp with all empty inputs sets InputError for all mandatory fields`() {
        val viewModel = SignUpViewModel()

        viewModel.performSignUp(
            nicknameRaw = "",
            emailRaw = "",
            passwordRaw = "",
            confirmPasswordRaw = "",
            isTermsChecked = false,
        )

        val state = viewModel.uiState.value
        assertTrue(state is SignUpUiState.InputError)
        val inputError = state as SignUpUiState.InputError
        assertEquals(R.string.signup_error_empty_nickname, inputError.nicknameErrorResId)
        assertEquals(R.string.signup_error_empty_email, inputError.emailErrorResId)
        assertEquals(R.string.signup_error_empty_password, inputError.passwordErrorResId)
        assertEquals(R.string.signup_error_empty_confirm_password, inputError.confirmPasswordErrorResId)
        assertNull(inputError.termsErrorResId)
    }

    @Test
    fun `performSignUp checks format length mismatch and terms when fields are non-empty`() {
        val viewModel = SignUpViewModel()

        viewModel.performSignUp(
            nicknameRaw = "Minji",
            emailRaw = "invalid-email",
            passwordRaw = "pass",
            confirmPasswordRaw = "different-pass",
            isTermsChecked = false,
        )

        val state = viewModel.uiState.value
        assertTrue(state is SignUpUiState.InputError)
        val inputError = state as SignUpUiState.InputError
        assertNull(inputError.nicknameErrorResId)
        assertEquals(R.string.signup_error_invalid_email, inputError.emailErrorResId)
        assertEquals(R.string.signup_error_password_too_short, inputError.passwordErrorResId)
        assertEquals(R.string.signup_error_password_mismatch, inputError.confirmPasswordErrorResId)
        assertEquals(R.string.signup_error_terms_required, inputError.termsErrorResId)
    }

    @Test
    fun `clearNicknameError clears nickname error independently`() {
        val viewModel = SignUpViewModel()
        viewModel.performSignUp("", "", "", "", false)

        viewModel.clearNicknameError()

        val state = viewModel.uiState.value
        assertTrue(state is SignUpUiState.InputError)
        val inputError = state as SignUpUiState.InputError
        assertNull(inputError.nicknameErrorResId)
        assertEquals(R.string.signup_error_empty_email, inputError.emailErrorResId)
    }

    @Test
    fun `clearPasswordError with matching confirm password clears mismatch error`() {
        val viewModel = SignUpViewModel()
        viewModel.performSignUp("Minji", "user@example.com", "password123", "password456", true)

        val initialError = viewModel.uiState.value as SignUpUiState.InputError
        assertEquals(R.string.signup_error_password_mismatch, initialError.confirmPasswordErrorResId)

        // Clear password error when password is modified to match confirm password
        viewModel.clearPasswordError(passwordCurrent = "password456", confirmPasswordCurrent = "password456")

        assertEquals(SignUpUiState.Idle, viewModel.uiState.value)
    }

    @Test
    fun `clearPasswordError with empty confirm password preserves empty confirm error`() {
        val viewModel = SignUpViewModel()
        viewModel.performSignUp("Minji", "user@example.com", "", "", true)

        val initialError = viewModel.uiState.value as SignUpUiState.InputError
        assertEquals(R.string.signup_error_empty_password, initialError.passwordErrorResId)
        assertEquals(R.string.signup_error_empty_confirm_password, initialError.confirmPasswordErrorResId)

        viewModel.clearPasswordError(passwordCurrent = "password123", confirmPasswordCurrent = "")

        val state = viewModel.uiState.value as SignUpUiState.InputError
        assertNull(state.passwordErrorResId)
        assertEquals(R.string.signup_error_empty_confirm_password, state.confirmPasswordErrorResId)
    }

    @Test
    fun `performSignUp user collision tries signInAction and completes if existing profile exists`() {
        val fakeRepo = FakeUserRepository(profileToReturn = User(nickname = "Minji", email = "existing@example.com"))
        var createCalled = false
        var signInCalled = false

        val viewModel = SignUpViewModel(
            userRepository = fakeRepo,
            currentUidProvider = { "u123" },
            authUserProvider = { email, _, isCreate ->
                if (isCreate) {
                    createCalled = true
                    throw FirebaseAuthUserCollisionException("ERROR_EMAIL_ALREADY_IN_USE", "Collision")
                } else {
                    signInCalled = true
                    Pair("u123", email)
                }
            },
        )

        viewModel.performSignUp("Minji", "existing@example.com", "password123", "password123", true)
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(createCalled)
        assertTrue(signInCalled)
        val state = viewModel.uiState.value
        assertTrue(state is SignUpUiState.SignUpComplete)
        assertTrue((state as SignUpUiState.SignUpComplete).isExistingAccount)
    }

    @Test
    fun `performSignUp user collision with wrong password maps to signup_error_email_already_in_use`() {
        val viewModel = SignUpViewModel(
            authUserProvider = { _, _, isCreate ->
                if (isCreate) {
                    throw FirebaseAuthUserCollisionException("ERROR_EMAIL_ALREADY_IN_USE", "Collision")
                } else {
                    throw FirebaseAuthInvalidCredentialsException("ERROR_WRONG_PASSWORD", "Wrong password")
                }
            },
        )

        viewModel.performSignUp("Minji", "existing@example.com", "wrongpass123", "wrongpass123", true)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is SignUpUiState.FormError)
        assertEquals(R.string.signup_error_email_already_in_use, (state as SignUpUiState.FormError).messageResId)
    }

    @Test
    fun `performSignUp user collision with ERROR_USER_DISABLED maps to signup_error_generic`() {
        val viewModel = SignUpViewModel(
            authUserProvider = { _, _, isCreate ->
                if (isCreate) {
                    throw FirebaseAuthUserCollisionException("ERROR_EMAIL_ALREADY_IN_USE", "Collision")
                } else {
                    throw FirebaseAuthInvalidUserException("ERROR_USER_DISABLED", "User disabled")
                }
            },
        )

        viewModel.performSignUp("Minji", "disabled@example.com", "password123", "password123", true)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is SignUpUiState.FormError)
        assertEquals(R.string.signup_error_generic, (state as SignUpUiState.FormError).messageResId)
    }

    @Test
    fun `performSignUp user collision with network error maps to signup_error_generic`() {
        val viewModel = SignUpViewModel(
            authUserProvider = { _, _, isCreate ->
                if (isCreate) {
                    throw FirebaseAuthUserCollisionException("ERROR_EMAIL_ALREADY_IN_USE", "Collision")
                } else {
                    throw RuntimeException("Network timeout during collision signin")
                }
            },
        )

        viewModel.performSignUp("Minji", "existing@example.com", "password123", "password123", true)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is SignUpUiState.FormError)
        assertEquals(R.string.signup_error_generic, (state as SignUpUiState.FormError).messageResId)
    }

    @Test
    fun `retryProfileStep with UID mismatch blocks profile operation and emits FormError`() {
        val fakeRepo = FakeUserRepository()
        val viewModel = SignUpViewModel(
            userRepository = fakeRepo,
            currentUidProvider = { "u999_different" },
        )

        viewModel.retryProfileStep(null, "u123_original", "Minji", "user@example.com", false)

        val state = viewModel.uiState.value
        assertTrue(state is SignUpUiState.FormError)
        assertEquals(0, fakeRepo.getUserProfileCallCount)
    }

    @Test
    fun `UID change during profile fetch releases loading and emits FormError`() {
        var activeUid: String? = "u123"
        val fakeRepo = object : UserRepository(null, null) {
            override suspend fun getUserProfile(uid: String, source: com.google.firebase.firestore.Source): User? {
                activeUid = null
                return User(nickname = "Minji", email = "test@example.com")
            }
        }

        val viewModel = SignUpViewModel(
            userRepository = fakeRepo,
            currentUidProvider = { activeUid },
            authUserProvider = { email, _, _ -> Pair("u123", email) },
        )

        viewModel.performSignUp("Minji", "test@example.com", "password123", "password123", true)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is SignUpUiState.FormError)
        assertEquals(R.string.signup_error_generic, (state as SignUpUiState.FormError).messageResId)
    }

    @Test
    fun `UID change during profile save releases loading and does not complete`() {
        var activeUid: String? = "u123"
        val fakeRepo = object : UserRepository(null, null) {
            override suspend fun getUserProfile(uid: String, source: com.google.firebase.firestore.Source): User? {
                return null
            }
            override suspend fun createUserProfile(uid: String, nickname: String, email: String, avatarColor: String) {
                activeUid = null
            }
        }

        val viewModel = SignUpViewModel(
            userRepository = fakeRepo,
            currentUidProvider = { activeUid },
            authUserProvider = { email, _, _ -> Pair("u123", email) },
        )

        viewModel.performSignUp("Minji", "test@example.com", "password123", "password123", true)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is SignUpUiState.FormError)
        assertEquals(R.string.signup_error_generic, (state as SignUpUiState.FormError).messageResId)
    }

    @Test
    fun `retryProfileStep does not call Auth createUserAction again`() {
        var authCallCount = 0
        val fakeRepo = FakeUserRepository(shouldThrowOnGet = true)

        val viewModel = SignUpViewModel(
            userRepository = fakeRepo,
            currentUidProvider = { "u123" },
            authUserProvider = { email, _, _ ->
                authCallCount++
                Pair("u123", email)
            },
        )

        viewModel.performSignUp("Minji", "new@example.com", "password123", "password123", true)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, authCallCount)
        assertTrue(viewModel.uiState.value is SignUpUiState.ProfileLoadError)

        val errorState = viewModel.uiState.value as SignUpUiState.ProfileLoadError

        // Retry profile step
        viewModel.retryProfileStep(errorState.user, errorState.uid, errorState.nickname, errorState.email, errorState.isExistingAuthAccount)
        testDispatcher.scheduler.advanceUntilIdle()

        // Auth count must STILL be 1 (no re-creation of Auth user)
        assertEquals(1, authCallCount)
    }

    @Test
    fun `signOut invalidates in-flight signup response`() {
        val viewModel = SignUpViewModel(
            authUserProvider = { email, _, _ ->
                delay(1000)
                Pair("u123", email)
            },
        )

        viewModel.performSignUp("Minji", "new@example.com", "password123", "password123", true)
        assertEquals(SignUpUiState.Loading, viewModel.uiState.value)

        viewModel.signOut()
        assertEquals(SignUpUiState.Idle, viewModel.uiState.value)

        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(SignUpUiState.Idle, viewModel.uiState.value)
    }
}

package com.pintrip.app.auth

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.google.firebase.auth.FirebaseAuthException
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest {

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

    private class TestAuthException(errorCode: String) : FirebaseAuthException(errorCode, "Test error")

    private class FakeUserRepository(
        private val profileToReturn: User? = null,
        private val shouldThrow: Boolean = false,
    ) : UserRepository(null, null) {
        override suspend fun getUserProfile(uid: String, source: com.google.firebase.firestore.Source): User? {
            if (shouldThrow) throw RuntimeException("Firestore error")
            return profileToReturn
        }
    }

    @Test
    fun `performLogin with empty email and empty password sets InputError for both fields`() {
        val viewModel = LoginViewModel()

        viewModel.performLogin(emailRaw = "", passwordRaw = "")

        val state = viewModel.uiState.value
        assertTrue(state is LoginUiState.InputError)
        val inputError = state as LoginUiState.InputError
        assertEquals(R.string.login_error_empty_email, inputError.emailErrorResId)
        assertEquals(R.string.login_error_empty_password, inputError.passwordErrorResId)
    }

    @Test
    fun `performLogin with empty email only sets InputError for email field`() {
        val viewModel = LoginViewModel()

        viewModel.performLogin(emailRaw = "", passwordRaw = "password123")

        val state = viewModel.uiState.value
        assertTrue(state is LoginUiState.InputError)
        val inputError = state as LoginUiState.InputError
        assertEquals(R.string.login_error_empty_email, inputError.emailErrorResId)
        assertEquals(null, inputError.passwordErrorResId)
    }

    @Test
    fun `performLogin with empty password only sets InputError for password field`() {
        val viewModel = LoginViewModel()

        viewModel.performLogin(emailRaw = "user@example.com", passwordRaw = "")

        val state = viewModel.uiState.value
        assertTrue(state is LoginUiState.InputError)
        val inputError = state as LoginUiState.InputError
        assertEquals(null, inputError.emailErrorResId)
        assertEquals(R.string.login_error_empty_password, inputError.passwordErrorResId)
    }

    @Test
    fun `clearEmailError clears email error while preserving password error`() {
        val viewModel = LoginViewModel()
        viewModel.performLogin(emailRaw = "", passwordRaw = "")

        assertTrue(viewModel.uiState.value is LoginUiState.InputError)

        viewModel.clearEmailError()

        val state = viewModel.uiState.value
        assertTrue(state is LoginUiState.InputError)
        val inputError = state as LoginUiState.InputError
        assertEquals(null, inputError.emailErrorResId)
        assertEquals(R.string.login_error_empty_password, inputError.passwordErrorResId)
    }

    @Test
    fun `clearPasswordError clears password error while preserving email error`() {
        val viewModel = LoginViewModel()
        viewModel.performLogin(emailRaw = "", passwordRaw = "")

        assertTrue(viewModel.uiState.value is LoginUiState.InputError)

        viewModel.clearPasswordError()

        val state = viewModel.uiState.value
        assertTrue(state is LoginUiState.InputError)
        val inputError = state as LoginUiState.InputError
        assertEquals(R.string.login_error_empty_email, inputError.emailErrorResId)
        assertEquals(null, inputError.passwordErrorResId)
    }

    @Test
    fun `performLogin sets Loading state during request`() {
        var observedLoadingState = false
        lateinit var viewModel: LoginViewModel
        viewModel = LoginViewModel(
            signInAction = { _, _ ->
                if (viewModel.uiState.value is LoginUiState.Loading) {
                    observedLoadingState = true
                }
                null
            },
        )

        viewModel.performLogin("user@example.com", "password123")
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(observedLoadingState)
    }

    @Test
    fun `performLogin with wrong password maps to FormError with check_credentials copy`() {
        val fakeRepo = FakeUserRepository(profileToReturn = User(nickname = "Minji", email = "user@example.com"))
        val viewModel = LoginViewModel(
            userRepository = fakeRepo,
            signInAction = { _, _ -> throw TestAuthException("ERROR_WRONG_PASSWORD") },
        )

        viewModel.performLogin("user@example.com", "wrongpass")
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is LoginUiState.FormError)
        assertEquals(R.string.login_error_check_credentials, (state as LoginUiState.FormError).messageResId)
    }

    @Test
    fun `performLogin with ERROR_USER_DISABLED maps to FormError with generic copy`() {
        val viewModel = LoginViewModel(
            signInAction = { _, _ -> throw TestAuthException("ERROR_USER_DISABLED") },
        )

        viewModel.performLogin("disabled@example.com", "password123")
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is LoginUiState.FormError)
        assertEquals(R.string.login_error_generic, (state as LoginUiState.FormError).messageResId)
    }

    @Test
    fun `performLogin with generic network exception maps to FormError with generic copy`() {
        val viewModel = LoginViewModel(
            signInAction = { _, _ -> throw RuntimeException("Network timeout") },
        )

        viewModel.performLogin("user@example.com", "password123")
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is LoginUiState.FormError)
        assertEquals(R.string.login_error_generic, (state as LoginUiState.FormError).messageResId)
    }

    @Test
    fun `checkProfileAndNavigate with valid nickname emits Success`() {
        val fakeRepo = FakeUserRepository(profileToReturn = User(nickname = "Minji", email = "user@example.com"))
        val viewModel = LoginViewModel(userRepository = fakeRepo)

        viewModel.checkProfileAndNavigate(user = null, uidOverride = "u123")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(LoginUiState.Success, viewModel.uiState.value)
    }

    @Test
    fun `checkProfileAndNavigate with blank nickname emits ProfileCorrupted`() {
        val fakeRepo = FakeUserRepository(profileToReturn = User(nickname = "   ", email = "user@example.com"))
        val viewModel = LoginViewModel(userRepository = fakeRepo)

        viewModel.checkProfileAndNavigate(user = null, uidOverride = "u123")
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value is LoginUiState.ProfileCorrupted)
    }

    @Test
    fun `checkProfileAndNavigate with null profile emits ProfileMissing`() {
        val fakeRepo = FakeUserRepository(profileToReturn = null)
        val viewModel = LoginViewModel(userRepository = fakeRepo)

        viewModel.checkProfileAndNavigate(user = null, uidOverride = "u123")
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value is LoginUiState.ProfileMissing)
    }

    @Test
    fun `checkProfileAndNavigate with Firestore exception emits ProfileLoadError`() {
        val fakeRepo = FakeUserRepository(shouldThrow = true)
        val viewModel = LoginViewModel(userRepository = fakeRepo)

        viewModel.checkProfileAndNavigate(user = null, uidOverride = "u123")
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value is LoginUiState.ProfileLoadError)
    }

    @Test
    fun `signOut invalidates in-flight login response`() {
        val viewModel = LoginViewModel(
            signInAction = { _, _ ->
                delay(1000)
                null
            },
        )

        viewModel.performLogin("user@example.com", "password123")
        assertEquals(LoginUiState.Loading, viewModel.uiState.value)

        viewModel.signOut()
        assertEquals(LoginUiState.Idle, viewModel.uiState.value)

        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(LoginUiState.Idle, viewModel.uiState.value)
    }
}

package com.pintrip.app.auth

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.SavedStateHandle
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.pintrip.app.R
import com.pintrip.app.data.model.User
import com.pintrip.app.data.repository.UserRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        var shouldThrowOnGet: Boolean = false,
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
        val event = viewModel.events.value?.getContentIfNotHandled()
        assertTrue(event is SignUpEvent.ShowSignUpComplete)
        assertTrue((event as SignUpEvent.ShowSignUpComplete).isExistingAccount)
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
            override suspend fun getUserProfile(uid: String, source: com.google.firebase.firestore.Source): User {
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
    fun `retryProfileStep without arguments uses preserved pendingErrorContext`() {
        val fakeRepo = FakeUserRepository(shouldThrowOnGet = true)
        val viewModel = SignUpViewModel(
            userRepository = fakeRepo,
            currentUidProvider = { "u123" },
            authUserProvider = { email, _, _ -> Pair("u123", email) },
        )

        viewModel.performSignUp("Minji", "test@example.com", "password123", "password123", true)
        testDispatcher.scheduler.advanceUntilIdle()

        val errorEvent = viewModel.events.value?.getContentIfNotHandled()
        assertTrue(errorEvent is SignUpEvent.ShowProfileLoadError)
        assertEquals("u123", viewModel.pendingErrorContext?.uid)

        // Retry without arguments using preserved pendingErrorContext
        viewModel.retryProfileStep()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(2, fakeRepo.getUserProfileCallCount)
    }

    @Test
    fun `onGetStartedClicked emits NavigateToRoomList event ONCE when signup completed and active UID matches`() {
        val activeUid: String? = "u123"
        val fakeRepo = FakeUserRepository(profileToReturn = User(nickname = "Minji", email = "test@example.com"))
        val viewModel = SignUpViewModel(
            userRepository = fakeRepo,
            currentUidProvider = { activeUid },
            authUserProvider = { email, _, _ -> Pair("u123", email) },
        )

        viewModel.performSignUp("Minji", "test@example.com", "password123", "password123", true)
        testDispatcher.scheduler.advanceUntilIdle()

        val completeEvent = viewModel.events.value?.getContentIfNotHandled()
        assertTrue(completeEvent is SignUpEvent.ShowSignUpComplete)

        // Click Get Started -> verifies profile and emits NavigateToRoomList event
        viewModel.onGetStartedClicked()
        testDispatcher.scheduler.advanceUntilIdle()

        val navEvent1 = viewModel.events.value?.getContentIfNotHandled()
        assertTrue(navEvent1 is SignUpEvent.NavigateToRoomList)

        // Second click onGetStartedClicked does not re-emit navigation event
        viewModel.onGetStartedClicked()
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(viewModel.events.value?.getContentIfNotHandled())
    }

    @Test
    fun `restored SignUpViewModel with SavedStateHandle completed context fetches profile and emits NavigateToRoomList`() {
        val savedStateHandle = SavedStateHandle().apply {
            set("key_completed_uid", "u123")
            set("key_is_signup_completed", true)
        }
        val fakeRepo = FakeUserRepository(profileToReturn = User(nickname = "Minji", email = "test@example.com"))
        val viewModel = SignUpViewModel(
            userRepository = fakeRepo,
            currentUidProvider = { "u123" },
            savedStateHandle = savedStateHandle,
        )

        assertTrue(viewModel.isSignUpCompleted)
        assertEquals("u123", viewModel.completedUid)

        // Click Get Started on restored ViewModel
        viewModel.onGetStartedClicked()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, fakeRepo.getUserProfileCallCount)
        assertEquals(0, fakeRepo.createUserProfileCallCount) // Read-only check: zero profile creations
        val event = viewModel.events.value?.getContentIfNotHandled()
        assertTrue(event is SignUpEvent.NavigateToRoomList)
    }

    @Test
    fun `restored SignUpViewModel with SavedStateHandle completion check throws exception emits ShowCompletionCheckError and retryCompletionCheck succeeds`() {
        val savedStateHandle = SavedStateHandle().apply {
            set("key_completed_uid", "u123")
            set("key_is_signup_completed", true)
        }
        val fakeRepo = FakeUserRepository(profileToReturn = User(nickname = "Minji", email = "test@example.com"), shouldThrowOnGet = true)
        val viewModel = SignUpViewModel(
            userRepository = fakeRepo,
            currentUidProvider = { "u123" },
            savedStateHandle = savedStateHandle,
        )

        viewModel.onGetStartedClicked()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, fakeRepo.getUserProfileCallCount)
        assertEquals(0, fakeRepo.createUserProfileCallCount) // Read-only: zero writes
        val event = viewModel.events.value?.getContentIfNotHandled()
        assertTrue(event is SignUpEvent.ShowCompletionCheckError)
        assertEquals("u123", (event as SignUpEvent.ShowCompletionCheckError).uid)

        // Turn off throw, retry completion check
        fakeRepo.shouldThrowOnGet = false
        viewModel.retryCompletionCheck()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(2, fakeRepo.getUserProfileCallCount)
        assertEquals(0, fakeRepo.createUserProfileCallCount) // Read-only: zero writes throughout!
        val navEvent = viewModel.events.value?.getContentIfNotHandled()
        assertTrue(navEvent is SignUpEvent.NavigateToRoomList)
    }

    @Test
    fun `restored SignUpViewModel with SavedStateHandle completed context when UID mismatches emits FormError`() {
        val savedStateHandle = SavedStateHandle().apply {
            set("key_completed_uid", "u123")
            set("key_is_signup_completed", true)
        }
        val fakeRepo = FakeUserRepository(profileToReturn = User(nickname = "Minji", email = "test@example.com"))
        val viewModel = SignUpViewModel(
            userRepository = fakeRepo,
            currentUidProvider = { "u999_different" },
            savedStateHandle = savedStateHandle,
        )

        // Click Get Started on restored ViewModel when active UID is different
        viewModel.onGetStartedClicked()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(0, fakeRepo.getUserProfileCallCount)
        val state = viewModel.uiState.value
        assertTrue(state is SignUpUiState.FormError)
        assertEquals(R.string.signup_error_generic, (state as SignUpUiState.FormError).messageResId)
    }

    @Test
    fun `onGetStartedClicked when completion context is missing emits FormError`() {
        val viewModel = SignUpViewModel()

        // Call onGetStartedClicked without completed context
        viewModel.onGetStartedClicked()

        val state = viewModel.uiState.value
        assertTrue(state is SignUpUiState.FormError)
        assertEquals(R.string.signup_error_generic, (state as SignUpUiState.FormError).messageResId)
    }

    @Test
    fun `onGetStartedClicked when active UID becomes null or changes to different UID does NOT navigate and emits FormError`() {
        var activeUid: String? = "u123"
        val fakeRepo = FakeUserRepository(profileToReturn = User(nickname = "Minji", email = "test@example.com"))
        val viewModel = SignUpViewModel(
            userRepository = fakeRepo,
            currentUidProvider = { activeUid },
            authUserProvider = { email, _, _ -> Pair("u123", email) },
        )

        viewModel.performSignUp("Minji", "test@example.com", "password123", "password123", true)
        testDispatcher.scheduler.advanceUntilIdle()

        val completeEvent = viewModel.events.value?.getContentIfNotHandled()
        assertTrue(completeEvent is SignUpEvent.ShowSignUpComplete)

        // Active UID becomes null (logged out) or changes
        activeUid = null

        // Click Get Started
        viewModel.onGetStartedClicked()

        val state = viewModel.uiState.value
        assertTrue(state is SignUpUiState.FormError)
        assertEquals(R.string.signup_error_generic, (state as SignUpUiState.FormError).messageResId)
    }

    @Test
    fun `cancellation of previous completion check does not clear new request loading state`() {
        val savedStateHandle = SavedStateHandle().apply {
            set("key_completed_uid", "u123")
            set("key_is_signup_completed", true)
        }

        val deferredA = CompletableDeferred<User?>()
        val deferredB = CompletableDeferred<User?>()
        var getProfileCallCount = 0

        val fakeRepo = object : UserRepository(null, null) {
            override suspend fun getUserProfile(uid: String, source: com.google.firebase.firestore.Source): User? {
                getProfileCallCount++
                return if (getProfileCallCount == 1) {
                    deferredA.await()
                } else {
                    deferredB.await()
                }
            }
        }

        val viewModel = SignUpViewModel(
            userRepository = fakeRepo,
            currentUidProvider = { "u123" },
            savedStateHandle = savedStateHandle,
        )

        // 1. Completion verification A starts and pauses at deferredA
        viewModel.onGetStartedClicked()
        testDispatcher.scheduler.runCurrent()
        assertEquals(1, getProfileCallCount)
        assertEquals(SignUpUiState.Loading, viewModel.uiState.value)

        // 2. signOut invalidates session token A
        viewModel.signOut()
        assertEquals(SignUpUiState.Idle, viewModel.uiState.value)

        // 3. New Request B starts (retryProfileStep) and enters loading state
        viewModel.retryProfileStep(null, "u123", "Minji", "test@example.com", false)
        testDispatcher.scheduler.runCurrent()
        assertEquals(2, getProfileCallCount)
        assertEquals(SignUpUiState.Loading, viewModel.uiState.value)

        // 4. Request A completes with CancellationException
        deferredA.completeExceptionally(CancellationException("Request A cancelled"))
        testDispatcher.scheduler.runCurrent()

        // 5. Verify Request A's cancellation DID NOT reset Request B's Loading state to Idle!
        assertEquals(SignUpUiState.Loading, viewModel.uiState.value)

        // 6. Complete Request B
        deferredB.complete(User(nickname = "Minji", email = "test@example.com"))
        testDispatcher.scheduler.advanceUntilIdle()

        // Request B completed cleanly
        assertEquals(SignUpUiState.Idle, viewModel.uiState.value)
    }

    @Test
    fun `onGetStartedClicked before completion or after signOut does NOT emit navigation event`() {
        val viewModel = SignUpViewModel()

        // Call before completion
        viewModel.onGetStartedClicked()
        val stateBefore = viewModel.uiState.value
        assertTrue(stateBefore is SignUpUiState.FormError)

        // Call after signOut
        viewModel.signOut()
        viewModel.onGetStartedClicked()
        val stateAfter = viewModel.uiState.value
        assertTrue(stateAfter is SignUpUiState.FormError)
        assertNull(viewModel.events.value)
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
        val event1 = viewModel.events.value?.getContentIfNotHandled()
        assertTrue(event1 is SignUpEvent.ShowProfileLoadError)

        val errorEvent = event1 as SignUpEvent.ShowProfileLoadError

        // Retry profile step
        viewModel.retryProfileStep(errorEvent.user, errorEvent.uid, errorEvent.nickname, errorEvent.email, errorEvent.isExistingAuthAccount)
        testDispatcher.scheduler.advanceUntilIdle()

        // Auth count must STILL be 1 (no re-creation of Auth user)
        assertEquals(1, authCallCount)
    }

    @Test
    fun `signOut in-flight delay invalidates late response and clears pending context`() {
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
        assertNull(viewModel.pendingErrorContext)
        assertNull(viewModel.events.value)

        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(SignUpUiState.Idle, viewModel.uiState.value)
        assertNull(viewModel.events.value)
    }

    @Test
    fun `signup_oldLogout_preservesNewAccount`() {
        var activeUid: String? = "account_A"
        val mockAuth = org.mockito.Mockito.mock(FirebaseAuth::class.java)
        org.mockito.Mockito.doAnswer { activeUid = null; null }.`when`(mockAuth).signOut()

        val gate = CompletableDeferred<Unit>()
        var cleanupCount = 0
        val fakeRepo = object : com.pintrip.app.data.repository.DeviceTokenRepository() {
            override suspend fun cleanupTokenOnLogout(previousUid: String): Boolean {
                assertEquals("account_A", previousUid)
                cleanupCount++
                gate.await()
                return true
            }
        }

        val viewModel = SignUpViewModel(auth = mockAuth, currentUidProvider = { activeUid })
        viewModel.performLogout(fakeRepo)

        testDispatcher.scheduler.runCurrent()
        assertEquals(1, cleanupCount)
        assertTrue(com.pintrip.app.data.repository.DeviceTokenRepository.isLoggingOut)

        // Account B logs in while cleanup is suspended
        activeUid = "account_B"
        com.pintrip.app.data.repository.DeviceTokenRepository.activateSession("account_B")

        // Unblock cleanup
        gate.complete(Unit)
        testDispatcher.scheduler.runCurrent()

        assertEquals("Old logout must preserve the newer authenticated account", "account_B", activeUid)
        assertFalse("New active session isLoggingOut flag must remain false", com.pintrip.app.data.repository.DeviceTokenRepository.isLoggingOut)
    }

    @Test
    fun `signup_cleanupCancellation_releasesFlagAndSignsOut`() {
        val mockAuth = org.mockito.Mockito.mock(FirebaseAuth::class.java)
        val fakeRepo = object : com.pintrip.app.data.repository.DeviceTokenRepository() {
            override suspend fun cleanupTokenOnLogout(previousUid: String): Boolean {
                throw CancellationException("synthetic cleanup cancellation")
            }
        }

        val viewModel = SignUpViewModel(auth = mockAuth, currentUidProvider = { "account_A" })
        viewModel.performLogout(fakeRepo)

        testDispatcher.scheduler.runCurrent()
        assertFalse("Cleanup cancellation must not leave the logout flag set", com.pintrip.app.data.repository.DeviceTokenRepository.isLoggingOut)
        org.mockito.Mockito.verify(mockAuth).signOut()
    }

    @Test
    fun `signup_realTokenRepository_normalLogoutReleasesFlag`() {
        var activeUid: String? = "account_A"
        val mockAuth = org.mockito.Mockito.mock(FirebaseAuth::class.java)
        org.mockito.Mockito.doAnswer { activeUid = null; null }.`when`(mockAuth).signOut()
        var deleteCount = 0
        com.pintrip.app.data.repository.DeviceTokenRepository.preferenceOverride = com.pintrip.app.room.FakeSharedPreferences()
        com.pintrip.app.data.repository.DeviceTokenRepository.firestoreDeleterOverride = { uid, _ ->
            assertEquals("account_A", uid)
            deleteCount++
            true
        }
        try {
            val viewModel = SignUpViewModel(auth = mockAuth, currentUidProvider = { activeUid })
            viewModel.performLogout(com.pintrip.app.data.repository.DeviceTokenRepository())
            testDispatcher.scheduler.runCurrent()
            assertEquals(1, deleteCount)
            org.mockito.Mockito.verify(mockAuth).signOut()
            assertEquals(null, activeUid)
            assertFalse("Normal logout using the REAL token repository must release the logout flag", com.pintrip.app.data.repository.DeviceTokenRepository.isLoggingOut)
        } finally {
            com.pintrip.app.data.repository.DeviceTokenRepository.preferenceOverride = null
            com.pintrip.app.data.repository.DeviceTokenRepository.firestoreDeleterOverride = null
            com.pintrip.app.data.repository.DeviceTokenRepository.setLoggingOut(false)
        }
    }
}

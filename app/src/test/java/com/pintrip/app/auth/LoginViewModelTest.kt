package com.pintrip.app.auth

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.Source
import com.pintrip.app.R
import com.pintrip.app.data.model.User
import com.pintrip.app.data.repository.UserRepository
import kotlinx.coroutines.CancellationException
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
import org.mockito.Mockito

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
        var getUserProfileCallCount = 0
        var createUserProfileCallCount = 0
        var lastGetUid: String? = null
        var lastCreateUid: String? = null
        var lastCreateEmail: String? = null

        override suspend fun getUserProfile(uid: String, source: com.google.firebase.firestore.Source): User? {
            getUserProfileCallCount++
            lastGetUid = uid
            if (shouldThrow) throw RuntimeException("Firestore error")
            return profileToReturn
        }

        override suspend fun createUserProfile(uid: String, nickname: String, email: String, avatarColor: String) {
            createUserProfileCallCount++
            lastCreateUid = uid
            lastCreateEmail = email
        }
    }

    private fun createMockUser(uid: String = "u123", email: String = "user@example.com"): FirebaseUser {
        val mockUser = Mockito.mock(FirebaseUser::class.java)
        Mockito.`when`(mockUser.uid).thenReturn(uid)
        Mockito.`when`(mockUser.email).thenReturn(email)
        return mockUser
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
    fun `performLogin success path with missing profile preserves pendingUid and saveProfileNickname uses it`() {
        val fakeRepo = FakeUserRepository(profileToReturn = null)
        val mockUser = createMockUser("u123", "user@example.com")
        var signInActionCallCount = 0

        val viewModel = LoginViewModel(
            userRepository = fakeRepo,
            currentUidProvider = { "u123" },
            signInAction = { _, _ ->
                signInActionCallCount++
                mockUser
            },
        )

        // Actual performLogin execution
        viewModel.performLogin("user@example.com", "password123")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, signInActionCallCount)
        assertEquals("u123", viewModel.pendingUid)
        assertEquals("u123", fakeRepo.lastGetUid)

        val missingEvent = viewModel.events.value?.getContentIfNotHandled()
        assertTrue(missingEvent is LoginEvent.ShowProfileMissing)

        // Save nickname using preserved pendingUid
        viewModel.saveProfileNickname("Minji")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, fakeRepo.createUserProfileCallCount)
        assertEquals("u123", fakeRepo.lastCreateUid)
        assertEquals("user@example.com", fakeRepo.lastCreateEmail)
        assertEquals(1, signInActionCallCount)

        val navEvent = viewModel.events.value?.getContentIfNotHandled()
        assertTrue(navEvent is LoginEvent.NavigateToRoomList)
    }

    @Test
    fun `performLogin success path with profile load error preserves pendingUid and retryProfileCheck uses it`() {
        val fakeRepo = FakeUserRepository(shouldThrow = true)
        val mockUser = createMockUser("u123", "user@example.com")
        var signInActionCallCount = 0

        val viewModel = LoginViewModel(
            userRepository = fakeRepo,
            currentUidProvider = { "u123" },
            signInAction = { _, _ ->
                signInActionCallCount++
                mockUser
            },
        )

        // Actual performLogin execution
        viewModel.performLogin("user@example.com", "password123")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, signInActionCallCount)
        assertEquals(1, fakeRepo.getUserProfileCallCount)
        assertEquals("u123", fakeRepo.lastGetUid)

        val errorEvent = viewModel.events.value?.getContentIfNotHandled()
        assertTrue(errorEvent is LoginEvent.ShowProfileLoadError)

        // Retry profile check using preserved pendingUid
        viewModel.retryProfileCheck()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(2, fakeRepo.getUserProfileCallCount)
        assertEquals("u123", fakeRepo.lastGetUid)
        assertEquals(1, signInActionCallCount)
    }

    @Test
    fun `checkProfileAndNavigate with valid nickname emits Event NavigateToRoomList`() {
        val fakeRepo = FakeUserRepository(profileToReturn = User(nickname = "Minji", email = "user@example.com"))
        val viewModel = LoginViewModel(userRepository = fakeRepo, currentUidProvider = { "u123" })

        viewModel.checkProfileAndNavigate(user = null, uidOverride = "u123")
        testDispatcher.scheduler.advanceUntilIdle()

        val eventWrapper = viewModel.events.value
        val event = eventWrapper?.getContentIfNotHandled()
        assertTrue(event is LoginEvent.NavigateToRoomList)

        // Event consumed once: second call returns null
        assertNull(eventWrapper?.getContentIfNotHandled())
    }

    @Test
    fun `checkProfileAndNavigate with blank nickname emits Event ShowProfileCorrupted`() {
        val fakeRepo = FakeUserRepository(profileToReturn = User(nickname = "   ", email = "user@example.com"))
        val viewModel = LoginViewModel(userRepository = fakeRepo, currentUidProvider = { "u123" })

        viewModel.checkProfileAndNavigate(user = null, uidOverride = "u123")
        testDispatcher.scheduler.advanceUntilIdle()

        val eventWrapper = viewModel.events.value
        val event = eventWrapper?.getContentIfNotHandled()
        assertTrue(event is LoginEvent.ShowProfileCorrupted)

        assertNull(eventWrapper?.getContentIfNotHandled())
    }

    @Test
    fun `checkProfileAndNavigate with null profile emits Event ShowProfileMissing`() {
        val fakeRepo = FakeUserRepository(profileToReturn = null)
        val viewModel = LoginViewModel(userRepository = fakeRepo, currentUidProvider = { "u123" })

        viewModel.checkProfileAndNavigate(user = null, uidOverride = "u123")
        testDispatcher.scheduler.advanceUntilIdle()

        val eventWrapper = viewModel.events.value
        val event = eventWrapper?.getContentIfNotHandled()
        assertTrue(event is LoginEvent.ShowProfileMissing)

        assertNull(eventWrapper?.getContentIfNotHandled())
    }

    @Test
    fun `checkProfileAndNavigate with Firestore exception emits Event ShowProfileLoadError`() {
        val fakeRepo = FakeUserRepository(shouldThrow = true)
        val viewModel = LoginViewModel(userRepository = fakeRepo, currentUidProvider = { "u123" })

        viewModel.checkProfileAndNavigate(user = null, uidOverride = "u123")
        testDispatcher.scheduler.advanceUntilIdle()

        val eventWrapper = viewModel.events.value
        val event = eventWrapper?.getContentIfNotHandled()
        assertTrue(event is LoginEvent.ShowProfileLoadError)

        assertNull(eventWrapper?.getContentIfNotHandled())
    }

    @Test
    fun `checkProfileAndNavigate when active UID becomes null or changes to different UID emits FormError`() {
        var activeUid: String? = "u123"
        val fakeRepo = FakeUserRepository(profileToReturn = null)
        val viewModel = LoginViewModel(userRepository = fakeRepo, currentUidProvider = { activeUid })

        viewModel.checkProfileAndNavigate(user = null, uidOverride = "u123")
        testDispatcher.scheduler.advanceUntilIdle()

        // Active UID becomes null (logged out)
        activeUid = null

        viewModel.saveProfileNickname("Minji")
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is LoginUiState.FormError)
        assertEquals(R.string.login_error_generic, (state as LoginUiState.FormError).messageResId)
        assertEquals(0, fakeRepo.createUserProfileCallCount)
        assertNull(viewModel.pendingUid)
    }

    @Test
    fun `checkProfileAndNavigate when active UID changes to different UID during fetch emits FormError`() {
        var activeUid: String? = "u123"
        val fakeRepo = object : UserRepository(null, null) {
            override suspend fun getUserProfile(uid: String, source: com.google.firebase.firestore.Source): User {
                // Active UID changes mid-flight to u999
                activeUid = "u999_different"
                return User(nickname = "Minji", email = "test@example.com")
            }
        }

        val viewModel = LoginViewModel(userRepository = fakeRepo, currentUidProvider = { activeUid })

        viewModel.checkProfileAndNavigate(user = null, uidOverride = "u123")
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is LoginUiState.FormError)
        assertEquals(R.string.login_error_generic, (state as LoginUiState.FormError).messageResId)
        assertNull(viewModel.pendingUid)
    }

    @Test
    fun `signOut in-flight delay invalidates late response and clears pending context`() {
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
        assertNull(viewModel.pendingUser)
        assertNull(viewModel.pendingUid)

        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(LoginUiState.Idle, viewModel.uiState.value)
        assertNull(viewModel.events.value)
    }

    @Test
    fun `login_oldLogout_preservesNewAccount`() {
        var activeUid: String? = "account_A"
        val mockAuth = org.mockito.Mockito.mock(FirebaseAuth::class.java)
        org.mockito.Mockito.doAnswer { activeUid = null; null }.`when`(mockAuth).signOut()

        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        var cleanupCount = 0
        val fakeRepo = object : com.pintrip.app.data.repository.DeviceTokenRepository() {
            override suspend fun cleanupTokenOnLogout(previousUid: String): Boolean {
                assertEquals("account_A", previousUid)
                cleanupCount++
                gate.await()
                return true
            }
        }

        val viewModel = LoginViewModel(auth = mockAuth, currentUidProvider = { activeUid })
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
    fun `login_cleanupCancellation_releasesFlagAndSignsOut`() {
        val mockAuth = org.mockito.Mockito.mock(FirebaseAuth::class.java)
        val fakeRepo = object : com.pintrip.app.data.repository.DeviceTokenRepository() {
            override suspend fun cleanupTokenOnLogout(previousUid: String): Boolean {
                throw CancellationException("synthetic cleanup cancellation")
            }
        }

        val viewModel = LoginViewModel(auth = mockAuth, currentUidProvider = { "account_A" })
        viewModel.performLogout(fakeRepo)

        testDispatcher.scheduler.runCurrent()
        assertFalse("Cleanup cancellation must not leave the logout flag set", com.pintrip.app.data.repository.DeviceTokenRepository.isLoggingOut)
        org.mockito.Mockito.verify(mockAuth).signOut()
    }

    @Test
    fun `login_realTokenRepository_normalLogoutReleasesFlag`() {
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
            val viewModel = LoginViewModel(auth = mockAuth, currentUidProvider = { activeUid })
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

    private fun verifyReentryBlocked(autoLogin: Boolean) {
        var activeUid: String? = "account_A"
        var signInCalls = 0
        var profileCalls = 0
        val user = org.mockito.Mockito.mock(FirebaseUser::class.java)
        org.mockito.Mockito.`when`(user.uid).thenReturn("account_A")
        val auth = org.mockito.Mockito.mock(FirebaseAuth::class.java)
        org.mockito.Mockito.`when`(auth.currentUser).thenAnswer { if (activeUid == null) null else user }
        org.mockito.Mockito.doAnswer { activeUid = null; null }.`when`(auth).signOut()
        val profiles = object : UserRepository(null, null) {
            override suspend fun getUserProfile(uid: String, source: Source): User? {
                profileCalls++
                return User(nickname = "Synthetic A", email = "a@example.com")
            }
        }
        val cleanupGate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val tokens = object : com.pintrip.app.data.repository.DeviceTokenRepository() {
            override suspend fun cleanupTokenOnLogout(previousUid: String): Boolean {
                cleanupGate.await()
                return true
            }
        }
        val viewModel = LoginViewModel(
            auth = auth,
            userRepository = profiles,
            currentUidProvider = { activeUid },
            signInAction = { _, _ -> signInCalls++; activeUid = "account_A"; user }
        )
        viewModel.performLogout(tokens)
        testDispatcher.scheduler.runCurrent()

        if (autoLogin) {
            viewModel.checkExistingSession()
        } else {
            viewModel.performLogin("a@example.com", "synthetic_password")
        }
        testDispatcher.scheduler.runCurrent()

        val eventBeforeCleanup = viewModel.events.value?.peekContent()
        cleanupGate.complete(Unit)
        testDispatcher.scheduler.runCurrent()

        if (autoLogin) {
            assertEquals("Auto-login must not fetch a profile during logout", 0, profileCalls)
        } else {
            assertEquals("Login action must be ignored during logout", 0, signInCalls)
        }
        assertNull("No navigation event should be emitted during logout", eventBeforeCleanup)

        // After logout completes, a new login request can proceed normally
        viewModel.performLogin("a@example.com", "synthetic_password")
        testDispatcher.scheduler.runCurrent()
        assertEquals("Login action is allowed after logout completes", 1, signInCalls)
    }

    @Test
    fun `sameAccountLogin_isBlockedWhileLogoutWaits`() = verifyReentryBlocked(false)

    @Test
    fun `recreationAutoLogin_isBlockedWhileLogoutWaits`() = verifyReentryBlocked(true)
}

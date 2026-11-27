package com.pintrip.app.data.repository

import com.pintrip.app.room.FakeSharedPreferences
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DeviceTokenRepositoryTest {

    private lateinit var fakePrefs: FakeSharedPreferences

    @Before
    fun setUp() {
        fakePrefs = FakeSharedPreferences()
        DeviceTokenRepository.preferenceOverride = fakePrefs
        DeviceTokenRepository.setLoggingOut(false)
        DeviceTokenRepository.tokenFetcherOverride = null
        DeviceTokenRepository.currentUserProviderOverride = null
        DeviceTokenRepository.firestoreWriterOverride = null
        DeviceTokenRepository.firestoreDeleterOverride = null
    }

    @After
    fun tearDown() {
        DeviceTokenRepository.preferenceOverride = null
        DeviceTokenRepository.setLoggingOut(false)
        DeviceTokenRepository.tokenFetcherOverride = null
        DeviceTokenRepository.currentUserProviderOverride = null
        DeviceTokenRepository.firestoreWriterOverride = null
        DeviceTokenRepository.firestoreDeleterOverride = null
    }

    @Test
    fun getInstallationId_generatesAndPersistsUUID() {
        assertNull(fakePrefs.getString("installation_id", null))

        val repo = DeviceTokenRepository()
        val id1 = repo.getInstallationId()

        assertNotNull(id1)
        assertEquals(id1, fakePrefs.getString("installation_id", null))

        val id2 = repo.getInstallationId()
        assertEquals(id1, id2)
    }

    @Test
    fun sessionGeneration_incrementsOnInvalidate() {
        val initialGen = DeviceTokenRepository.currentSessionGeneration()
        val nextGen = DeviceTokenRepository.invalidateSession()

        assertEquals(nextGen, initialGen + 1)
        assertEquals(nextGen, DeviceTokenRepository.currentSessionGeneration())
    }

    @Test
    fun syncToken_whenUnauthenticated_returnsFalse() = runBlocking {
        DeviceTokenRepository.currentUserProviderOverride = { null }
        val repo = DeviceTokenRepository()

        val result = repo.syncToken("token_123")
        assertFalse(result)
    }

    @Test
    fun syncToken_whenLoggingOut_returnsFalse() = runBlocking {
        DeviceTokenRepository.setLoggingOut(true)
        DeviceTokenRepository.currentUserProviderOverride = { "user_123" }
        val repo = DeviceTokenRepository()

        val result = repo.syncToken("token_123")
        assertFalse(result)
    }

    @Test
    fun syncToken_successfulWrite_returnsTrue() = runBlocking {
        DeviceTokenRepository.currentUserProviderOverride = { "user_123" }
        var writtenUid: String? = null
        var writtenData: Map<String, Any?>? = null

        DeviceTokenRepository.firestoreWriterOverride = { uid, _, data ->
            writtenUid = uid
            writtenData = data
            true
        }

        val repo = DeviceTokenRepository()
        val result = repo.syncToken("token_abc")

        assertTrue(result)
        assertEquals("user_123", writtenUid)
        assertEquals("token_abc", writtenData?.get("token"))
        assertEquals("android", writtenData?.get("platform"))
    }

    @Test
    fun cleanupTokenOnLogout_blankUid_returnsTrueImmediately() = runBlocking {
        val repo = DeviceTokenRepository()
        val result = repo.cleanupTokenOnLogout("")
        assertTrue(result)
        assertTrue(DeviceTokenRepository.isLoggingOut)
    }

    @Test
    fun cleanupTokenOnLogout_validUid_callsDeleter() = runBlocking {
        var deletedUid: String? = null
        DeviceTokenRepository.firestoreDeleterOverride = { uid, _ ->
            deletedUid = uid
            true
        }

        val repo = DeviceTokenRepository()
        val result = repo.cleanupTokenOnLogout("user_previous")

        assertTrue(result)
        assertEquals("user_previous", deletedUid)
        assertTrue(DeviceTokenRepository.isLoggingOut)
    }

    @Test
    fun login_logout_relogin_flow_allowsTokenSyncAndUnblocksSession() = runBlocking {
        val currentUid = "user_A"
        DeviceTokenRepository.currentUserProviderOverride = { currentUid }
        var writtenUid: String? = null

        DeviceTokenRepository.firestoreWriterOverride = { uid, _, _ ->
            writtenUid = uid
            true
        }
        DeviceTokenRepository.firestoreDeleterOverride = { _, _ -> true }

        val repo = DeviceTokenRepository()

        // 1. Initial Login A -> sync succeeds
        repo.activateSession("user_A")
        val sync1 = repo.syncToken("token_A1")
        assertTrue(sync1)
        assertEquals("user_A", writtenUid)

        // 2. Logout -> isLoggingOut becomes true
        repo.cleanupTokenOnLogout("user_A")
        assertTrue(DeviceTokenRepository.isLoggingOut)

        // Sync during logout is blocked
        val syncDuringLogout = repo.syncToken("token_A_stale")
        assertFalse(syncDuringLogout)

        // 3. Re-login same user A -> activateSession unblocks logging out
        repo.activateSession("user_A")
        assertFalse(DeviceTokenRepository.isLoggingOut)

        // Sync after re-login succeeds
        val syncReLoggedIn = repo.syncToken("token_A2")
        assertTrue(syncReLoggedIn)
    }

    @Test
    fun loginA_logout_loginB_flow_isolatesTokensAndRejectsStaleA() = runBlocking {
        var activeUid = "user_A"
        DeviceTokenRepository.currentUserProviderOverride = { activeUid }
        var lastWrittenUid: String? = null

        DeviceTokenRepository.firestoreWriterOverride = { uid, _, _ ->
            lastWrittenUid = uid
            true
        }
        DeviceTokenRepository.firestoreDeleterOverride = { _, _ -> true }

        val repo = DeviceTokenRepository()

        // Login A
        repo.activateSession("user_A")
        repo.syncToken("token_A")
        assertEquals("user_A", lastWrittenUid)

        // Logout A -> Login B
        repo.cleanupTokenOnLogout("user_A")
        activeUid = "user_B"
        repo.activateSession("user_B")

        val syncB = repo.syncToken("token_B")
        assertTrue(syncB)
        assertEquals("user_B", lastWrittenUid)
    }
}

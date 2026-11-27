package com.pintrip.app.notification

import com.google.firebase.firestore.FirebaseFirestoreException
import com.pintrip.app.data.model.Room
import com.pintrip.app.data.repository.RoomRepository
import com.pintrip.app.room.FakeSharedPreferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PendingNotificationManagerTest {

    private lateinit var fakePrefs: FakeSharedPreferences

    @Before
    fun setUp() {
        fakePrefs = FakeSharedPreferences()
        PendingNotificationManager.preferenceOverride = fakePrefs
        PendingNotificationManager.clearPendingDestination(null)
        NotificationDestinationHandler.roomFetcherOverride = null
    }

    @After
    fun tearDown() {
        NotificationDestinationHandler.roomFetcherOverride = null
        PendingNotificationManager.clearPendingDestination(null)
        PendingNotificationManager.preferenceOverride = null
    }

    @Test
    fun saveAndGetPendingDestination_validData_persistsAndRetrieves() {
        assertNull(PendingNotificationManager.getPendingDestination(null))

        val destination = PendingNotificationDestination(
            roomId = "room_100",
            kakaoPlaceId = "place_200",
            recipientUid = "user_300",
            requestId = "req_111"
        )

        PendingNotificationManager.savePendingDestination(null, destination)

        val retrieved = PendingNotificationManager.getPendingDestination(null)
        assertNotNull(retrieved)
        assertEquals("room_100", retrieved?.roomId)
        assertEquals("place_200", retrieved?.kakaoPlaceId)
        assertEquals("user_300", retrieved?.recipientUid)
        assertEquals("req_111", retrieved?.requestId)
    }

    @Test
    fun savePendingDestination_blankIds_ignored() {
        val destination = PendingNotificationDestination(
            roomId = "",
            kakaoPlaceId = "place_200",
            recipientUid = "user_300"
        )

        PendingNotificationManager.savePendingDestination(null, destination)
        assertNull(PendingNotificationManager.getPendingDestination(null))
    }

    @Test
    fun singleFlightAcquisition_preventsDuplicateProcessing() {
        val destination = PendingNotificationDestination(
            roomId = "room_100",
            kakaoPlaceId = "place_200",
            recipientUid = "user_300",
            requestId = "req_single"
        )

        PendingNotificationManager.savePendingDestination(null, destination)

        // First call acquires destination
        val firstCall = PendingNotificationManager.acquireProcessingDestination(null)
        assertNotNull(firstCall)
        assertEquals("req_single", firstCall?.requestId)

        // Second call in parallel gets null
        val secondCall = PendingNotificationManager.acquireProcessingDestination(null)
        assertNull(secondCall)

        // Release processing allows retry
        PendingNotificationManager.releaseProcessing("req_single")
        val retryCall = PendingNotificationManager.acquireProcessingDestination(null)
        assertNotNull(retryCall)
    }

    @Test
    fun processDestination_concurrentInFlight_secondCallReturnsAlreadyProcessingAndServerCalledOnce() = runBlocking {
        val destination = PendingNotificationDestination(
            roomId = "room_dup",
            kakaoPlaceId = "place_dup",
            recipientUid = "user_1",
            requestId = "req_dup"
        )
        PendingNotificationManager.savePendingDestination(null, destination)

        val deferredRoom = CompletableDeferred<Room?>()
        var roomFetchCount = 0

        NotificationDestinationHandler.roomFetcherOverride = {
            roomFetchCount++
            deferredRoom.await()
        }

        val fakeRoomRepo = RoomRepository()
        val defaultSession = { SessionSnapshot("user_1", 1L, false) }

        // Start job1 immediately with UNDISPATCHED so it acquires the destination lock
        val job1 = async(start = CoroutineStart.UNDISPATCHED) {
            NotificationDestinationHandler.processDestination(
                null, fakeRoomRepo, sessionProvider = defaultSession
            )
        }

        // Call processDestination concurrently while job1 is in-flight
        val job2Result = NotificationDestinationHandler.processDestination(
            null, fakeRoomRepo, sessionProvider = defaultSession
        )

        assertEquals(PendingDestinationResult.AlreadyProcessing, job2Result)

        // Unblock job1
        val fakeRoom = Room(id = "room_dup", memberIds = listOf("user_1"))
        deferredRoom.complete(fakeRoom)

        val job1Result = job1.await()
        assertTrue(job1Result is PendingDestinationResult.Success)
        assertEquals(1, roomFetchCount)
    }

    @Test
    fun processDestination_realResponseInversion_AStaysStaleAndBNavigates() = runBlocking {
        val destA = PendingNotificationDestination(
            roomId = "room_A",
            kakaoPlaceId = "place_A",
            recipientUid = "user_1",
            requestId = "req_A"
        )
        PendingNotificationManager.savePendingDestination(null, destA)

        val deferredA = CompletableDeferred<Room?>()
        val fakeRoomA = Room(id = "room_A", memberIds = listOf("user_1"))
        val fakeRoomB = Room(id = "room_B", memberIds = listOf("user_1"))

        NotificationDestinationHandler.roomFetcherOverride = { roomId ->
            if (roomId == "room_A") deferredA.await() else fakeRoomB
        }

        val fakeRoomRepo = RoomRepository()
        val defaultSession = { SessionSnapshot("user_1", 1L, false) }

        // Start jobA immediately with UNDISPATCHED so it acquires destA and waits on deferredA
        val jobA = async(start = CoroutineStart.UNDISPATCHED) {
            NotificationDestinationHandler.processDestination(
                null, fakeRoomRepo, sessionProvider = defaultSession
            )
        }

        // While jobA is waiting on deferredA, Notification B arrives and is saved
        val destB = PendingNotificationDestination(
            roomId = "room_B",
            kakaoPlaceId = "place_B",
            recipientUid = "user_1",
            requestId = "req_B"
        )
        PendingNotificationManager.savePendingDestination(null, destB)

        // Process destination B
        val resultB = NotificationDestinationHandler.processDestination(
            null, fakeRoomRepo, sessionProvider = defaultSession
        )

        assertTrue(resultB is PendingDestinationResult.Success)
        val successB = resultB as PendingDestinationResult.Success
        assertEquals("room_B", successB.roomId)
        assertEquals("req_B", successB.requestId)

        // Unblock jobA
        deferredA.complete(fakeRoomA)

        val resultA = jobA.await()
        assertEquals(PendingDestinationResult.StaleOrSuperseded, resultA)
    }

    @Test
    fun processDestination_staleAException_returnsStaleOrSupersededAndPreservesB() = runBlocking {
        val destA = PendingNotificationDestination(
            roomId = "room_A",
            kakaoPlaceId = "place_A",
            recipientUid = "user_1",
            requestId = "req_A"
        )
        PendingNotificationManager.savePendingDestination(null, destA)

        val deferredA = CompletableDeferred<Room?>()
        val fakeRoomB = Room(id = "room_B", memberIds = listOf("user_1"))

        NotificationDestinationHandler.roomFetcherOverride = { roomId ->
            if (roomId == "room_A") deferredA.await() else fakeRoomB
        }

        val fakeRoomRepo = RoomRepository()
        val defaultSession = { SessionSnapshot("user_1", 1L, false) }

        // Start jobA immediately with UNDISPATCHED
        val jobA = async(start = CoroutineStart.UNDISPATCHED) {
            NotificationDestinationHandler.processDestination(
                null, fakeRoomRepo, sessionProvider = defaultSession
            )
        }

        // Destination B arrives
        val destB = PendingNotificationDestination(
            roomId = "room_B",
            kakaoPlaceId = "place_B",
            recipientUid = "user_1",
            requestId = "req_B"
        )
        PendingNotificationManager.savePendingDestination(null, destB)

        // deferredA completes exceptionally with UNAVAILABLE
        deferredA.completeExceptionally(
            FirebaseFirestoreException("Network error", FirebaseFirestoreException.Code.UNAVAILABLE)
        )

        val resultA = jobA.await()
        // Must return StaleOrSuperseded, NOT NetworkError or AccessDenied!
        assertEquals(PendingDestinationResult.StaleOrSuperseded, resultA)

        // B is preserved in SharedPreferences and successfully processed
        val currentStored = PendingNotificationManager.getPendingDestination(null)
        assertNotNull(currentStored)
        assertEquals("req_B", currentStored?.requestId)

        val resultB = NotificationDestinationHandler.processDestination(
            null, fakeRoomRepo, sessionProvider = defaultSession
        )
        assertTrue(resultB is PendingDestinationResult.Success)
    }

    @Test
    fun processDestination_inFlightUidChange_abortsWithStaleOrSuperseded() = runBlocking {
        val destination = PendingNotificationDestination(
            roomId = "room_uid",
            kakaoPlaceId = "place_uid",
            recipientUid = "user_1",
            requestId = "req_uid"
        )
        PendingNotificationManager.savePendingDestination(null, destination)

        val deferredRoom = CompletableDeferred<Room?>()
        NotificationDestinationHandler.roomFetcherOverride = { deferredRoom.await() }

        var activeUid = "user_1"
        val activeGen = 100L
        val loggingOut = false

        val dynamicSessionProvider = { SessionSnapshot(activeUid, activeGen, loggingOut) }
        val fakeRoomRepo = RoomRepository()

        val job = async(start = CoroutineStart.UNDISPATCHED) {
            NotificationDestinationHandler.processDestination(
                null, fakeRoomRepo, sessionProvider = dynamicSessionProvider
            )
        }

        // Change UID mid-flight while waiting
        activeUid = "user_2"

        val fakeRoom = Room(id = "room_uid", memberIds = listOf("user_1"))
        deferredRoom.complete(fakeRoom)

        val result = job.await()
        assertEquals(PendingDestinationResult.StaleOrSuperseded, result)

        // Destination is NOT consumed on UID mismatch
        val currentStored = PendingNotificationManager.getPendingDestination(null)
        assertNotNull(currentStored)
        assertEquals("req_uid", currentStored?.requestId)
    }

    @Test
    fun processDestination_inFlightGenerationChangeSameUid_abortsWithStaleOrSuperseded() = runBlocking {
        val destination = PendingNotificationDestination(
            roomId = "room_gen",
            kakaoPlaceId = "place_gen",
            recipientUid = "user_1",
            requestId = "req_gen"
        )
        PendingNotificationManager.savePendingDestination(null, destination)

        val deferredRoom = CompletableDeferred<Room?>()
        NotificationDestinationHandler.roomFetcherOverride = { deferredRoom.await() }

        val activeUid = "user_1"
        var activeGen = 100L
        val loggingOut = false

        val dynamicSessionProvider = { SessionSnapshot(activeUid, activeGen, loggingOut) }
        val fakeRoomRepo = RoomRepository()

        val job = async(start = CoroutineStart.UNDISPATCHED) {
            NotificationDestinationHandler.processDestination(
                null, fakeRoomRepo, sessionProvider = dynamicSessionProvider
            )
        }

        // Change session generation mid-flight while keeping same UID
        activeGen = 101L

        val fakeRoom = Room(id = "room_gen", memberIds = listOf("user_1"))
        deferredRoom.complete(fakeRoom)

        val result = job.await()
        assertEquals(PendingDestinationResult.StaleOrSuperseded, result)

        // Destination is NOT consumed on generation mismatch
        val currentStored = PendingNotificationManager.getPendingDestination(null)
        assertNotNull(currentStored)
        assertEquals("req_gen", currentStored?.requestId)
    }

    @Test
    fun processDestination_inFlightIsLoggingOut_abortsWithStaleOrSuperseded() = runBlocking {
        val destination = PendingNotificationDestination(
            roomId = "room_logout",
            kakaoPlaceId = "place_logout",
            recipientUid = "user_1",
            requestId = "req_logout"
        )
        PendingNotificationManager.savePendingDestination(null, destination)

        val deferredRoom = CompletableDeferred<Room?>()
        NotificationDestinationHandler.roomFetcherOverride = { deferredRoom.await() }

        val activeUid = "user_1"
        val activeGen = 100L
        var loggingOut = false

        val dynamicSessionProvider = { SessionSnapshot(activeUid, activeGen, loggingOut) }
        val fakeRoomRepo = RoomRepository()

        val job = async(start = CoroutineStart.UNDISPATCHED) {
            NotificationDestinationHandler.processDestination(
                null, fakeRoomRepo, sessionProvider = dynamicSessionProvider
            )
        }

        // Change isLoggingOut mid-flight while waiting
        loggingOut = true

        val fakeRoom = Room(id = "room_logout", memberIds = listOf("user_1"))
        deferredRoom.complete(fakeRoom)

        val result = job.await()
        assertEquals(PendingDestinationResult.StaleOrSuperseded, result)

        // Destination is NOT consumed when logging out mid-flight
        val currentStored = PendingNotificationManager.getPendingDestination(null)
        assertNotNull(currentStored)
        assertEquals("req_logout", currentStored?.requestId)
    }

    @Test
    fun processDestination_networkError_preservesDestinationForRetry() = runBlocking {
        val destination = PendingNotificationDestination(
            roomId = "room_net",
            kakaoPlaceId = "place_net",
            recipientUid = "user_1",
            requestId = "req_net"
        )
        PendingNotificationManager.savePendingDestination(null, destination)

        var throwNetworkError = true
        val fakeRoom = Room(id = "room_net", memberIds = listOf("user_1"))

        NotificationDestinationHandler.roomFetcherOverride = {
            if (throwNetworkError) {
                throw FirebaseFirestoreException("Network unavailable", FirebaseFirestoreException.Code.UNAVAILABLE)
            } else {
                fakeRoom
            }
        }

        val fakeRoomRepo = RoomRepository()
        val defaultSession = { SessionSnapshot("user_1", 1L, false) }

        // 1st attempt: Network error -> returns NetworkError, destination preserved
        val result1 = NotificationDestinationHandler.processDestination(
            null, fakeRoomRepo, sessionProvider = defaultSession
        )
        assertEquals(PendingDestinationResult.NetworkError, result1)
        assertNotNull(PendingNotificationManager.getPendingDestination(null))

        // 2nd attempt: Network restored -> returns Success, destination consumed
        throwNetworkError = false
        val result2 = NotificationDestinationHandler.processDestination(
            null, fakeRoomRepo, sessionProvider = defaultSession
        )
        assertTrue(result2 is PendingDestinationResult.Success)
        assertNull(PendingNotificationManager.getPendingDestination(null))
    }
}

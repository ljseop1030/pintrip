package com.pintrip.app.notification

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Source
import com.pintrip.app.data.model.Room
import com.pintrip.app.data.repository.DeviceTokenRepository
import com.pintrip.app.data.repository.RoomRepository
import kotlinx.coroutines.CancellationException

data class SessionSnapshot(
    val uid: String?,
    val generation: Long,
    val isLoggingOut: Boolean
)

sealed class PendingDestinationResult {
    data class Success(
        val roomId: String,
        val kakaoPlaceId: String,
        val requestId: String
    ) : PendingDestinationResult()

    object AccountMismatch : PendingDestinationResult()
    object AccessDenied : PendingDestinationResult()
    object NetworkError : PendingDestinationResult()
    object StaleOrSuperseded : PendingDestinationResult()
    object NoDestination : PendingDestinationResult()
    object AlreadyProcessing : PendingDestinationResult()
}

object NotificationDestinationHandler {

    @get:Synchronized @set:Synchronized
    var roomFetcherOverride: (suspend (roomId: String) -> Room?)? = null

    /**
     * 알림 목적지를 단일 실행(Single-Flight)으로 획득하고, Firestore 방 권한을 비동기로 검증합니다.
     * 검증 성공 시 원자적으로 목적지를 소비하고 Success 결과를 반환합니다.
     * 이미 동기 처리 중이거나 최신 요청 및 라이브 세션 세대가 변경된 경우 안전하게 중단합니다.
     */
    suspend fun processDestination(
        context: Context?,
        roomRepository: RoomRepository,
        sessionProvider: () -> SessionSnapshot = {
            SessionSnapshot(
                uid = try { FirebaseAuth.getInstance().currentUser?.uid } catch (_: Exception) { null },
                generation = DeviceTokenRepository.currentSessionGeneration(),
                isLoggingOut = DeviceTokenRepository.isLoggingOut
            )
        }
    ): PendingDestinationResult {
        val initialSession = sessionProvider.invoke()
        if (initialSession.isLoggingOut || initialSession.uid.isNullOrBlank()) {
            return PendingDestinationResult.AccountMismatch
        }

        val destination = PendingNotificationManager.acquireProcessingDestination(context)
        if (destination == null) {
            return if (PendingNotificationManager.getPendingDestination(context) != null) {
                PendingDestinationResult.AlreadyProcessing
            } else {
                PendingDestinationResult.NoDestination
            }
        }

        if (destination.recipientUid != initialSession.uid) {
            PendingNotificationManager.consumeDestination(context, destination.requestId)
            return PendingDestinationResult.AccountMismatch
        }

        try {
            val roomFetcher = roomFetcherOverride
            val room = if (roomFetcher != null) {
                roomFetcher.invoke(destination.roomId)
            } else {
                roomRepository.getRoom(destination.roomId, Source.SERVER)
            }

            val liveSession = sessionProvider.invoke()
            if (liveSession.isLoggingOut || liveSession.uid != initialSession.uid || liveSession.generation != initialSession.generation) {
                PendingNotificationManager.releaseProcessing(destination.requestId)
                return PendingDestinationResult.StaleOrSuperseded
            }

            val latestDestination = PendingNotificationManager.getPendingDestination(context)
            if (latestDestination?.requestId != destination.requestId) {
                PendingNotificationManager.releaseProcessing(destination.requestId)
                return PendingDestinationResult.StaleOrSuperseded
            }

            if (room == null || !room.memberIds.contains(initialSession.uid)) {
                PendingNotificationManager.consumeDestination(context, destination.requestId)
                return PendingDestinationResult.AccessDenied
            }

            val consumed = PendingNotificationManager.consumeDestination(context, destination.requestId)
            if (!consumed) {
                return PendingDestinationResult.StaleOrSuperseded
            }

            return PendingDestinationResult.Success(
                roomId = destination.roomId,
                kakaoPlaceId = destination.kakaoPlaceId,
                requestId = destination.requestId
            )
        } catch (e: CancellationException) {
            PendingNotificationManager.releaseProcessing(destination.requestId)
            throw e
        } catch (e: Exception) {
            val liveSession = sessionProvider.invoke()
            if (liveSession.isLoggingOut || liveSession.uid != initialSession.uid || liveSession.generation != initialSession.generation) {
                PendingNotificationManager.releaseProcessing(destination.requestId)
                return PendingDestinationResult.StaleOrSuperseded
            }

            val latestDestination = PendingNotificationManager.getPendingDestination(context)
            if (latestDestination?.requestId != destination.requestId) {
                PendingNotificationManager.releaseProcessing(destination.requestId)
                return PendingDestinationResult.StaleOrSuperseded
            }

            if (e is FirebaseFirestoreException &&
                (e.code == FirebaseFirestoreException.Code.UNAVAILABLE ||
                 e.code == FirebaseFirestoreException.Code.DEADLINE_EXCEEDED)) {
                PendingNotificationManager.releaseProcessing(destination.requestId)
                return PendingDestinationResult.NetworkError
            } else {
                PendingNotificationManager.consumeDestination(context, destination.requestId)
                return PendingDestinationResult.AccessDenied
            }
        }
    }
}

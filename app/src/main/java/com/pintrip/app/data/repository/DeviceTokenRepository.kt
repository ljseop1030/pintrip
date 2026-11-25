package com.pintrip.app.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.pintrip.app.notification.FriendNotificationService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

open class DeviceTokenRepository(
    private val context: Context? = null,
    db: FirebaseFirestore? = null,
    auth: FirebaseAuth? = null,
    messaging: FirebaseMessaging? = null,
    val repositoryScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    private val db: FirebaseFirestore? by lazy { db ?: try { FirebaseFirestore.getInstance() } catch (_: Exception) { null } }
    private val auth: FirebaseAuth? by lazy { auth ?: try { FirebaseAuth.getInstance() } catch (_: Exception) { null } }
    private val messaging: FirebaseMessaging? by lazy { messaging ?: try { FirebaseMessaging.getInstance() } catch (_: Exception) { null } }

    val isLoggingOut: Boolean get() = Companion.isLoggingOut

    fun activateSession(uid: String) = Companion.activateSession(uid)

    companion object {
        private const val TAG = "DeviceTokenRepository"
        private const val PREFS_NAME = "pintrip_device_prefs"
        private const val KEY_INSTALLATION_ID = "installation_id"
        private const val PLATFORM_ANDROID = "android"
        private const val REMOTE_CLEANUP_TIMEOUT_MS = 3000L

        @Volatile
        private var instance: DeviceTokenRepository? = null

        @get:Synchronized @set:Synchronized
        var preferenceOverride: SharedPreferences? = null

        private val sessionGeneration = AtomicLong(0L)
        private val tokenUpdateSeq = AtomicLong(0L)
        private val _isLoggingOut = AtomicBoolean(false)

        @get:Synchronized @set:Synchronized
        var tokenFetcherOverride: (suspend () -> String?)? = null

        @get:Synchronized @set:Synchronized
        var currentUserProviderOverride: (() -> String?)? = null

        @get:Synchronized @set:Synchronized
        var firestoreWriterOverride: (suspend (uid: String, installationId: String, data: Map<String, Any?>) -> Boolean)? = null

        @get:Synchronized @set:Synchronized
        var firestoreDeleterOverride: (suspend (uid: String, installationId: String) -> Boolean)? = null

        val isLoggingOut: Boolean get() = _isLoggingOut.get()

        fun setLoggingOut(loggingOut: Boolean) {
            _isLoggingOut.set(loggingOut)
        }

        fun activateSession(uid: String): Long {
            _isLoggingOut.set(false)
            return sessionGeneration.get()
        }

        fun currentSessionGeneration(): Long = sessionGeneration.get()

        fun invalidateSession(): Long {
            _isLoggingOut.set(true)
            return sessionGeneration.incrementAndGet()
        }

        fun getInstance(context: Context): DeviceTokenRepository {
            return instance ?: synchronized(this) {
                instance ?: DeviceTokenRepository(context.applicationContext).also { instance = it }
            }
        }
    }

    private fun resolveCurrentUid(): String? {
        val customProvider = currentUserProviderOverride
        if (customProvider != null) {
            return customProvider.invoke()
        }
        return try {
            auth?.currentUser?.uid
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 앱 설치 단위를 식별하는 installationId를 반환합니다.
     * SharedPreferences에 생성 및 저장하여 보존하며, 기기 고유 하드웨어 ID는 사용하지 않습니다.
     */
    fun getInstallationId(): String {
        val prefs = preferenceOverride ?: checkNotNull(context) { "Context is required when preferenceOverride is null" }.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var installationId = prefs.getString(KEY_INSTALLATION_ID, null)
        if (installationId.isNullOrBlank()) {
            installationId = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_INSTALLATION_ID, installationId).apply()
        }
        return installationId
    }

    /**
     * 로그인된 사용자의 users/{uid}/devices/{installationId} 경로에 FCM 토큰을 동기화합니다.
     *
     * - 미로그인 상태이거나 로그아웃 진행 중인 경우 토큰 저장을 진행하지 않습니다.
     * - 시퀀스 번호 및 세대 번호(sessionGeneration)를 직렬화 및 확인하여 이전 지연 응답이 최신 토큰을 덮어쓰는 것을 방지합니다.
     * - 네트워크나 저장 실패 시에도 로그인 성공 자체를 막지 않고 반환합니다.
     */
    open suspend fun syncToken(token: String? = null): Boolean {
        if (_isLoggingOut.get()) {
            Log.d(TAG, "Logging out in progress. Aborting token sync.")
            return false
        }

        val initialSeq = tokenUpdateSeq.incrementAndGet()
        val initialGen = currentSessionGeneration()

        val currentUid = resolveCurrentUid()
        if (currentUid.isNullOrBlank()) {
            Log.d(TAG, "Not logged in. Skipping device token sync.")
            return false
        }

        val targetToken = if (!token.isNullOrBlank()) {
            token
        } else {
            val customFetcher = tokenFetcherOverride
            if (customFetcher != null) {
                customFetcher.invoke()
            } else {
                try {
                    messaging?.token?.await()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.w(TAG, "Failed to fetch FCM token: ${e.message}")
                    null
                }
            }
        }

        if (targetToken.isNullOrBlank()) {
            Log.w(TAG, "FCM token is empty. Cannot sync token.")
            return false
        }

        if (initialSeq != tokenUpdateSeq.get() || initialGen != currentSessionGeneration() || _isLoggingOut.get()) {
            Log.w(TAG, "Session or token sequence invalidated during token fetch. Aborting sync.")
            return false
        }

        val activeUid = resolveCurrentUid()
        if (activeUid != currentUid) {
            Log.w(TAG, "User changed during token fetch. Aborting sync.")
            return false
        }

        val installationId = getInstallationId()
        val deviceData = mapOf<String, Any?>(
            "token" to targetToken,
            "platform" to PLATFORM_ANDROID,
            "updatedAt" to FieldValue.serverTimestamp()
        )

        val customWriter = firestoreWriterOverride
        val writeSuccess = if (customWriter != null) {
            customWriter.invoke(currentUid, installationId, deviceData)
        } else {
            val firestore = db ?: return false
            val deviceDocRef = firestore.collection("users")
                .document(currentUid)
                .collection("devices")
                .document(installationId)
            try {
                deviceDocRef.set(deviceData, SetOptions.merge()).await()
                true
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Failed to sync FCM token for uid: $currentUid: ${e.message}", e)
                false
            }
        }

        if (initialSeq != tokenUpdateSeq.get() || initialGen != currentSessionGeneration() || _isLoggingOut.get()) {
            Log.w(TAG, "Session or token sequence changed during write. Token sync outdated.")
            return false
        }

        if (writeSuccess) {
            Log.d(TAG, "Successfully synced FCM token for uid: $currentUid, installationId: $installationId")
        }
        return writeSuccess
    }

    /**
     * 로그아웃/계정 전환 시 로컬 세션 세대를 무효화하고 표시된 친구 알림을 정리하며
     * 이전 UID의 users/{previousUid}/devices/{installationId} 원격 토큰 연결을 제한 시간(3초) 내에 삭제 시도합니다.
     */
    open suspend fun cleanupTokenOnLogout(previousUid: String): Boolean {
        invalidateSession()

        context?.let { ctx ->
            FriendNotificationService.cancelFriendNotifications(ctx)
        }

        if (previousUid.isBlank()) return true

        val installationId = getInstallationId()

        val customDeleter = firestoreDeleterOverride
        if (customDeleter != null) {
            return customDeleter.invoke(previousUid, installationId)
        }

        val firestore = db ?: return false
        val deviceDocRef = firestore.collection("users")
            .document(previousUid)
            .collection("devices")
            .document(installationId)

        return try {
            val result = withTimeoutOrNull(REMOTE_CLEANUP_TIMEOUT_MS) {
                deviceDocRef.delete().await()
                true
            }
            if (result == true) {
                Log.d(TAG, "Successfully cleaned up device token for previousUid: $previousUid")
                true
            } else {
                Log.w(TAG, "Remote token deletion timed out for previousUid: $previousUid")
                false
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "Failed to delete device token on logout for previousUid: $previousUid: ${e.message}")
            false
        }
    }
}

package com.pintrip.app.notification

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.firebase.auth.FirebaseAuth
import com.pintrip.app.BuildConfig
import com.pintrip.app.data.repository.DeviceTokenRepository
import kotlinx.coroutines.tasks.await
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class FriendPlaceNotificationWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "FriendPlaceNotifWorker"

        const val KEY_ROOM_ID = "key_room_id"
        const val KEY_NOTIFICATION_BATCH_ID = "key_notification_batch_id"
        const val KEY_REQUESTING_UID = "key_requesting_uid"

        @Volatile
        var okHttpClientOverride: OkHttpClient? = null

        fun enqueueWork(
            context: Context,
            roomId: String,
            notificationBatchId: String,
            requestingUid: String
        ) {
            if (roomId.isBlank() || notificationBatchId.isBlank() || requestingUid.isBlank()) return

            val inputData = Data.Builder()
                .putString(KEY_ROOM_ID, roomId)
                .putString(KEY_NOTIFICATION_BATCH_ID, notificationBatchId)
                .putString(KEY_REQUESTING_UID, requestingUid)
                .build()

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val workRequest = OneTimeWorkRequestBuilder<FriendPlaceNotificationWorker>()
                .setInputData(inputData)
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    10,
                    TimeUnit.SECONDS
                )
                .build()

            try {
                WorkManager.getInstance(context.applicationContext).enqueue(workRequest)
                Log.d(TAG, "Enqueued notification work for room: $roomId, batch: $notificationBatchId")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to enqueue WorkManager notification task: ${e.message}")
            }
        }
    }

    override suspend fun doWork(): ListenableWorker.Result {
        val roomId = inputData.getString(KEY_ROOM_ID)
        val notificationBatchId = inputData.getString(KEY_NOTIFICATION_BATCH_ID)
        val requestingUid = inputData.getString(KEY_REQUESTING_UID)

        if (roomId.isNullOrBlank() || notificationBatchId.isNullOrBlank() || requestingUid.isNullOrBlank()) {
            Log.w(TAG, "Invalid work input parameters. Marking work as failed.")
            return ListenableWorker.Result.failure()
        }

        val auth = FirebaseAuth.getInstance()
        val currentUser = auth.currentUser
        val tokenRepo = DeviceTokenRepository.getInstance(applicationContext)

        if (currentUser == null || currentUser.uid != requestingUid || tokenRepo.isLoggingOut) {
            Log.w(TAG, "Current user mismatched or logging out. Aborting notification worker.")
            return ListenableWorker.Result.failure()
        }

        val baseUrl = BuildConfig.NOTIFICATION_API_URL.trim().trimEnd('/')
        if (baseUrl.isBlank()) {
            Log.w(TAG, "NOTIFICATION_API_URL is not configured in BuildConfig. Skipping server notification API call.")
            return ListenableWorker.Result.failure()
        }

        val idToken = try {
            currentUser.getIdToken(false).await()?.token
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch Firebase ID token: ${e.message}")
            return ListenableWorker.Result.retry()
        }

        if (idToken.isNullOrBlank()) {
            Log.w(TAG, "Firebase ID token is blank. Retrying worker.")
            return ListenableWorker.Result.retry()
        }

        val endpointUrl = "$baseUrl/api/friend-place-added"
        val jsonBody = JSONObject().apply {
            put("roomId", roomId)
            put("notificationBatchId", notificationBatchId)
        }.toString()

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val requestBody = jsonBody.toRequestBody(mediaType)

        val request = Request.Builder()
            .url(endpointUrl)
            .addHeader("Authorization", "Bearer $idToken")
            .post(requestBody)
            .build()

        val client = okHttpClientOverride ?: OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                val code = response.code
                if (response.isSuccessful) {
                    Log.d(TAG, "Notification API call succeeded with code $code for batch: $notificationBatchId")
                    ListenableWorker.Result.success()
                } else if (code == 429) {
                    Log.w(TAG, "Notification API call rate-limited (HTTP 429). Retrying work.")
                    ListenableWorker.Result.retry()
                } else if (code in 400..499) {
                    Log.w(TAG, "Notification API call rejected with permanent client error code $code")
                    ListenableWorker.Result.failure()
                } else {
                    Log.w(TAG, "Notification API call returned server error code $code. Retrying work.")
                    ListenableWorker.Result.retry()
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "Network error calling Notification API: ${e.message}. Retrying work.")
            ListenableWorker.Result.retry()
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected exception in Notification worker: ${e.message}", e)
            ListenableWorker.Result.failure()
        }
    }
}

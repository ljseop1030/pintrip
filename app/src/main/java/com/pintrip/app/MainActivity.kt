package com.pintrip.app

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Source
import com.pintrip.app.auth.TestLoginActivity
import com.pintrip.app.data.repository.DeviceTokenRepository
import com.pintrip.app.data.repository.RoomRepository
import com.pintrip.app.data.repository.UserRepository
import com.pintrip.app.databinding.ActivityMainBinding
import com.pintrip.app.map.RoomMapActivity
import com.pintrip.app.notification.FriendNotificationService
import com.pintrip.app.notification.PendingNotificationDestination
import com.pintrip.app.notification.PendingNotificationManager
import com.pintrip.app.place.PlaceDetailActivity
import com.pintrip.app.room.RoomListActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 앱진입 메인 액티비티.
 *
 * Firebase Auth 세션 및 Firestore 프로필(Source.SERVER)을 확인하여
 * - 미로그인 시: [TestLoginActivity]로 이동 (PendingNotificationManager에 목적지 보존)
 * - 로그인 및 프로필 확인 시: FCM 토큰 동기화 후 [RoomListActivity] 또는 푸시 알림 목적지([RoomMapActivity] -> [PlaceDetailActivity])로 이동
 * - 로그인되어 있으나 프로필 문서 누락 시: [TestLoginActivity]로 이동하여 닉네임 복구 진행
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var binding: ActivityMainBinding
    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val userRepository: UserRepository by lazy { UserRepository(auth = auth) }
    private val roomRepository: RoomRepository by lazy { RoomRepository(auth = auth) }
    private val deviceTokenRepository: DeviceTokenRepository by lazy { DeviceTokenRepository.getInstance(applicationContext) }

    private var pendingInviteCodeToPass: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (savedInstanceState == null) {
            handleIncomingDeepLink(intent)
            handleIncomingNotification(intent)
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        binding.btnTestLogin.setOnClickListener {
            navigateToLogin()
        }

        val currentUser = auth.currentUser
        if (currentUser == null) {
            navigateToLogin()
        } else {
            binding.btnTestLogin.isEnabled = false
            verifyProfileAndNavigate(currentUser)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingDeepLink(intent)
        handleIncomingNotification(intent)

        val currentUser = auth.currentUser
        if (currentUser != null) {
            verifyProfileAndNavigate(currentUser)
        }
    }

    private fun handleIncomingDeepLink(intent: Intent?) {
        if (intent == null || intent.action != Intent.ACTION_VIEW) return
        if ((intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0) return
        val code = com.pintrip.app.room.DeepLinkUtils.extractInviteCode(intent)
        if (code != null) {
            com.pintrip.app.room.PendingInviteManager.savePendingCode(applicationContext, code)
            pendingInviteCodeToPass = code
        }
    }

    private fun handleIncomingNotification(intent: Intent?) {
        if (intent == null) return
        val action = intent.action
        val type = intent.getStringExtra(FriendNotificationService.EXTRA_TYPE)
        val roomId = intent.getStringExtra(FriendNotificationService.EXTRA_ROOM_ID)
        val kakaoPlaceId = intent.getStringExtra(FriendNotificationService.EXTRA_KAKAO_PLACE_ID)
        val recipientUid = intent.getStringExtra(FriendNotificationService.EXTRA_RECIPIENT_UID)

        if ((action == FriendNotificationService.ACTION_FRIEND_PLACE_ADDED || type == FriendNotificationService.TYPE_PLACE_ADDED) &&
            !roomId.isNullOrBlank() && !kakaoPlaceId.isNullOrBlank() && !recipientUid.isNullOrBlank()) {

            val destination = PendingNotificationDestination(
                roomId = roomId,
                kakaoPlaceId = kakaoPlaceId,
                recipientUid = recipientUid
            )
            PendingNotificationManager.savePendingDestination(applicationContext, destination)
        }
    }

    private fun verifyProfileAndNavigate(user: FirebaseUser) {
        lifecycleScope.launch {
            try {
                binding.btnTestLogin.isEnabled = false
                val profile = userRepository.getUserProfile(user.uid, source = Source.SERVER)
                if (profile != null && profile.nickname.isNotBlank()) {
                    deviceTokenRepository.activateSession(user.uid)
                    launch {
                        try {
                            deviceTokenRepository.syncToken()
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            Log.w(TAG, "Device token sync failed on login: ${e.message}")
                        }
                    }

                    val notifResult = com.pintrip.app.notification.NotificationDestinationHandler.processDestination(
                        applicationContext,
                        roomRepository,
                        sessionProvider = {
                            com.pintrip.app.notification.SessionSnapshot(
                                uid = auth.currentUser?.uid,
                                generation = DeviceTokenRepository.currentSessionGeneration(),
                                isLoggingOut = deviceTokenRepository.isLoggingOut
                            )
                        }
                    )
                    handleMainActivityDestinationResult(notifResult)
                } else {
                    navigateToLogin()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) {
                    Log.e(TAG, "Failed to verify user profile on app entry", e)
                }
                binding.btnTestLogin.isEnabled = true

                val messageResId = if (e is FirebaseFirestoreException &&
                    (e.code == FirebaseFirestoreException.Code.UNAVAILABLE ||
                     e.code == FirebaseFirestoreException.Code.DEADLINE_EXCEEDED)
                ) {
                    R.string.error_network_unavailable
                } else {
                    R.string.error_profile_load_default
                }

                AlertDialog.Builder(this@MainActivity)
                    .setTitle(getString(R.string.error_profile_load_failed))
                    .setMessage(getString(messageResId))
                    .setCancelable(false)
                    .setPositiveButton(getString(R.string.btn_retry)) { _, _ ->
                        verifyProfileAndNavigate(user)
                    }
                    .setNegativeButton(getString(R.string.btn_logout)) { _, _ ->
                        auth.signOut()
                        navigateToLogin()
                    }
                    .show()
            }
        }
    }

    private fun handleMainActivityDestinationResult(result: com.pintrip.app.notification.PendingDestinationResult) {
        when (result) {
            is com.pintrip.app.notification.PendingDestinationResult.Success -> {
                val override = PendingNotificationManager.navigatorOverride
                if (override != null) {
                    override.invoke(this, result.roomId, result.kakaoPlaceId)
                } else {
                    val roomMapIntent = RoomMapActivity.newIntent(this, result.roomId)
                    val placeDetailIntent = PlaceDetailActivity.newIntent(this, result.roomId, result.kakaoPlaceId)
                    startActivities(arrayOf(roomMapIntent, placeDetailIntent))
                }
                finish()
            }
            is com.pintrip.app.notification.PendingDestinationResult.AccountMismatch -> {
                Toast.makeText(this, R.string.notice_notification_account_mismatch, Toast.LENGTH_SHORT).show()
                navigateToRoomList()
            }
            is com.pintrip.app.notification.PendingDestinationResult.AccessDenied -> {
                Toast.makeText(this, R.string.notice_notification_access_denied, Toast.LENGTH_SHORT).show()
                navigateToRoomList()
            }
            is com.pintrip.app.notification.PendingDestinationResult.NetworkError -> {
                Toast.makeText(this, R.string.error_network_unavailable, Toast.LENGTH_SHORT).show()
                navigateToRoomList()
            }
            is com.pintrip.app.notification.PendingDestinationResult.NoDestination -> {
                navigateToRoomList()
            }
            is com.pintrip.app.notification.PendingDestinationResult.AlreadyProcessing,
            is com.pintrip.app.notification.PendingDestinationResult.StaleOrSuperseded -> {
                // In-flight or superseded: do NOT navigate to RoomList or finish prematurely!
            }
        }
    }

    private fun navigateToRoomList() {
        val intent = Intent(this, RoomListActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            pendingInviteCodeToPass?.let { code ->
                putExtra(com.pintrip.app.room.RoomListActivity.EXTRA_INVITE_CODE, code)
            }
        }
        pendingInviteCodeToPass = null
        startActivity(intent)
        finish()
    }

    private fun navigateToLogin() {
        val intent = Intent(this, TestLoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }
}

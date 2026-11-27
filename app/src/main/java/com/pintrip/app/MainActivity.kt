package com.pintrip.app

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Source
import com.pintrip.app.auth.SignUpActivity
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
 * 앱 진입 메인 액티비티 및 02 시작 화면.
 *
 * - 미로그인 시: 02 시작 화면 노출 (로그인하기 -> TestLoginActivity, 회원가입하기 -> SignUpActivity).
 *   알림으로 진입한 경우 목적지는 PendingNotificationManager에 보존
 * - 로그인 및 프로필 확인 시: FCM 토큰 동기화 후 [RoomListActivity] 또는
 *   푸시 알림 목적지([RoomMapActivity] -> [PlaceDetailActivity])로 이동 (스플래시 유지)
 * - 로그인되어 있으나 프로필 문서 누락 시: 스플래시 유지한 채 [TestLoginActivity]로 이동하여 닉네임 복구
 * - 프로필 확인 실패 시: 스플래시 해제 후 에러 다이얼로그 표시 (로그아웃 시 시작 화면에 유지)
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var binding: ActivityMainBinding
    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val userRepository: UserRepository by lazy { UserRepository(auth = auth) }
    private var isCheckingAuth = true
    private var isMotionPlayed = false
    private val roomRepository: RoomRepository by lazy { RoomRepository(auth = auth) }
    private val deviceTokenRepository: DeviceTokenRepository by lazy { DeviceTokenRepository.getInstance(applicationContext) }

    private var pendingInviteCodeToPass: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        splashScreen.setKeepOnScreenCondition { isCheckingAuth }

        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (savedInstanceState == null) {
            handleIncomingDeepLink(intent)
            handleIncomingNotification(intent)
        }

        // Apply system bar insets only to content container so background image covers full screen
        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.clContent.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        binding.btnTestLogin.setOnClickListener {
            val intent = Intent(this, TestLoginActivity::class.java)
            startActivity(intent)
        }

        binding.tvSignUp.setOnClickListener {
            val intent = Intent(this, SignUpActivity::class.java)
            startActivity(intent)
        }

        val currentUser = auth.currentUser
        if (currentUser == null) {
            showStartScreen(savedInstanceState)
        } else {
            binding.btnTestLogin.isEnabled = false
            verifyProfileAndNavigate(currentUser, savedInstanceState)
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

    private fun getInitialLogoScale(): Float {
        val splashWidth = resources.getDimension(R.dimen.splash_logo_width)
        val startWidth = resources.getDimension(R.dimen.start_logo_width)
        return splashWidth / startWidth
    }

    private fun showStartScreen(savedInstanceState: Bundle?) {
        isCheckingAuth = false
        if (savedInstanceState != null || isMotionPlayed) {
            showFinalState()
            return
        }

        binding.ivBackground.alpha = 0f
        binding.tvTagline.alpha = 0f
        binding.llBottomContainer.alpha = 0f

        val initialScale = getInitialLogoScale()
        binding.ivLogo.scaleX = initialScale
        binding.ivLogo.scaleY = initialScale

        binding.clContent.post {
            if (isDestroyed || isFinishing) return@post
            val contentHeight = binding.clContent.height
            val logoHeight = binding.ivLogo.height
            if (contentHeight > 0 && logoHeight > 0) {
                val startCenterY = (contentHeight / 2f) - (logoHeight / 2f)
                val targetY = binding.ivLogo.top.toFloat()
                binding.ivLogo.translationY = startCenterY - targetY
            } else {
                binding.ivLogo.translationY = 0f
            }

            playStartMotion()
        }
    }

    private fun playStartMotion() {
        if (isMotionPlayed) return
        isMotionPlayed = true

        binding.ivLogo.animate()
            .translationY(0f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(500)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()

        binding.ivBackground.animate()
            .alpha(1f)
            .setDuration(500)
            .start()

        binding.tvTagline.animate()
            .alpha(1f)
            .setStartDelay(200)
            .setDuration(400)
            .start()

        binding.llBottomContainer.animate()
            .alpha(1f)
            .setStartDelay(350)
            .setDuration(400)
            .start()
    }

    private fun showFinalState() {
        binding.ivBackground.alpha = 1f
        binding.tvTagline.alpha = 1f
        binding.llBottomContainer.alpha = 1f
        binding.ivLogo.translationY = 0f
        binding.ivLogo.scaleX = 1f
        binding.ivLogo.scaleY = 1f
    }

    private fun handleIncomingNotification(intent: Intent?) {
        if (intent == null) return
        val action = intent.action
        val type = intent.getStringExtra(FriendNotificationService.EXTRA_TYPE)
        val roomId = intent.getStringExtra(FriendNotificationService.EXTRA_ROOM_ID)
        val kakaoPlaceId = intent.getStringExtra(FriendNotificationService.EXTRA_KAKAO_PLACE_ID).orEmpty()
        val recipientUid = intent.getStringExtra(FriendNotificationService.EXTRA_RECIPIENT_UID)

        if ((action == FriendNotificationService.ACTION_FRIEND_PLACE_ADDED || type == FriendNotificationService.TYPE_PLACE_ADDED) &&
            !roomId.isNullOrBlank() && !recipientUid.isNullOrBlank()) {

            val destination = PendingNotificationDestination(
                roomId = roomId,
                kakaoPlaceId = kakaoPlaceId,
                recipientUid = recipientUid
            )
            PendingNotificationManager.savePendingDestination(applicationContext, destination)
        }
    }

    private fun verifyProfileAndNavigate(user: FirebaseUser, savedInstanceState: Bundle? = null) {
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
                    // Missing profile: go to Login directly without releasing splash
                    navigateToLogin()
                }
            } catch (e: CancellationException) {
                showStartScreen(savedInstanceState)
                throw e
            } catch (e: Exception) {
                showStartScreen(savedInstanceState)
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
                        isCheckingAuth = true
                        verifyProfileAndNavigate(user, savedInstanceState)
                    }
                    .setNegativeButton(getString(R.string.btn_logout)) { _, _ ->
                        auth.signOut()
                        showStartScreen(savedInstanceState)
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
                    if (result.kakaoPlaceId.isNotBlank()) {
                        val placeDetailIntent = PlaceDetailActivity.newIntent(this, result.roomId, result.kakaoPlaceId)
                        startActivities(arrayOf(roomMapIntent, placeDetailIntent))
                    } else {
                        startActivity(roomMapIntent)
                    }
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
        isCheckingAuth = false
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
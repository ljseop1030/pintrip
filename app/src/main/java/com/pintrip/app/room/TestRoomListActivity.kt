package com.pintrip.app.room

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.pintrip.app.BuildConfig
import com.pintrip.app.R
import com.pintrip.app.auth.TestLoginActivity
import com.pintrip.app.data.repository.UserRepository
import com.pintrip.app.databinding.ActivityTestRoomListBinding
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 개발(Debug) 전용 방 목록 화면.
 *
 * rooms/test_room_1 문서를 단건 직접 조회하여 카드 1개로 표시합니다.
 * 타 사용자 방 권한 보장을 위해 rooms 전체 쿼리는 실행하지 않습니다.
 */
class TestRoomListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTestRoomListBinding
    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val db: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
    private val userRepository: UserRepository by lazy { UserRepository(auth = auth) }

    companion object {
        private const val TEST_ROOM_ID = "test_room_1"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val currentUser = auth.currentUser
        // Debug 빌드가 아니거나 로그인되어 있지 않은 경우 진입 차단
        if (!BuildConfig.DEBUG || currentUser == null) {
            navigateToLogin()
            return
        }

        enableEdgeToEdge()
        binding = ActivityTestRoomListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        binding.btnLogout.setOnClickListener {
            auth.signOut()
            navigateToLogin()
        }

        binding.btnRetry.setOnClickListener {
            fetchTestRoom()
        }

        // test_room_1 카드 클릭 시 장소 테스트 화면(TestRoomActivity)으로 이동
        binding.roomCard.cardRoomItem.setOnClickListener {
            val intent = Intent(this, TestRoomActivity::class.java)
            startActivity(intent)
        }

        loadUserProfileHeader()
        fetchTestRoom()
    }

    private fun loadUserProfileHeader() {
        val currentUser = auth.currentUser ?: return
        lifecycleScope.launch {
            try {
                val profile = userRepository.getUserProfile(currentUser.uid)
                val nickname = profile?.nickname
                if (!nickname.isNullOrBlank()) {
                    binding.tvGreeting.text = getString(R.string.greeting_format, nickname)
                    binding.tvHeaderAvatar.text = nickname.take(1).uppercase()
                } else {
                    binding.tvGreeting.text = getString(R.string.greeting_default)
                    binding.tvHeaderAvatar.text = "U"
                }
            } catch (_: Exception) {
                binding.tvGreeting.text = getString(R.string.greeting_default)
                binding.tvHeaderAvatar.text = "U"
            }
        }
    }

    private fun fetchTestRoom() {
        showLoading()

        lifecycleScope.launch {
            try {
                val snapshot = db.collection("rooms").document(TEST_ROOM_ID).get().await()

                if (!snapshot.exists()) {
                    showError(getString(R.string.error_room_not_found))
                    return@launch
                }

                // 1. 방 정보 바인딩
                val name = snapshot.getString("name")
                if (name.isNullOrBlank()) {
                    showError(getString(R.string.error_invalid_room_name))
                    return@launch
                }

                val placeCount = snapshot.getLong("placeCount")?.toInt() ?: 0
                val startDate = snapshot.getTimestamp("startDate")
                val endDate = snapshot.getTimestamp("endDate")

                binding.roomCard.tvRoomName.text = name
                binding.roomCard.tvLocationBadge.text = getString(R.string.location_count_format, placeCount)

                // 일자 정보 처리 (null일 경우 숨김 처리)
                if (startDate != null && endDate != null) {
                    val dateFormat = SimpleDateFormat("MM.dd", Locale.getDefault())
                    val dateRange = "${dateFormat.format(startDate.toDate())} – ${dateFormat.format(endDate.toDate())}"
                    binding.roomCard.tvRoomDate.text = dateRange
                    binding.roomCard.tvRoomDate.visibility = View.VISIBLE
                } else {
                    binding.roomCard.tvRoomDate.visibility = View.GONE
                }

                showContent()
            } catch (e: com.google.firebase.firestore.FirebaseFirestoreException) {
                if (e.code == com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                    showError(getString(R.string.notice_no_participating_rooms))
                } else {
                    showError(getString(R.string.error_room_load_failed, e.localizedMessage ?: e.message.orEmpty()))
                }
            } catch (e: Exception) {
                showError(getString(R.string.error_room_load_failed, e.localizedMessage ?: e.message.orEmpty()))
            }
        }
    }

    private fun showLoading() {
        binding.pbLoading.visibility = View.VISIBLE
        binding.layoutError.visibility = View.GONE
        binding.layoutRoomContent.visibility = View.GONE
    }

    private fun showError(message: String) {
        binding.pbLoading.visibility = View.GONE
        binding.layoutError.visibility = View.VISIBLE
        binding.layoutRoomContent.visibility = View.GONE
        binding.tvErrorMessage.text = message
    }

    private fun showContent() {
        binding.pbLoading.visibility = View.GONE
        binding.layoutError.visibility = View.GONE
        binding.layoutRoomContent.visibility = View.VISIBLE
    }

    private fun navigateToLogin() {
        val intent = Intent(this, TestLoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }
}

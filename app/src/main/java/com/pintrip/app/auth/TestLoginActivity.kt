package com.pintrip.app.auth

import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.Source
import com.pintrip.app.BuildConfig
import com.pintrip.app.R
import com.pintrip.app.data.repository.UserRepository
import com.pintrip.app.databinding.ActivityTestLoginBinding
import com.pintrip.app.room.TestRoomListActivity
import kotlinx.coroutines.launch

/**
 * 개발(Debug) 및 정식 로그인 화면 (Figma S01).
 *
 * 이메일/비밀번호로 로그인하며, 프로필 문서(users/{uid})가 없는 경우
 * 화면에서 닉네임을 입력받아 프로필을 생성합니다.
 */
class TestLoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTestLoginBinding
    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val userRepository: UserRepository by lazy { UserRepository(auth = auth) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Debug 빌드가 아닌 경우 진입 차단
        if (!BuildConfig.DEBUG) {
            finish()
            return
        }

        enableEdgeToEdge()
        binding = ActivityTestLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        binding.btnLogin.setOnClickListener {
            performLogin()
        }

        binding.tvSignUpLink.setOnClickListener {
            startActivity(Intent(this, SignUpActivity::class.java))
        }

        // UI 세팅 후 이미 로그인된 경우 서버에서 프로필 확인 진행
        val currentUser = auth.currentUser
        if (currentUser != null) {
            binding.tvStatus.text = getString(R.string.status_logging_in)
            checkProfileAndNavigate(currentUser)
        } else {
            binding.tvStatus.text = getString(R.string.status_logged_out)
        }
    }

    private fun performLogin() {
        val email = binding.etEmail.text?.toString()?.trim().orEmpty()
        // 비밀번호는 입력한 그대로 사용 (앞뒤 공백 trim 제거 안 함)
        val password = binding.etPassword.text?.toString().orEmpty()

        if (email.isEmpty()) {
            binding.tilEmail.error = getString(R.string.error_empty_email)
            return
        } else {
            binding.tilEmail.error = null
        }

        if (password.isEmpty()) {
            binding.tilPassword.error = getString(R.string.error_empty_password)
            return
        } else {
            binding.tilPassword.error = null
        }

        binding.tvStatus.text = getString(R.string.status_logging_in)
        binding.btnLogin.isEnabled = false

        auth.signInWithEmailAndPassword(email, password)
            .addOnCompleteListener(this) { task ->
                binding.btnLogin.isEnabled = true
                if (task.isSuccessful) {
                    val user = auth.currentUser
                    if (user != null) {
                        checkProfileAndNavigate(user)
                    }
                } else {
                    val errorMsg = task.exception?.localizedMessage ?: getString(R.string.error_login_failed_default)
                    binding.tvStatus.text = getString(R.string.status_login_failed, errorMsg)
                }
            }
    }

    private fun checkProfileAndNavigate(user: FirebaseUser) {
        lifecycleScope.launch {
            try {
                // 로그인 시점에는 서버 기준(Source.SERVER)으로 실시간 프로필 존재 여부 확인
                val profile = userRepository.getUserProfile(user.uid, source = Source.SERVER)
                if (profile != null) {
                    navigateToTestRoomList()
                } else {
                    // 계정은 있으나 프로필 문서가 진짜로 존재하지 않는 경우: 닉네임 입력 다이얼로그 표시
                    promptProfileNickname(user)
                }
            } catch (e: Exception) {
                // 프로필 조회 실패(네트워크 오류/보안 규칙 오류 등) 시 문서를 '없음'으로 치부하지 않고 에러 안내 및 재시도/로그아웃 제공
                val errorMsg = e.localizedMessage ?: getString(R.string.error_profile_load_failed)
                binding.tvStatus.text = getString(R.string.status_login_failed, errorMsg)

                AlertDialog.Builder(this@TestLoginActivity)
                    .setTitle(getString(R.string.error_profile_load_failed))
                    .setMessage(errorMsg)
                    .setPositiveButton(getString(R.string.btn_retry)) { _, _ ->
                        checkProfileAndNavigate(user)
                    }
                    .setNegativeButton(getString(R.string.btn_logout)) { _, _ ->
                        auth.signOut()
                        binding.tvStatus.text = getString(R.string.status_logged_out)
                    }
                    .show()
            }
        }
    }

    private fun promptProfileNickname(user: FirebaseUser) {
        val input = EditText(this).apply {
            hint = getString(R.string.nickname_hint)
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.profile_dialog_title))
            .setMessage(getString(R.string.profile_dialog_message))
            .setView(input)
            .setCancelable(false)
            .setPositiveButton(getString(R.string.btn_save_profile)) { _, _ ->
                val nickname = input.text.toString().trim()
                if (nickname.isBlank()) {
                    Toast.makeText(this@TestLoginActivity, getString(R.string.error_empty_nickname), Toast.LENGTH_SHORT).show()
                    promptProfileNickname(user)
                    return@setPositiveButton
                }

                lifecycleScope.launch {
                    try {
                        userRepository.createUserProfile(
                            uid = user.uid,
                            nickname = nickname,
                            email = user.email.orEmpty(),
                        )
                        Toast.makeText(this@TestLoginActivity, getString(R.string.status_signup_success), Toast.LENGTH_SHORT).show()
                        navigateToTestRoomList()
                    } catch (e: Exception) {
                        val errorMsg = e.localizedMessage ?: getString(R.string.error_profile_save_failed)
                        binding.tvStatus.text = getString(R.string.status_login_failed, errorMsg)

                        AlertDialog.Builder(this@TestLoginActivity)
                            .setTitle(getString(R.string.error_profile_save_failed))
                            .setMessage(errorMsg)
                            .setPositiveButton(getString(R.string.btn_retry_profile)) { _, _ ->
                                promptProfileNickname(user)
                            }
                            .setNegativeButton(getString(R.string.btn_logout)) { _, _ ->
                                auth.signOut()
                                binding.tvStatus.text = getString(R.string.status_logged_out)
                            }
                            .show()
                    }
                }
            }
            .setNegativeButton(getString(R.string.btn_logout)) { _, _ ->
                auth.signOut()
                binding.tvStatus.text = getString(R.string.status_logged_out)
            }
            .show()
    }

    private fun navigateToTestRoomList() {
        startActivity(Intent(this, TestRoomListActivity::class.java))
        finish()
    }
}

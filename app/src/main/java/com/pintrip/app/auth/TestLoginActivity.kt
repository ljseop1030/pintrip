package com.pintrip.app.auth

import android.content.Intent
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.firebase.auth.FirebaseAuth
import com.pintrip.app.BuildConfig
import com.pintrip.app.R
import com.pintrip.app.databinding.ActivityTestLoginBinding
import com.pintrip.app.room.TestRoomListActivity

/**
 * 개발(Debug) 전용 테스트 로그인 화면.
 *
 * 로그인에 성공하면 방 목록 화면([TestRoomListActivity])으로 이동하며,
 * 비밀번호 입력 시 공백을 임의로 제거하지 않습니다.
 */
class TestLoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTestLoginBinding
    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Debug 빌드가 아닌 경우 진입 차단
        if (!BuildConfig.DEBUG) {
            finish()
            return
        }

        // 이미 로그인된 상태라면 바로 방 목록 화면으로 이동
        if (auth.currentUser != null) {
            navigateToTestRoomList()
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

        binding.tvStatus.text = getString(R.string.status_logged_out)

        binding.btnLogin.setOnClickListener {
            performLogin()
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
                    navigateToTestRoomList()
                } else {
                    val errorMsg = task.exception?.localizedMessage ?: getString(R.string.error_login_failed_default)
                    binding.tvStatus.text = getString(R.string.status_login_failed, errorMsg)
                }
            }
    }

    private fun navigateToTestRoomList() {
        startActivity(Intent(this, TestRoomListActivity::class.java))
        finish()
    }
}

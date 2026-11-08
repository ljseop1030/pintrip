package com.pintrip.app.auth

import android.content.Intent
import android.os.Bundle
import android.util.Patterns
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.firestore.Source
import com.pintrip.app.BuildConfig
import com.pintrip.app.R
import com.pintrip.app.data.repository.UserRepository
import com.pintrip.app.databinding.ActivitySignUpBinding
import com.pintrip.app.room.TestRoomListActivity
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * 개발(Debug) 및 정식 회원가입 화면 (Figma S02).
 *
 * 닉네임, 이메일, 비밀번호, 비밀번호 확인을 입력받아 Firebase Auth 계정을 생성하고
 * Firestore `users/{uid}`에 프로필 정보(nickname, email, avatarColor, createdAt)를 저장합니다.
 * 비밀번호는 절대로 Firestore나 로그에 저장하지 않으며 입력된 형태 그대로 사용합니다.
 */
class SignUpActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySignUpBinding
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
        binding = ActivitySignUpBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        binding.ivBack.setOnClickListener {
            finish()
        }

        binding.btnSignUp.setOnClickListener {
            performSignUp()
        }
    }

    private fun performSignUp() {
        val nickname = binding.etNickname.text?.toString()?.trim().orEmpty()
        val email = binding.etEmail.text?.toString()?.trim().orEmpty()
        // 비밀번호 및 비밀번호 확인은 입력된 그대로 사용 (trim 제거 안 함)
        val password = binding.etPassword.text?.toString().orEmpty()
        val confirmPassword = binding.etConfirmPassword.text?.toString().orEmpty()

        // 1. 유효성 검증
        if (nickname.isEmpty()) {
            binding.tilNickname.error = getString(R.string.error_empty_nickname)
            return
        } else {
            binding.tilNickname.error = null
        }

        if (email.isEmpty() || !Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            binding.tilEmail.error = getString(R.string.error_invalid_email)
            return
        } else {
            binding.tilEmail.error = null
        }

        if (password.length < 8) {
            binding.tilPassword.error = getString(R.string.error_password_too_short)
            return
        } else {
            binding.tilPassword.error = null
        }

        if (password != confirmPassword) {
            binding.tilConfirmPassword.error = getString(R.string.error_password_mismatch)
            return
        } else {
            binding.tilConfirmPassword.error = null
        }

        if (!binding.cbTerms.isChecked) {
            binding.tvStatus.text = getString(R.string.error_terms_required)
            return
        }

        binding.tvStatus.text = getString(R.string.status_creating_account)
        binding.btnSignUp.isEnabled = false

        lifecycleScope.launch {
            try {
                var uid: String? = null
                var isExistingAuthAccount = false

                // (a) Auth 계정 생성 시도
                try {
                    val authResult = auth.createUserWithEmailAndPassword(email, password).await()
                    uid = authResult.user?.uid
                } catch (_: FirebaseAuthUserCollisionException) {
                    // 이메일 이미 존재: 입력한 이메일/비밀번호로 인증 시도
                    try {
                        val loginResult = auth.signInWithEmailAndPassword(email, password).await()
                        uid = loginResult.user?.uid
                        isExistingAuthAccount = true
                    } catch (_: Exception) {
                        // 비밀번호 불일치 등 인가 실패 시 이메일 중복 에러 안내
                        binding.tvStatus.text = getString(R.string.error_email_already_in_use)
                        return@launch
                    }
                }

                checkNotNull(uid) { "사용자 UID를 가져오지 못했습니다." }

                // (b) 인증 성공 후 프로필 처리 단계 분리 실행
                performProfileStep(uid, nickname, email, isExistingAuthAccount)

            } catch (e: Exception) {
                val errorMsg = e.localizedMessage ?: getString(R.string.error_login_failed_default)
                binding.tvStatus.text = getString(R.string.status_login_failed, errorMsg)
            } finally {
                binding.btnSignUp.isEnabled = true
            }
        }
    }

    private fun performProfileStep(
        uid: String,
        nickname: String,
        email: String,
        isExistingAuthAccount: Boolean,
    ) {
        lifecycleScope.launch {
            try {
                binding.btnSignUp.isEnabled = false
                binding.tvStatus.text = getString(R.string.status_creating_account)

                // 1. 프로필 존재 확인 (Source.SERVER)
                val existingProfile = try {
                    userRepository.getUserProfile(uid, source = Source.SERVER)
                } catch (e: Exception) {
                    val errorMsg = e.localizedMessage ?: getString(R.string.error_profile_load_failed)
                    binding.tvStatus.text = getString(R.string.status_auth_success_profile_failed)

                    AlertDialog.Builder(this@SignUpActivity)
                        .setTitle(getString(R.string.error_profile_load_failed))
                        .setMessage(errorMsg)
                        .setPositiveButton(getString(R.string.btn_retry_profile)) { _, _ ->
                            performProfileStep(uid, nickname, email, isExistingAuthAccount)
                        }
                        .setNegativeButton(getString(R.string.btn_logout)) { _, _ ->
                            auth.signOut()
                            binding.tvStatus.text = getString(R.string.status_logged_out)
                        }
                        .show()
                    return@launch
                }

                val statusTextRes = if (existingProfile != null) {
                    // 프로필이 이미 수립된 기존 계정: 기존 데이터 보존
                    R.string.status_existing_account_logged_in
                } else {
                    // 프로필 생성이 없는 계정: 프로필 생성
                    try {
                        userRepository.createUserProfile(
                            uid = uid,
                            nickname = nickname,
                            email = email,
                        )
                        if (isExistingAuthAccount) {
                            R.string.status_profile_restored
                        } else {
                            R.string.status_signup_and_profile_success
                        }
                    } catch (e: Exception) {
                        val errorMsg = e.localizedMessage ?: getString(R.string.error_profile_save_failed)
                        binding.tvStatus.text = getString(R.string.status_auth_success_profile_failed)

                        AlertDialog.Builder(this@SignUpActivity)
                            .setTitle(getString(R.string.error_profile_save_failed))
                            .setMessage(errorMsg)
                            .setPositiveButton(getString(R.string.btn_retry_profile)) { _, _ ->
                                performProfileStep(uid, nickname, email, isExistingAuthAccount)
                            }
                            .setNegativeButton(getString(R.string.btn_logout)) { _, _ ->
                                auth.signOut()
                                binding.tvStatus.text = getString(R.string.status_logged_out)
                            }
                            .show()
                        return@launch
                    }
                }

                binding.tvStatus.text = getString(statusTextRes)
                Toast.makeText(this@SignUpActivity, getString(statusTextRes), Toast.LENGTH_SHORT).show()

                showSignUpCompleteDialog()

            } catch (e: Exception) {
                val errorMsg = e.localizedMessage ?: getString(R.string.error_profile_save_failed)
                binding.tvStatus.text = getString(R.string.status_auth_success_profile_failed)
            } finally {
                binding.btnSignUp.isEnabled = true
            }
        }
    }

    private fun showSignUpCompleteDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.notice_new_user_title))
            .setMessage(getString(R.string.notice_new_user_message))
            .setCancelable(false)
            .setPositiveButton(getString(R.string.btn_login)) { _, _ ->
                val intent = Intent(this, TestRoomListActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                startActivity(intent)
                finish()
            }
            .show()
    }
}

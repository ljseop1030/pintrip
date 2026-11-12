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
import com.pintrip.app.R
import com.pintrip.app.data.repository.UserRepository
import com.pintrip.app.databinding.ActivityTestLoginBinding
import com.pintrip.app.room.RoomListActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * 정식 로그인 화면 (Figma S01).
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

        val currentUser = auth.currentUser
        if (currentUser != null) {
            binding.tvStatus.text = getString(R.string.status_logging_in)
            binding.btnLogin.isEnabled = false
            checkProfileAndNavigate(currentUser)
        } else {
            binding.tvStatus.text = getString(R.string.status_logged_out)
            binding.btnLogin.isEnabled = true
        }
    }

    private fun performLogin() {
        val email = binding.etEmail.text?.toString()?.trim().orEmpty()
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

        lifecycleScope.launch {
            try {
                val authResult = auth.signInWithEmailAndPassword(email, password).await()
                val user = authResult.user
                if (user != null) {
                    checkProfileAndNavigate(user)
                } else {
                    binding.btnLogin.isEnabled = true
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                binding.btnLogin.isEnabled = true
                val errorMsg = e.localizedMessage ?: getString(R.string.error_login_failed_default)
                binding.tvStatus.text = getString(R.string.status_login_failed, errorMsg)
            }
        }
    }

    private fun checkProfileAndNavigate(user: FirebaseUser) {
        lifecycleScope.launch {
            try {
                binding.btnLogin.isEnabled = false
                val profile = userRepository.getUserProfile(user.uid, source = Source.SERVER)
                if (profile != null) {
                    if (profile.nickname.isNotBlank()) {
                        navigateToRoomList()
                    } else {
                        showCorruptedProfileDialog()
                    }
                } else {
                    promptProfileNickname(user)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                binding.btnLogin.isEnabled = true
                val errorMsg = e.localizedMessage ?: getString(R.string.error_profile_load_failed)
                binding.tvStatus.text = getString(R.string.status_login_failed, errorMsg)

                AlertDialog.Builder(this@TestLoginActivity)
                    .setTitle(getString(R.string.error_profile_load_failed))
                    .setMessage(errorMsg)
                    .setCancelable(false)
                    .setPositiveButton(getString(R.string.btn_retry)) { _, _ ->
                        checkProfileAndNavigate(user)
                    }
                    .setNegativeButton(getString(R.string.btn_logout)) { _, _ ->
                        auth.signOut()
                        binding.tvStatus.text = getString(R.string.status_logged_out)
                        binding.btnLogin.isEnabled = true
                    }
                    .show()
            }
        }
    }

    private fun showCorruptedProfileDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.error_profile_corrupted_title))
            .setMessage(getString(R.string.error_profile_corrupted_message))
            .setCancelable(false)
            .setNegativeButton(getString(R.string.btn_logout)) { _, _ ->
                auth.signOut()
                binding.tvStatus.text = getString(R.string.status_logged_out)
                binding.btnLogin.isEnabled = true
            }
            .show()
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
                        navigateToRoomList()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        binding.btnLogin.isEnabled = true
                        val errorMsg = e.localizedMessage ?: getString(R.string.error_profile_save_failed)
                        binding.tvStatus.text = getString(R.string.status_login_failed, errorMsg)

                        AlertDialog.Builder(this@TestLoginActivity)
                            .setTitle(getString(R.string.error_profile_save_failed))
                            .setMessage(errorMsg)
                            .setCancelable(false)
                            .setPositiveButton(getString(R.string.btn_retry_profile)) { _, _ ->
                                promptProfileNickname(user)
                            }
                            .setNegativeButton(getString(R.string.btn_logout)) { _, _ ->
                                auth.signOut()
                                binding.tvStatus.text = getString(R.string.status_logged_out)
                                binding.btnLogin.isEnabled = true
                            }
                            .show()
                    }
                }
            }
            .setNegativeButton(getString(R.string.btn_logout)) { _, _ ->
                auth.signOut()
                binding.tvStatus.text = getString(R.string.status_logged_out)
                binding.btnLogin.isEnabled = true
            }
            .show()
    }

    private fun navigateToRoomList() {
        val intent = Intent(this, RoomListActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }
}

package com.pintrip.app.auth

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ScrollView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doOnTextChanged
import com.google.firebase.auth.FirebaseUser
import com.pintrip.app.R
import com.pintrip.app.databinding.ActivityTestLoginBinding
import com.pintrip.app.room.RoomListActivity

/**
 * 정식 로그인 화면 (Figma S01).
 *
 * 이메일/비밀번호로 로그인하며, 프로필 문서(users/{uid})가 없는 경우
 * 화면에서 닉네임을 입력받아 프로필을 생성합니다.
 */
class TestLoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTestLoginBinding
    private val viewModel: LoginViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        binding = ActivityTestLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())

            v.setPadding(systemBars.left, systemBars.top, systemBars.right, 0)
            val bottomPadding = maxOf(systemBars.bottom, ime.bottom)
            binding.scrollView.setPadding(0, 0, 0, bottomPadding)

            insets
        }

        setupInputListeners()
        observeViewModel()

        binding.btnLogin.setOnClickListener {
            viewModel.performLogin(
                emailRaw = binding.etEmail.text?.toString().orEmpty(),
                passwordRaw = binding.etPassword.text?.toString().orEmpty(),
            )
        }

        binding.tvSignUpLink.setOnClickListener {
            startActivity(Intent(this, SignUpActivity::class.java))
        }

        viewModel.checkExistingSession()
    }

    private fun setupInputListeners() {
        binding.etEmail.doOnTextChanged { _, _, _, _ ->
            if (binding.etEmail.hasFocus()) {
                binding.tilEmail.error = null
                binding.tvFormError.visibility = View.GONE
                viewModel.clearEmailError()
            }
        }

        binding.etPassword.doOnTextChanged { _, _, _, _ ->
            if (binding.etPassword.hasFocus()) {
                binding.tilPassword.error = null
                binding.tvFormError.visibility = View.GONE
                viewModel.clearPasswordError()
            }
        }
    }

    private fun observeViewModel() {
        viewModel.uiState.observe(this) { state ->
            when (state) {
                is LoginUiState.Idle -> {
                    binding.btnLogin.isEnabled = true
                    binding.btnLogin.text = getString(R.string.btn_login)
                    clearAllErrorsFromUi()
                }

                is LoginUiState.Loading -> {
                    binding.btnLogin.isEnabled = false
                    binding.btnLogin.text = getString(R.string.login_status_signing_in)
                    clearAllErrorsFromUi()
                }

                is LoginUiState.InputError -> {
                    binding.btnLogin.isEnabled = true
                    binding.btnLogin.text = getString(R.string.btn_login)

                    binding.tilEmail.error = state.emailErrorResId?.let { getString(it) }
                    binding.tilPassword.error = state.passwordErrorResId?.let { getString(it) }
                    binding.tvFormError.visibility = View.GONE
                }

                is LoginUiState.FormError -> {
                    binding.btnLogin.isEnabled = true
                    binding.btnLogin.text = getString(R.string.btn_login)

                    binding.tilEmail.error = null
                    binding.tilPassword.error = null
                    binding.tvFormError.text = getString(state.messageResId)
                    binding.tvFormError.visibility = View.VISIBLE

                    binding.scrollView.post {
                        if (binding.tvFormError.visibility == View.VISIBLE) {
                            binding.scrollView.scrollToEnsureViewFullyVisible(binding.tvFormError)
                        }
                    }
                }

                is LoginUiState.ProfileMissing -> {
                    binding.btnLogin.isEnabled = true
                    binding.btnLogin.text = getString(R.string.btn_login)
                    clearAllErrorsFromUi()
                    promptProfileNickname(state.user)
                }

                is LoginUiState.ProfileCorrupted -> {
                    binding.btnLogin.isEnabled = true
                    binding.btnLogin.text = getString(R.string.btn_login)
                    clearAllErrorsFromUi()
                    showCorruptedProfileDialog()
                }

                is LoginUiState.ProfileLoadError -> {
                    binding.btnLogin.isEnabled = true
                    binding.btnLogin.text = getString(R.string.btn_login)
                    clearAllErrorsFromUi()
                    showProfileLoadErrorDialog(state.user)
                }

                is LoginUiState.ProfileSaveError -> {
                    binding.btnLogin.isEnabled = true
                    binding.btnLogin.text = getString(R.string.btn_login)
                    clearAllErrorsFromUi()
                    showProfileSaveErrorDialog(state.user)
                }

                is LoginUiState.Success -> {
                    navigateToRoomList()
                }
            }
        }
    }

    private fun clearAllErrorsFromUi() {
        binding.tilEmail.error = null
        binding.tilPassword.error = null
        binding.tvFormError.visibility = View.GONE
    }



    private fun showProfileLoadErrorDialog(user: FirebaseUser?) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.login_error_profile_title))
            .setMessage(getString(R.string.login_error_profile_load))
            .setCancelable(false)
            .setPositiveButton(getString(R.string.login_btn_retry)) { _, _ ->
                if (user != null) {
                    viewModel.checkProfileAndNavigate(user)
                } else {
                    viewModel.signOut()
                }
            }
            .setNegativeButton(getString(R.string.login_btn_sign_out)) { _, _ ->
                viewModel.signOut()
            }
            .show()
    }

    private fun showProfileSaveErrorDialog(user: FirebaseUser?) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.login_error_profile_title))
            .setMessage(getString(R.string.login_error_profile_save))
            .setCancelable(false)
            .setPositiveButton(getString(R.string.login_btn_retry)) { _, _ ->
                if (user != null) {
                    promptProfileNickname(user)
                } else {
                    viewModel.signOut()
                }
            }
            .setNegativeButton(getString(R.string.login_btn_sign_out)) { _, _ ->
                viewModel.signOut()
            }
            .show()
    }

    private fun showCorruptedProfileDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.login_dialog_profile_corrupted_title))
            .setMessage(getString(R.string.login_dialog_profile_corrupted_message))
            .setCancelable(false)
            .setNegativeButton(getString(R.string.login_btn_sign_out)) { _, _ ->
                viewModel.signOut()
            }
            .show()
    }

    private fun promptProfileNickname(user: FirebaseUser?) {
        val input = EditText(this).apply {
            hint = getString(R.string.nickname_hint)
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.login_dialog_profile_title))
            .setMessage(getString(R.string.login_dialog_profile_message))
            .setView(input)
            .setCancelable(false)
            .setPositiveButton(getString(R.string.login_btn_save)) { _, _ ->
                val nickname = input.text.toString().trim()
                if (nickname.isBlank()) {
                    Toast.makeText(
                        this@TestLoginActivity,
                        getString(R.string.login_error_empty_nickname),
                        Toast.LENGTH_SHORT,
                    ).show()
                    promptProfileNickname(user)
                    return@setPositiveButton
                }
                viewModel.saveProfileNickname(user, nickname)
            }
            .setNegativeButton(getString(R.string.login_btn_sign_out)) { _, _ ->
                viewModel.signOut()
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

package com.pintrip.app.auth

import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.ScrollView
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doOnTextChanged
import com.google.firebase.auth.FirebaseUser
import com.pintrip.app.R
import com.pintrip.app.databinding.ActivitySignUpBinding
import com.pintrip.app.room.RoomListActivity

/**
 * 정식 회원가입 화면 (Figma S02).
 *
 * 닉네임, 이메일, 비밀번호, 비밀번호 확인을 입력받아 Firebase Auth 계정을 생성하고
 * Firestore `users/{uid}`에 프로필 정보(nickname, email, avatarColor, createdAt)를 저장합니다.
 */
class SignUpActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySignUpBinding
    private val viewModel: SignUpViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        binding = ActivitySignUpBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())

            v.setPadding(systemBars.left, systemBars.top, systemBars.right, 0)
            val bottomPadding = maxOf(systemBars.bottom, ime.bottom)
            binding.scrollView.setPadding(0, 0, 0, bottomPadding)

            insets
        }

        binding.ivBack.setOnClickListener {
            finish()
        }

        setupInputListeners()
        observeViewModel()

        binding.btnSignUp.setOnClickListener {
            viewModel.performSignUp(
                nicknameRaw = binding.etNickname.text?.toString().orEmpty(),
                emailRaw = binding.etEmail.text?.toString().orEmpty(),
                passwordRaw = binding.etPassword.text?.toString().orEmpty(),
                confirmPasswordRaw = binding.etConfirmPassword.text?.toString().orEmpty(),
                isTermsChecked = binding.cbTerms.isChecked,
            )
        }
    }

    private fun setupInputListeners() {
        binding.etNickname.doOnTextChanged { _, _, _, _ ->
            if (binding.etNickname.hasFocus()) {
                binding.tilNickname.error = null
                binding.tvFormError.visibility = View.GONE
                viewModel.clearNicknameError()
            }
        }

        binding.etEmail.doOnTextChanged { _, _, _, _ ->
            if (binding.etEmail.hasFocus()) {
                binding.tilEmail.error = null
                binding.tvFormError.visibility = View.GONE
                viewModel.clearEmailError()
            }
        }

        binding.etPassword.doOnTextChanged { text, _, _, _ ->
            if (binding.etPassword.hasFocus()) {
                binding.tilPassword.error = null
                binding.tvFormError.visibility = View.GONE
                viewModel.clearPasswordError(
                    passwordCurrent = text?.toString().orEmpty(),
                    confirmPasswordCurrent = binding.etConfirmPassword.text?.toString().orEmpty(),
                )
            }
        }

        binding.etConfirmPassword.doOnTextChanged { _, _, _, _ ->
            if (binding.etConfirmPassword.hasFocus()) {
                binding.tilConfirmPassword.error = null
                binding.tvFormError.visibility = View.GONE
                viewModel.clearConfirmPasswordError()
            }
        }

        binding.cbTerms.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                binding.tvTermsError.visibility = View.GONE
                binding.tvFormError.visibility = View.GONE
                viewModel.clearTermsError()
            }
        }
    }

    private fun observeViewModel() {
        viewModel.uiState.observe(this) { state ->
            when (state) {
                is SignUpUiState.Idle -> {
                    binding.btnSignUp.isEnabled = true
                    binding.btnSignUp.text = getString(R.string.btn_signup)
                    clearAllErrorsFromUi()
                }

                is SignUpUiState.Loading -> {
                    binding.btnSignUp.isEnabled = false
                    binding.btnSignUp.text = getString(R.string.signup_status_signing_up)
                    clearAllErrorsFromUi()
                }

                is SignUpUiState.InputError -> {
                    binding.btnSignUp.isEnabled = true
                    binding.btnSignUp.text = getString(R.string.btn_signup)

                    binding.tilNickname.error = state.nicknameErrorResId?.let { getString(it) }
                    binding.tilEmail.error = state.emailErrorResId?.let { getString(it) }
                    binding.tilPassword.error = state.passwordErrorResId?.let { getString(it) }
                    binding.tilConfirmPassword.error = state.confirmPasswordErrorResId?.let { getString(it) }

                    if (state.termsErrorResId != null) {
                        binding.tvTermsError.text = getString(state.termsErrorResId)
                        binding.tvTermsError.visibility = View.VISIBLE
                    } else {
                        binding.tvTermsError.visibility = View.GONE
                    }
                    binding.tvFormError.visibility = View.GONE

                    binding.scrollView.post {
                        val targetView = when {
                            state.nicknameErrorResId != null -> binding.tilNickname
                            state.emailErrorResId != null -> binding.tilEmail
                            state.passwordErrorResId != null -> binding.tilPassword
                            state.confirmPasswordErrorResId != null -> binding.tilConfirmPassword
                            state.termsErrorResId != null -> binding.cbTerms
                            else -> null
                        }
                        targetView?.let { view ->
                            scrollToEnsureViewFullyVisible(binding.scrollView, view)
                        }
                    }
                }

                is SignUpUiState.FormError -> {
                    binding.btnSignUp.isEnabled = true
                    binding.btnSignUp.text = getString(R.string.btn_signup)

                    binding.tilNickname.error = null
                    binding.tilEmail.error = null
                    binding.tilPassword.error = null
                    binding.tilConfirmPassword.error = null
                    binding.tvTermsError.visibility = View.GONE
                    binding.tvFormError.text = getString(state.messageResId)
                    binding.tvFormError.visibility = View.VISIBLE

                    binding.scrollView.post {
                        scrollToEnsureViewFullyVisible(binding.scrollView, binding.tvFormError)
                    }
                }

                is SignUpUiState.ProfileLoadError -> {
                    binding.btnSignUp.isEnabled = true
                    binding.btnSignUp.text = getString(R.string.btn_signup)
                    clearAllErrorsFromUi()
                    showProfileLoadErrorDialog(state.user, state.uid, state.nickname, state.email, state.isExistingAuthAccount)
                }

                is SignUpUiState.ProfileSaveError -> {
                    binding.btnSignUp.isEnabled = true
                    binding.btnSignUp.text = getString(R.string.btn_signup)
                    clearAllErrorsFromUi()
                    showProfileSaveErrorDialog(state.user, state.uid, state.nickname, state.email, state.isExistingAuthAccount)
                }

                is SignUpUiState.ProfileCorrupted -> {
                    binding.btnSignUp.isEnabled = true
                    binding.btnSignUp.text = getString(R.string.btn_signup)
                    clearAllErrorsFromUi()
                    showCorruptedProfileDialog()
                }

                is SignUpUiState.SignUpComplete -> {
                    binding.btnSignUp.isEnabled = true
                    binding.btnSignUp.text = getString(R.string.btn_signup)
                    clearAllErrorsFromUi()
                    showSignUpCompleteDialog(state.isExistingAccount, state.isProfileRestored)
                }
            }
        }
    }

    private fun clearAllErrorsFromUi() {
        binding.tilNickname.error = null
        binding.tilEmail.error = null
        binding.tilPassword.error = null
        binding.tilConfirmPassword.error = null
        binding.tvTermsError.visibility = View.GONE
        binding.tvFormError.visibility = View.GONE
    }

    private fun scrollToEnsureViewFullyVisible(scrollView: ScrollView, targetView: View) {
        if (targetView.visibility != View.VISIBLE) return

        val scrollRect = Rect()
        scrollView.getHitRect(scrollRect)
        scrollRect.top += scrollView.paddingTop
        scrollRect.bottom -= scrollView.paddingBottom

        val targetRect = Rect()
        targetView.getDrawingRect(targetRect)
        scrollView.offsetDescendantRectToMyCoords(targetView, targetRect)

        if (!scrollRect.contains(targetRect)) {
            val marginPx = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                16f,
                scrollView.resources.displayMetrics,
            ).toInt()
            val targetY = maxOf(0, targetRect.top - marginPx)
            scrollView.smoothScrollTo(0, targetY)
        }
    }

    private fun showProfileLoadErrorDialog(
        user: FirebaseUser?,
        uid: String,
        nickname: String,
        email: String,
        isExistingAuthAccount: Boolean,
    ) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.signup_error_profile_title))
            .setMessage(getString(R.string.signup_error_profile_load))
            .setCancelable(false)
            .setPositiveButton(getString(R.string.signup_btn_retry)) { _, _ ->
                viewModel.retryProfileStep(user, uid, nickname, email, isExistingAuthAccount)
            }
            .setNegativeButton(getString(R.string.signup_btn_sign_out)) { _, _ ->
                viewModel.signOut()
            }
            .show()
    }

    private fun showProfileSaveErrorDialog(
        user: FirebaseUser?,
        uid: String,
        nickname: String,
        email: String,
        isExistingAuthAccount: Boolean,
    ) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.signup_error_profile_title))
            .setMessage(getString(R.string.signup_error_profile_save))
            .setCancelable(false)
            .setPositiveButton(getString(R.string.signup_btn_retry)) { _, _ ->
                viewModel.retryProfileStep(user, uid, nickname, email, isExistingAuthAccount)
            }
            .setNegativeButton(getString(R.string.signup_btn_sign_out)) { _, _ ->
                viewModel.signOut()
            }
            .show()
    }

    private fun showCorruptedProfileDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.signup_dialog_corrupted_title))
            .setMessage(getString(R.string.signup_dialog_corrupted_message))
            .setCancelable(false)
            .setNegativeButton(getString(R.string.signup_btn_sign_out)) { _, _ ->
                viewModel.signOut()
            }
            .show()
    }

    private fun showSignUpCompleteDialog(isExistingAccount: Boolean, isProfileRestored: Boolean) {
        val (titleRes, messageRes) = if (isExistingAccount) {
            Pair(R.string.signup_restored_title, R.string.signup_restored_message)
        } else {
            Pair(R.string.signup_complete_title, R.string.signup_complete_message)
        }

        AlertDialog.Builder(this)
            .setTitle(getString(titleRes))
            .setMessage(getString(messageRes))
            .setCancelable(false)
            .setPositiveButton(getString(R.string.signup_btn_get_started)) { _, _ ->
                val intent = Intent(this, RoomListActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                startActivity(intent)
                finish()
            }
            .show()
    }
}

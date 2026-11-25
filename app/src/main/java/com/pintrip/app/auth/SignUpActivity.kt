package com.pintrip.app.auth

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doOnTextChanged
import androidx.lifecycle.Lifecycle
import com.pintrip.app.R
import com.pintrip.app.databinding.ActivitySignUpBinding
import com.pintrip.app.room.RoomListActivity

/**
 * 정식 회원가입 화면 (Figma S02).
 *
 * 닉네임, 이메일, 비밀번호, 비밀번호 확인을 입력받아 Firebase Auth 계정을 생성하고
 * Firestore `users/{uid}`에 프로필 정보(nickname, email, avatarColor, createdAt)를 저장합니다.
 */
class SignUpActivity : AppCompatActivity(),
    ProfileErrorDialogFragment.Listener,
    ProfileCorruptedDialogFragment.Listener,
    SignUpCompleteDialogFragment.Listener {

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

    override fun onResumeFragments() {
        super.onResumeFragments()
        processPendingEventIfSafe()
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
                            state.termsErrorResId != null -> binding.tvTermsError
                            else -> null
                        }
                        targetView?.let { view ->
                            if (view.visibility == View.VISIBLE) {
                                binding.scrollView.scrollToEnsureViewFullyVisible(view)
                            }
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
                        if (binding.tvFormError.visibility == View.VISIBLE) {
                            binding.scrollView.scrollToEnsureViewFullyVisible(binding.tvFormError)
                        }
                    }
                }
            }
        }

        viewModel.events.observe(this) {
            processPendingEventIfSafe()
        }
    }

    private fun processPendingEventIfSafe() {
        if (isFinishing || isDestroyed || supportFragmentManager.isStateSaved || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            return
        }
        val eventWrapper = viewModel.events.value ?: return
        if (!eventWrapper.hasBeenHandled) {
            eventWrapper.getContentIfNotHandled()?.let { handleSignUpEvent(it) }
        }
    }

    private fun handleSignUpEvent(event: SignUpEvent) {
        when (event) {
            is SignUpEvent.ShowProfileLoadError -> {
                showErrorDialog(R.string.signup_error_profile_title, R.string.signup_error_profile_load, false)
            }

            is SignUpEvent.ShowProfileSaveError -> {
                showErrorDialog(R.string.signup_error_profile_title, R.string.signup_error_profile_save, true)
            }

            is SignUpEvent.ShowProfileCorrupted -> {
                showCorruptedDialog()
            }

            is SignUpEvent.ShowSignUpComplete -> {
                showSignUpCompleteDialog(event.isExistingAccount)
            }

            is SignUpEvent.NavigateToRoomList -> {
                navigateToRoomList()
            }
        }
    }

    private fun showErrorDialog(titleRes: Int, messageRes: Int, isSaveError: Boolean) {
        if (isFinishing || isDestroyed || supportFragmentManager.isStateSaved) return
        val tag = if (isSaveError) ProfileErrorDialogFragment.TAG_SAVE_ERROR else ProfileErrorDialogFragment.TAG_LOAD_ERROR
        if (supportFragmentManager.findFragmentByTag(tag) == null) {
            ProfileErrorDialogFragment.newInstance(titleRes, messageRes, isSaveError)
                .show(supportFragmentManager, tag)
        }
    }

    private fun showCorruptedDialog() {
        if (isFinishing || isDestroyed || supportFragmentManager.isStateSaved) return
        if (supportFragmentManager.findFragmentByTag(ProfileCorruptedDialogFragment.TAG) == null) {
            ProfileCorruptedDialogFragment.newInstance(
                R.string.signup_dialog_corrupted_title,
                R.string.signup_dialog_corrupted_message,
            ).show(supportFragmentManager, ProfileCorruptedDialogFragment.TAG)
        }
    }

    private fun showSignUpCompleteDialog(isExistingAccount: Boolean) {
        if (isFinishing || isDestroyed || supportFragmentManager.isStateSaved) return
        val (titleRes, messageRes) = if (isExistingAccount) {
            Pair(R.string.signup_restored_title, R.string.signup_restored_message)
        } else {
            Pair(R.string.signup_complete_title, R.string.signup_complete_message)
        }

        if (supportFragmentManager.findFragmentByTag(SignUpCompleteDialogFragment.TAG) == null) {
            SignUpCompleteDialogFragment.newInstance(titleRes, messageRes)
                .show(supportFragmentManager, SignUpCompleteDialogFragment.TAG)
        }
    }

    override fun onRetryProfile(isSaveError: Boolean) {
        viewModel.retryProfileStep()
    }

    override fun onSignOutFromErrorDialog() {
        viewModel.signOut()
    }

    override fun onSignOutFromCorruptedDialog() {
        viewModel.signOut()
    }

    override fun onGetStartedFromSignUpComplete() {
        viewModel.onGetStartedClicked()
    }

    private fun navigateToRoomList() {
        val intent = Intent(this, RoomListActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }

    private fun clearAllErrorsFromUi() {
        binding.tilNickname.error = null
        binding.tilEmail.error = null
        binding.tilPassword.error = null
        binding.tilConfirmPassword.error = null
        binding.tvTermsError.visibility = View.GONE
        binding.tvFormError.visibility = View.GONE
    }
}

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
import com.pintrip.app.databinding.ActivityTestLoginBinding
import com.pintrip.app.room.RoomListActivity

/**
 * 정식 로그인 화면 (Figma S01).
 *
 * 이메일/비밀번호로 로그인하며, 프로필 문서(users/{uid})가 없는 경우
 * 화면에서 닉네임을 입력받아 프로필을 생성합니다.
 */
class TestLoginActivity : AppCompatActivity(),
    ProfileNicknameDialogFragment.Listener,
    ProfileErrorDialogFragment.Listener,
    ProfileCorruptedDialogFragment.Listener {

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

    override fun onResumeFragments() {
        super.onResumeFragments()
        processPendingEventIfSafe()
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
            eventWrapper.getContentIfNotHandled()?.let { handleLoginEvent(it) }
        }
    }

    private fun handleLoginEvent(event: LoginEvent) {
        when (event) {
            is LoginEvent.ShowProfileMissing -> {
                showNicknameDialog()
            }

            is LoginEvent.ShowProfileCorrupted -> {
                showCorruptedDialog()
            }

            is LoginEvent.ShowProfileLoadError -> {
                showErrorDialog(R.string.login_error_profile_title, R.string.login_error_profile_load, false)
            }

            is LoginEvent.ShowProfileSaveError -> {
                showErrorDialog(R.string.login_error_profile_title, R.string.login_error_profile_save, true)
            }

            is LoginEvent.NavigateToRoomList -> {
                navigateToRoomList()
            }
        }
    }

    private fun showNicknameDialog() {
        if (isFinishing || isDestroyed || supportFragmentManager.isStateSaved) return
        if (supportFragmentManager.findFragmentByTag(ProfileNicknameDialogFragment.TAG) == null) {
            ProfileNicknameDialogFragment.newInstance()
                .show(supportFragmentManager, ProfileNicknameDialogFragment.TAG)
        }
    }

    private fun showCorruptedDialog() {
        if (isFinishing || isDestroyed || supportFragmentManager.isStateSaved) return
        if (supportFragmentManager.findFragmentByTag(ProfileCorruptedDialogFragment.TAG) == null) {
            ProfileCorruptedDialogFragment.newInstance(
                R.string.login_dialog_profile_corrupted_title,
                R.string.login_dialog_profile_corrupted_message,
            ).show(supportFragmentManager, ProfileCorruptedDialogFragment.TAG)
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

    override fun onSaveNickname(nickname: String) {
        viewModel.saveProfileNickname(nickname)
    }

    override fun onSignOutFromNicknameDialog() {
        viewModel.signOut()
    }

    override fun onRetryProfile(isSaveError: Boolean) {
        if (isSaveError) {
            showNicknameDialog()
        } else {
            viewModel.retryProfileCheck()
        }
    }

    override fun onSignOutFromErrorDialog() {
        viewModel.signOut()
    }

    override fun onSignOutFromCorruptedDialog() {
        viewModel.signOut()
    }

    private fun clearAllErrorsFromUi() {
        binding.tilEmail.error = null
        binding.tilPassword.error = null
        binding.tvFormError.visibility = View.GONE
    }

    private fun navigateToRoomList() {
        val intent = Intent(this, RoomListActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }
}

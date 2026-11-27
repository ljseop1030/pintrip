package com.pintrip.app.room

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.firebase.auth.FirebaseAuth
import com.pintrip.app.R
import com.pintrip.app.data.repository.DeviceTokenRepository
import com.pintrip.app.databinding.BottomSheetMyInfoBinding
import com.pintrip.app.ui.common.NotificationPermissionHelper
import com.pintrip.app.ui.common.NotificationSettingsHandler
import kotlinx.coroutines.launch

/**
 * 내 정보 바텀시트 (Figma 20 내 정보).
 */
class MyInfoBottomSheetFragment : BottomSheetDialogFragment() {

    companion object {
        const val TAG = "MyInfoBottomSheetFragment"
    }

    private var _binding: BottomSheetMyInfoBinding? = null
    private val binding get() = _binding!!

    private val roomListViewModel: RoomListViewModel by lazy {
        ViewModelProvider(requireActivity())[RoomListViewModel::class.java]
    }

    private var isUpdatingSwitchProgrammatically = false

    private val notificationPermissionLauncher =
        NotificationPermissionHelper.registerNotificationPermissionLauncher(this) { isGranted ->
            if (isGranted) {
                lifecycleScope.launch {
                    val context = context ?: return@launch
                    DeviceTokenRepository.getInstance(context.applicationContext).syncToken()
                }
            }
            updateNotificationSwitchState()
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetMyInfoBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupUserProfile()
        setupListeners()
        observeViewModel()
    }

    override fun onResume() {
        super.onResume()
        updateNotificationSwitchState()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun setupUserProfile() {
        val currentUser = FirebaseAuth.getInstance().currentUser
        val email = currentUser?.email.orEmpty().ifBlank {
            getString(R.string.sample_email_hint)
        }
        binding.tvEmail.text = email
    }

    private fun setupListeners() {
        binding.btnClose.setOnClickListener {
            dismiss()
        }

        binding.switchNotification.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingSwitchProgrammatically) return@setOnCheckedChangeListener
            val activity = activity ?: return@setOnCheckedChangeListener

            if (isChecked) {
                NotificationSettingsHandler.executeNotificationPermissionFlow(
                    activity = activity,
                    launcher = notificationPermissionLauncher,
                    onGranted = {
                        lifecycleScope.launch {
                            DeviceTokenRepository.getInstance(activity.applicationContext).syncToken()
                        }
                    },
                    onStateChanged = {
                        updateNotificationSwitchState()
                    }
                )
            } else {
                NotificationSettingsHandler.showNotificationSettingsDialog(activity, isChannel = false)
                updateNotificationSwitchState()
            }
        }

        binding.tvLogout.setOnClickListener {
            dismiss()
            roomListViewModel.logout()
        }
    }

    private fun observeViewModel() {
        roomListViewModel.nickname.observe(viewLifecycleOwner) { nickname ->
            if (!nickname.isNullOrBlank()) {
                binding.tvNickname.text = nickname
            }
        }

        roomListViewModel.avatarText.observe(viewLifecycleOwner) { avatarText ->
            if (!avatarText.isNullOrBlank()) {
                binding.tvAvatar.text = avatarText
            }
        }

    }

    private fun updateNotificationSwitchState() {
        val context = context ?: return
        val isEnabled = NotificationSettingsHandler.isNotificationEnabled(context)
        isUpdatingSwitchProgrammatically = true
        binding.switchNotification.isChecked = isEnabled
        isUpdatingSwitchProgrammatically = false
    }
}

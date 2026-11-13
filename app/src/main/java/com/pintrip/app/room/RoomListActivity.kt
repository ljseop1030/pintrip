package com.pintrip.app.room

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import com.pintrip.app.R
import com.pintrip.app.auth.TestLoginActivity
import com.pintrip.app.databinding.ActivityRoomListBinding
import com.pintrip.app.place.PlaceListActivity

/**
 * 사용자 참여 여행방 목록 화면 (Figma S03).
 */
class RoomListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRoomListBinding
    private val viewModel: RoomListViewModel by viewModels()
    private lateinit var roomAdapter: RoomAdapter

    private val createRoomLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            viewModel.loadRooms(forceRefresh = true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        binding = ActivityRoomListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        setupRecyclerView()
        setupListeners()
        observeViewModel()

        viewModel.loadRooms()
    }

    override fun onResume() {
        super.onResume()
        viewModel.resetNavigation()
    }

    private fun setupRecyclerView() {
        roomAdapter = RoomAdapter { room ->
            viewModel.onRoomCardClick(room)
        }
        binding.rvRooms.apply {
            layoutManager = LinearLayoutManager(this@RoomListActivity)
            adapter = roomAdapter
        }
    }

    private fun setupListeners() {
        binding.btnCreateRoom.setOnClickListener {
            viewModel.onCreateRoomClick()
        }

        binding.btnJoinCode.setOnClickListener {
            viewModel.onJoinCodeClick()
        }

        binding.btnRetry.setOnClickListener {
            viewModel.loadRooms(forceRefresh = true)
        }

        binding.btnLogout.setOnClickListener {
            viewModel.logout()
            navigateToLogin()
        }
    }

    private fun observeViewModel() {
        viewModel.nickname.observe(this) { nickname ->
            binding.tvGreeting.text = getString(R.string.greeting_format, nickname)
        }

        viewModel.avatarText.observe(this) { avatar ->
            binding.tvHeaderAvatar.text = avatar
        }

        viewModel.avatarColor.observe(this) { colorHex ->
            if (!colorHex.isNullOrBlank()) {
                try {
                    val parsedColor = Color.parseColor(colorHex)
                    binding.tvHeaderAvatar.backgroundTintList = ColorStateList.valueOf(parsedColor)
                    binding.tvHeaderAvatar.setTextColor(Color.WHITE)
                } catch (_: Exception) {
                    // Keep layout background if parsing fails
                }
            }
        }

        viewModel.uiState.observe(this) { state ->
            when (state) {
                is RoomListUiState.Loading -> {
                    binding.pbLoading.isVisible = true
                    binding.rvRooms.isVisible = false
                    binding.tvEmpty.isVisible = false
                    binding.layoutError.isVisible = false
                }
                is RoomListUiState.Empty -> {
                    binding.pbLoading.isVisible = false
                    binding.rvRooms.isVisible = false
                    binding.tvEmpty.isVisible = true
                    binding.layoutError.isVisible = false
                }
                is RoomListUiState.Success -> {
                    binding.pbLoading.isVisible = false
                    binding.rvRooms.isVisible = true
                    binding.tvEmpty.isVisible = false
                    binding.layoutError.isVisible = false
                    roomAdapter.submitList(state.rooms)
                }
                is RoomListUiState.Error -> {
                    binding.pbLoading.isVisible = false
                    binding.rvRooms.isVisible = false
                    binding.tvEmpty.isVisible = false
                    binding.layoutError.isVisible = true
                    binding.tvErrorMessage.text = getString(state.messageResId)
                }
            }
        }

        viewModel.navigateToCreateRoom.observe(this) { event ->
            event.getContentIfNotHandled()?.let {
                try {
                    val intent = Intent(this, CreateRoomActivity::class.java)
                    createRoomLauncher.launch(intent)
                } catch (_: Exception) {
                    viewModel.resetNavigation()
                }
            }
        }

        viewModel.navigateToPlaceList.observe(this) { event ->
            event.getContentIfNotHandled()?.let { roomId ->
                joinCodeDialog?.dismiss()
                try {
                    val intent = PlaceListActivity.newIntent(this, roomId)
                    startActivity(intent)
                } catch (_: Exception) {
                    viewModel.resetNavigation()
                }
            }
        }

        viewModel.showJoinCodeDialogEvent.observe(this) { event ->
            event.getContentIfNotHandled()?.let {
                showJoinCodeDialog()
            }
        }

        viewModel.isJoining.observe(this) { isJoining ->
            val dialogBinding = joinCodeDialogBinding
            if (dialogBinding != null) {
                dialogBinding.pbLoading.isVisible = isJoining
                dialogBinding.btnJoin.isEnabled = !isJoining
                dialogBinding.btnCancel.isEnabled = !isJoining
                dialogBinding.etInviteCode.isEnabled = !isJoining
            }
        }

        viewModel.joinErrorMessageEvent.observe(this) { event ->
            event.getContentIfNotHandled()?.let { resId ->
                val dialogBinding = joinCodeDialogBinding
                if (dialogBinding != null) {
                    dialogBinding.tilInviteCode.error = getString(resId)
                } else {
                    Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
                }
            }
        }

        viewModel.toastEvent.observe(this) { event ->
            event.getContentIfNotHandled()?.let { resId ->
                Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private var joinCodeDialog: androidx.appcompat.app.AlertDialog? = null
    private var joinCodeDialogBinding: com.pintrip.app.databinding.DialogJoinCodeBinding? = null

    private fun showJoinCodeDialog() {
        if (joinCodeDialog?.isShowing == true) return

        val dialogBinding = com.pintrip.app.databinding.DialogJoinCodeBinding.inflate(layoutInflater)
        joinCodeDialogBinding = dialogBinding

        dialogBinding.etInviteCode.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                dialogBinding.tilInviteCode.error = null
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        dialogBinding.btnCancel.setOnClickListener {
            joinCodeDialog?.dismiss()
        }

        dialogBinding.btnJoin.setOnClickListener {
            val code = dialogBinding.etInviteCode.text?.toString().orEmpty()
            viewModel.joinRoomWithCode(code)
        }

        joinCodeDialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setView(dialogBinding.root)
            .setCancelable(true)
            .setOnDismissListener {
                joinCodeDialogBinding = null
                joinCodeDialog = null
            }
            .show()
    }

    private fun navigateToLogin() {
        val intent = Intent(this, TestLoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }
}

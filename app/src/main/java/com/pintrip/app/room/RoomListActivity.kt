package com.pintrip.app.room

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.util.Patterns
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import android.util.Log
import androidx.lifecycle.lifecycleScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Source
import com.pintrip.app.R
import com.pintrip.app.auth.TestLoginActivity
import com.pintrip.app.data.repository.DeviceTokenRepository
import com.pintrip.app.data.repository.RoomRepository
import com.pintrip.app.databinding.ActivityRoomListBinding
import com.pintrip.app.extract.ShareLauncher
import com.pintrip.app.map.RoomMapActivity
import com.pintrip.app.notification.PendingNotificationManager
import com.pintrip.app.place.PlaceDetailActivity
import com.pintrip.app.place.PlaceListActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 사용자 참여 여행방 목록 화면 (Figma S03).
 */
class RoomListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRoomListBinding
    private val roomRepository: RoomRepository by lazy { RoomRepository() }
    private val viewModel: RoomListViewModel by viewModels {
        RoomListViewModelFactory(
            deviceTokenRepository = DeviceTokenRepository.getInstance(applicationContext)
        )
    }
    private lateinit var roomAdapter: RoomAdapter

    private val createRoomLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            viewModel.loadRooms(forceRefresh = true)
        }
    }

    private val placeListLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { _ ->
        viewModel.loadRooms(forceRefresh = true)
    }

    // 사진 버튼: 포토피커로 스크린샷을 고르면 16 공유 수신으로 넘김 (방은 16에서 선택)
    private val pickPhotosLauncher = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(ShareLauncher.MAX_IMAGES),
    ) { uris ->
        if (uris.isNotEmpty()) {
            ShareLauncher.openImages(this, uris)
        }
    }

    companion object {
        const val EXTRA_INVITE_CODE = "extra_invite_code"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        binding = ActivityRoomListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val initialHeaderPaddingTop = binding.layoutHeader.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, 0, systemBars.right, systemBars.bottom)
            binding.layoutHeader.setPadding(
                binding.layoutHeader.paddingLeft,
                initialHeaderPaddingTop + systemBars.top,
                binding.layoutHeader.paddingRight,
                binding.layoutHeader.paddingBottom
            )
            insets
        }

        setupRecyclerView()
        setupListeners()
        observeViewModel()

        if (savedInstanceState == null) {
            processIntentInvite(intent)
            checkPendingNotificationDestination()
        }
        viewModel.syncDeviceToken()
        viewModel.loadRooms()
    }

    override fun onResume() {
        super.onResume()
        viewModel.resetNavigation()
        viewModel.checkPendingInvite(applicationContext)
        checkPendingNotificationDestination()
    }

    private fun checkPendingNotificationDestination() {
        val currentUid = FirebaseAuth.getInstance().currentUser?.uid
        if (currentUid.isNullOrBlank()) return
        val tokenRepo = DeviceTokenRepository.getInstance(applicationContext)

        lifecycleScope.launch {
            val result = com.pintrip.app.notification.NotificationDestinationHandler.processDestination(
                applicationContext,
                roomRepository,
                sessionProvider = {
                    com.pintrip.app.notification.SessionSnapshot(
                        uid = FirebaseAuth.getInstance().currentUser?.uid,
                        generation = DeviceTokenRepository.currentSessionGeneration(),
                        isLoggingOut = tokenRepo.isLoggingOut
                    )
                }
            )
            handleRoomListDestinationResult(result)
        }
    }

    private fun handleRoomListDestinationResult(result: com.pintrip.app.notification.PendingDestinationResult) {
        when (result) {
            is com.pintrip.app.notification.PendingDestinationResult.Success -> {
                val override = PendingNotificationManager.navigatorOverride
                if (override != null) {
                    override.invoke(this, result.roomId, result.kakaoPlaceId)
                } else {
                    val roomMapIntent = RoomMapActivity.newIntent(this, result.roomId)
                    if (result.kakaoPlaceId.isNotBlank()) {
                        val placeDetailIntent = PlaceDetailActivity.newIntent(this, result.roomId, result.kakaoPlaceId)
                        startActivities(arrayOf(roomMapIntent, placeDetailIntent))
                    } else {
                        startActivity(roomMapIntent)
                    }
                }
            }
            is com.pintrip.app.notification.PendingDestinationResult.AccountMismatch -> {
                Toast.makeText(this, R.string.notice_notification_account_mismatch, Toast.LENGTH_SHORT).show()
            }
            is com.pintrip.app.notification.PendingDestinationResult.AccessDenied -> {
                Toast.makeText(this, R.string.notice_notification_access_denied, Toast.LENGTH_SHORT).show()
            }
            is com.pintrip.app.notification.PendingDestinationResult.NetworkError -> {
                Toast.makeText(this, R.string.error_network_unavailable, Toast.LENGTH_SHORT).show()
            }
            is com.pintrip.app.notification.PendingDestinationResult.NoDestination,
            is com.pintrip.app.notification.PendingDestinationResult.AlreadyProcessing,
            is com.pintrip.app.notification.PendingDestinationResult.StaleOrSuperseded -> {
                // Do nothing
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        processIntentInvite(intent)
    }

    private fun processIntentInvite(intent: Intent?) {
        if (intent == null) return
        if ((intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0) return

        val codeFromExtra = intent.getStringExtra(EXTRA_INVITE_CODE)
        val codeFromUri = DeepLinkUtils.extractInviteCode(intent)
        val code = codeFromExtra ?: codeFromUri

        if (!code.isNullOrBlank()) {
            intent.removeExtra(EXTRA_INVITE_CODE)
            viewModel.handleDeepLinkCode(applicationContext, code)
        } else {
            viewModel.checkPendingInvite(applicationContext)
        }
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

        binding.tvHeaderAvatar.setOnClickListener {
            showMyInfoBottomSheet()
        }

        binding.ivChevronDown.setOnClickListener {
            showMyInfoBottomSheet()
        }

        binding.btnPhotoAdd.setOnClickListener {
            pickPhotosLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
            )
        }

        binding.etLink.setOnEditorActionListener { _, actionId, event ->
            val isGo = actionId == EditorInfo.IME_ACTION_GO ||
                    (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (isGo) {
                submitLink()
                true
            } else {
                false
            }
        }
    }

    /** 링크바에 입력한 텍스트를 16 공유 수신으로 넘긴다. 링크가 없으면 여기서 바로 알려준다. */
    private fun submitLink() {
        val text = binding.etLink.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        if (!Patterns.WEB_URL.matcher(text).find()) {
            Toast.makeText(this, R.string.share_receive_error_no_url, Toast.LENGTH_SHORT).show()
            return
        }
        WindowCompat.getInsetsController(window, binding.etLink).hide(WindowInsetsCompat.Type.ime())
        binding.etLink.text = null
        binding.etLink.clearFocus()
        ShareLauncher.openLink(this, text)
    }

    private fun showMyInfoBottomSheet() {
        if (supportFragmentManager.findFragmentByTag(MyInfoBottomSheetFragment.TAG) != null) return
        val sheet = MyInfoBottomSheetFragment()
        sheet.show(supportFragmentManager, MyInfoBottomSheetFragment.TAG)
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
                    binding.layoutLinkBar.isVisible = false
                    binding.tvScreenshotHint.isVisible = false
                }
                is RoomListUiState.Empty -> {
                    binding.pbLoading.isVisible = false
                    binding.rvRooms.isVisible = false
                    binding.tvEmpty.isVisible = true
                    binding.layoutError.isVisible = false
                    binding.layoutLinkBar.isVisible = false
                    binding.tvScreenshotHint.isVisible = false
                }
                is RoomListUiState.Success -> {
                    binding.pbLoading.isVisible = false
                    binding.rvRooms.isVisible = true
                    binding.tvEmpty.isVisible = false
                    binding.layoutError.isVisible = false
                    binding.layoutLinkBar.isVisible = true
                    binding.tvScreenshotHint.isVisible = true
                    roomAdapter.submitList(state.rooms)
                }
                is RoomListUiState.Error -> {
                    binding.pbLoading.isVisible = false
                    binding.rvRooms.isVisible = false
                    binding.tvEmpty.isVisible = false
                    binding.layoutError.isVisible = true
                    binding.layoutLinkBar.isVisible = false
                    binding.tvScreenshotHint.isVisible = false
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
                dismissJoinCodeDialog()
                try {
                    val intent = com.pintrip.app.map.RoomMapActivity.newIntent(this, roomId)
                    placeListLauncher.launch(intent)
                    viewModel.onPlaceListLaunchSuccess()
                } catch (_: Exception) {
                    viewModel.onPlaceListLaunchFailed()
                }
            }
        }

        viewModel.navigateToLogin.observe(this) { event ->
            event.getContentIfNotHandled()?.let {
                navigateToLogin()
            }
        }

        viewModel.isJoinDialogOpen.observe(this) { isOpen ->
            if (isOpen) {
                showJoinCodeDialog()
            } else {
                dismissJoinCodeDialog()
            }
        }

        viewModel.joinCodeInput.observe(this) { text ->
            val dialogBinding = joinCodeDialogBinding
            if (dialogBinding != null && dialogBinding.etInviteCode.text?.toString() != text) {
                dialogBinding.etInviteCode.setText(text)
                if (!text.isNullOrEmpty()) {
                    dialogBinding.etInviteCode.setSelection(text.length)
                }
            }
        }

        viewModel.isJoining.observe(this) { isJoining ->
            val dialogBinding = joinCodeDialogBinding
            val dialog = joinCodeDialog
            if (dialogBinding != null && dialog != null) {
                dialogBinding.pbLoading.isVisible = isJoining
                dialogBinding.btnJoin.isEnabled = !isJoining
                dialogBinding.btnCancel.isEnabled = !isJoining
                dialogBinding.etInviteCode.isEnabled = !isJoining
                dialog.setCancelable(!isJoining)
                dialog.setCanceledOnTouchOutside(!isJoining)
            }
        }

        viewModel.joinErrorMessageResId.observe(this) { resId ->
            val dialogBinding = joinCodeDialogBinding
            if (dialogBinding != null) {
                if (resId != null) {
                    dialogBinding.tilInviteCode.error = getString(resId)
                } else {
                    dialogBinding.tilInviteCode.error = null
                }
            }
        }

        viewModel.joinSuccessResult.observe(this) { result ->
            if (result != null) {
                showJoinSuccessDialog(result)
            } else {
                dismissJoinSuccessDialog()
            }
        }

        viewModel.toastEvent.observe(this) { event ->
            event.getContentIfNotHandled()?.let { resId ->
                Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private var isCleaningUpForDestroy = false
    private var joinCodeDialog: androidx.appcompat.app.AlertDialog? = null
    private var joinCodeDialogBinding: com.pintrip.app.databinding.DialogJoinCodeBinding? = null
    private var joinSuccessDialog: androidx.appcompat.app.AlertDialog? = null

    override fun onDestroy() {
        super.onDestroy()
        isCleaningUpForDestroy = true

        joinCodeDialog?.setOnDismissListener(null)
        joinCodeDialog?.dismiss()
        joinCodeDialog = null
        joinCodeDialogBinding = null

        joinSuccessDialog?.setOnDismissListener(null)
        joinSuccessDialog?.setOnCancelListener(null)
        joinSuccessDialog?.dismiss()
        joinSuccessDialog = null
    }

    private fun showJoinSuccessDialog(result: com.pintrip.app.data.repository.JoinRoomResult) {
        if (joinSuccessDialog?.isShowing == true) return

        var isUserExplicitDismiss = false

        val dialogBinding = com.pintrip.app.databinding.DialogJoinSuccessBinding.inflate(layoutInflater)
        val roomName = result.room.name
        val titleText = when (result) {
            is com.pintrip.app.data.repository.JoinRoomResult.NewJoin -> {
                getString(R.string.notice_join_room_success_format, roomName)
            }
            is com.pintrip.app.data.repository.JoinRoomResult.Rechecked -> {
                getString(R.string.notice_join_room_rechecked_format, roomName)
            }
            is com.pintrip.app.data.repository.JoinRoomResult.Creator -> {
                getString(R.string.notice_join_own_room_format, roomName)
            }
            is com.pintrip.app.data.repository.JoinRoomResult.ExistingMember -> {
                getString(R.string.notice_already_joined_room_format, roomName)
            }
        }
        dialogBinding.tvTitle.text = titleText

        dialogBinding.btnViewPlaces.setOnClickListener {
            viewModel.onViewPlacesFromJoinSuccess(result.room.id)
        }

        dialogBinding.btnGoToList.setOnClickListener {
            isUserExplicitDismiss = true
            dismissJoinSuccessDialog()
        }

        joinSuccessDialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setView(dialogBinding.root)
            .setCancelable(true)
            .setOnCancelListener {
                if (!isCleaningUpForDestroy && !isChangingConfigurations) {
                    isUserExplicitDismiss = true
                }
            }
            .setOnDismissListener {
                joinSuccessDialog = null
                if (!isCleaningUpForDestroy && !isChangingConfigurations && isUserExplicitDismiss) {
                    viewModel.onDismissJoinSuccess(applicationContext)
                }
            }
            .show()
    }

    private fun dismissJoinSuccessDialog() {
        val dialog = joinSuccessDialog
        joinSuccessDialog = null
        dialog?.dismiss()
    }

    private fun showJoinCodeDialog() {
        if (joinCodeDialog?.isShowing == true) return

        val dialogBinding = com.pintrip.app.databinding.DialogJoinCodeBinding.inflate(layoutInflater)
        joinCodeDialogBinding = dialogBinding

        val initialInput = viewModel.joinCodeInput.value.orEmpty()
        dialogBinding.etInviteCode.setText(initialInput)
        if (initialInput.isNotEmpty()) {
            dialogBinding.etInviteCode.setSelection(initialInput.length)
        }

        val errResId = viewModel.joinErrorMessageResId.value
        if (errResId != null) {
            dialogBinding.tilInviteCode.error = getString(errResId)
        }

        val joining = viewModel.isJoining.value == true
        dialogBinding.pbLoading.isVisible = joining
        dialogBinding.btnJoin.isEnabled = !joining
        dialogBinding.btnCancel.isEnabled = !joining
        dialogBinding.etInviteCode.isEnabled = !joining

        dialogBinding.etInviteCode.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                viewModel.onJoinCodeInputChanged(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        dialogBinding.btnCancel.setOnClickListener {
            dismissJoinCodeDialog()
        }

        dialogBinding.btnJoin.setOnClickListener {
            val code = dialogBinding.etInviteCode.text?.toString().orEmpty()
            viewModel.joinRoomWithCode(applicationContext, code)
        }

        joinCodeDialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setView(dialogBinding.root)
            .setCancelable(!joining)
            .setOnDismissListener {
                joinCodeDialogBinding = null
                joinCodeDialog = null
                if (!isCleaningUpForDestroy && !isChangingConfigurations) {
                    viewModel.onJoinDialogDismissed(applicationContext)
                }
            }
            .show()
    }

    private fun dismissJoinCodeDialog() {
        val dialog = joinCodeDialog
        joinCodeDialogBinding = null
        joinCodeDialog = null
        dialog?.dismiss()
    }

    private fun navigateToLogin() {
        val intent = Intent(this, TestLoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }
}
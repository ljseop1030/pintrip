package com.pintrip.app.map

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.widget.ImageViewCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.kakao.vectormap.KakaoMap
import com.kakao.vectormap.KakaoMapReadyCallback
import com.kakao.vectormap.LatLng
import com.kakao.vectormap.MapLifeCycleCallback
import com.kakao.vectormap.camera.CameraUpdateFactory
import com.kakao.vectormap.label.Label
import com.kakao.vectormap.label.LabelOptions
import com.kakao.vectormap.label.LabelStyles
import com.kakao.vectormap.label.LabelTextBuilder
import com.pintrip.app.R
import com.pintrip.app.data.model.PlaceDetail
import com.pintrip.app.data.repository.DeviceTokenRepository
import com.pintrip.app.databinding.ActivityRoomMapBinding
import com.pintrip.app.notification.FriendNotificationService
import com.pintrip.app.place.InviteCodeUiState
import com.pintrip.app.place.PlaceCategory
import com.pintrip.app.place.PlaceDetailActivity
import com.pintrip.app.place.labelResId
import com.pintrip.app.room.DeepLinkUtils
import com.pintrip.app.travel.TravelModeService
import com.pintrip.app.travel.TravelModeState
import com.pintrip.app.travel.TravelState
import com.pintrip.app.ui.common.LocationPermissionHelper
import com.pintrip.app.ui.common.NotificationPermissionAction
import com.pintrip.app.ui.common.NotificationPermissionFlow
import com.pintrip.app.ui.common.NotificationPermissionHelper
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class RoomMapActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "RoomMapActivity"
        const val EXTRA_ROOM_ID = "extra_room_id"

        fun newIntent(context: Context, roomId: String): Intent {
            return Intent(context, RoomMapActivity::class.java).apply {
                putExtra(EXTRA_ROOM_ID, roomId)
            }
        }
    }

    private lateinit var binding: ActivityRoomMapBinding
    private val viewModel: RoomMapViewModel by viewModels()
    private val shakeViewModel: com.pintrip.app.place.ShakeToPickViewModel by viewModels()

    private var kakaoMapInstance: KakaoMap? = null
    private var labelStyles: LabelStyles? = null
    private val labelPlaceMap = mutableMapOf<Label, String>()
    private var currentSuccessState: RoomMapUiState.Success? = null
    private var bottomSheetBehavior: BottomSheetBehavior<*>? = null
    private lateinit var roomPlaceAdapter: RoomPlaceAdapter

    private var shakeDetector: com.pintrip.app.place.ShakeDetector? = null
    private var shakeBottomSheetDialog: com.google.android.material.bottomsheet.BottomSheetDialog? = null
    private var shakeDialogBinding: com.pintrip.app.databinding.BottomSheetShakeToPickBinding? = null

    private var isCleaningUpForDestroy = false
    private var isTravelButtonExpanded = false
    private val headerDateFormat = SimpleDateFormat("MM.dd", Locale.getDefault()).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    private var inviteCodeDialog: androidx.appcompat.app.AlertDialog? = null
    private var inviteCodeDialogBinding: com.pintrip.app.databinding.DialogInviteCodeDisplayBinding? = null

    private var addPlaceBottomSheetDialog: com.google.android.material.bottomsheet.BottomSheetDialog? = null
    private var addPlaceDialogBinding: com.pintrip.app.databinding.BottomSheetAddPlaceBinding? = null

    private val pickPhotoLauncher = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) {
            val roomId = intent.getStringExtra(EXTRA_ROOM_ID).orEmpty()
            val intent = com.pintrip.app.extract.ShareReceiveActivity.newIntent(this, roomId).apply {
                action = Intent.ACTION_SEND
                type = "image/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
            dismissAddPlaceBottomSheet()
        }
    }

    private val requestLocationPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
            val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
            if (fineGranted || coarseGranted) {
                checkNotificationPermissionAndStartService()
            } else {
                val showRationaleFine = shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)
                val showRationaleCoarse = shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION)
                if (!showRationaleFine && !showRationaleCoarse) {
                    showLocationPermissionSettingsDialog()
                } else {
                    Toast.makeText(this, R.string.travel_mode_location_permission_required, Toast.LENGTH_SHORT).show()
                }
            }
        }

    private val requestNotificationPermissionLauncher =
        NotificationPermissionHelper.registerNotificationPermissionLauncher(this) { isGranted ->
            if (!isGranted) {
                Toast.makeText(this, R.string.travel_mode_notification_permission_denied_toast, Toast.LENGTH_SHORT).show()
            }
            startTravelModeService()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val roomId = intent.getStringExtra(EXTRA_ROOM_ID)
        if (roomId.isNullOrBlank()) {
            Toast.makeText(this, R.string.place_list_error_no_room_id, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        enableEdgeToEdge()
        binding = ActivityRoomMapBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        setupHeader()
        setupTravelModeButton()
        setupBottomSheet()
        setupRecyclerView(roomId)
        setupCategoryChips()
        setupDiceButton()

        val sensorManager = getSystemService(Context.SENSOR_SERVICE) as? android.hardware.SensorManager
        shakeDetector = com.pintrip.app.place.ShakeDetector(sensorManager) {
            shakeViewModel.pickRandomPlace()
        }

        lifecycle.addObserver(object : androidx.lifecycle.LifecycleEventObserver {
            override fun onStateChanged(source: androidx.lifecycle.LifecycleOwner, event: androidx.lifecycle.Lifecycle.Event) {
                if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                    binding.root.post { updateSensorState() }
                } else if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE) {
                    shakeDetector?.stop()
                }
            }
        })

        val currentState = KakaoMapHelper.getSdkInitState()
        if (currentState == KakaoMapHelper.SdkInitState.FAILED) {
            binding.mapView.isVisible = false
            binding.pbLoading.isVisible = false
            binding.layoutError.isVisible = false
            binding.tvMapUnavailable.isVisible = true
            setupListeners(roomId)
            observeViewModel()
            viewModel.loadRoomMap(roomId)
            return
        }

        try {
            if (currentState == KakaoMapHelper.SdkInitState.NOT_INITIALIZED) {
                KakaoMapHelper.initSdk(applicationContext)
            }
            if (KakaoMapHelper.getSdkInitState() == KakaoMapHelper.SdkInitState.FAILED) {
                throw IllegalStateException("KakaoMapSdk init failed")
            }
            binding.tvMapUnavailable.isVisible = false
            setupMapView()
            setupListeners(roomId)
            observeViewModel()

            viewModel.loadRoomMap(roomId)
        } catch (e: Throwable) {
            Log.e(TAG, "KakaoMapSdk init failed on this device: ${e.message}", e)
            binding.mapView.isVisible = false
            binding.pbLoading.isVisible = false
            binding.layoutError.isVisible = false
            binding.tvMapUnavailable.isVisible = true
            setupListeners(roomId)
            observeViewModel()
            viewModel.loadRoomMap(roomId)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isCleaningUpForDestroy = true

        shakeDetector?.stop()
        shakeDetector = null

        shakeBottomSheetDialog?.setOnDismissListener(null)
        shakeBottomSheetDialog?.dismiss()
        shakeBottomSheetDialog = null
        shakeDialogBinding = null

        inviteCodeDialog?.setOnDismissListener(null)
        inviteCodeDialog?.dismiss()
        inviteCodeDialog = null
        inviteCodeDialogBinding = null

        dismissAddPlaceBottomSheet()
    }

    private fun setupHeader() {
        binding.ivBack.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
        binding.btnInvite.setOnClickListener {
            viewModel.onInviteCodeClick()
        }
    }

    private fun setupTravelModeButton() {
        binding.btnTravelMode.setOnClickListener {
            val roomId = intent.getStringExtra(EXTRA_ROOM_ID).orEmpty()
            val travelState = TravelModeState.state.value
            val isOnInThisRoom = travelState.isRunning && travelState.roomId == roomId
            when {
                isOnInThisRoom -> showTravelModeTurnOffDialog()
                !isTravelButtonExpanded -> {
                    isTravelButtonExpanded = true
                    renderTravelModeButton(travelState)
                }
                else -> {
                    isTravelButtonExpanded = false
                    handleTravelModeMenuClick()
                    renderTravelModeButton(travelState)
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                TravelModeState.state.collect { travelState ->
                    renderTravelModeButton(travelState)
                }
            }
        }
    }

    private fun renderTravelModeButton(travelState: TravelState) {
        val roomId = intent.getStringExtra(EXTRA_ROOM_ID).orEmpty()
        val isOnInThisRoom = travelState.isRunning && travelState.roomId == roomId
        if (isOnInThisRoom) isTravelButtonExpanded = false

        val bgColor = ContextCompat.getColor(this, if (isOnInThisRoom) R.color.pintrip_blue else R.color.white)
        val textColor = ContextCompat.getColor(this, if (isOnInThisRoom) R.color.white else R.color.button_outlined_text)
        val iconColor = ContextCompat.getColor(this, if (isOnInThisRoom) R.color.white else R.color.pintrip_blue)

        binding.btnTravelMode.setCardBackgroundColor(bgColor)
        ImageViewCompat.setImageTintList(binding.ivTravelIcon, ColorStateList.valueOf(iconColor))
        binding.tvTravelModeText.setTextColor(textColor)
        binding.tvTravelModeText.setText(
            when {
                isOnInThisRoom -> R.string.travel_mode_active
                travelState.isRunning -> R.string.menu_travel_mode_switch_to_this_room
                else -> R.string.travel_mode_turn_on
            }
        )
        binding.tvTravelModeText.isVisible = isOnInThisRoom || isTravelButtonExpanded
        binding.viewTravelDot.isVisible = isOnInThisRoom
        binding.btnTravelMode.contentDescription = binding.tvTravelModeText.text
    }

    private fun showTravelModeTurnOffDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.travel_mode_turn_off_confirm_title)
            .setMessage(R.string.travel_mode_turn_off_confirm_message)
            .setPositiveButton(R.string.travel_mode_turn_off) { _, _ ->
                handleTravelModeMenuClick()
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun handleTravelModeMenuClick() {
        val roomId = intent.getStringExtra(EXTRA_ROOM_ID).orEmpty()
        val currentState = TravelModeState.state.value

        if (currentState.isRunning && currentState.roomId == roomId) {
            startService(TravelModeService.stopIntent(this))
        } else {
            if (LocationPermissionHelper.hasLocationPermission(this)) {
                checkNotificationPermissionAndStartService()
            } else {
                requestLocationPermissionsLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )
            }
        }
    }

    private fun checkNotificationPermissionAndStartService() {
        NotificationPermissionHelper.requestNotificationPermission(
            context = this,
            launcher = requestNotificationPermissionLauncher,
            onAlreadyGranted = {
                startTravelModeService()
            }
        )
    }

    private fun startTravelModeService() {
        val roomId = intent.getStringExtra(EXTRA_ROOM_ID).orEmpty()
        val roomName = currentSuccessState?.roomName.orEmpty().ifBlank { getString(R.string.app_name) }
        val serviceIntent = TravelModeService.startIntent(this, roomId, roomName)
        ContextCompat.startForegroundService(this, serviceIntent)
    }

    private fun showLocationPermissionSettingsDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.travel_mode_location_permission_dialog_title)
            .setMessage(R.string.travel_mode_location_permission_dialog_message)
            .setPositiveButton(R.string.travel_mode_btn_go_to_settings) { _, _ ->
                LocationPermissionHelper.openAppSettings(this)
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun setupBottomSheet() {
        bottomSheetBehavior = BottomSheetBehavior.from(binding.bottomSheet)
    }

    private fun setupRecyclerView(roomId: String) {
        roomPlaceAdapter = RoomPlaceAdapter { kakaoPlaceId ->
            startActivity(PlaceDetailActivity.newIntent(this, roomId, kakaoPlaceId))
        }
        binding.rvPlaces.apply {
            layoutManager = LinearLayoutManager(this@RoomMapActivity)
            adapter = roomPlaceAdapter
        }
    }

    private fun setupCategoryChips() {
        binding.chipGroupCategory.setOnCheckedStateChangeListener { _, checkedIds ->
            val checkedId = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            val category = when (checkedId) {
                R.id.chip_all -> PlaceCategory.ALL
                R.id.chip_dining -> PlaceCategory.DINING
                R.id.chip_cafe -> PlaceCategory.CAFE
                R.id.chip_sights -> PlaceCategory.SIGHTS
                R.id.chip_stay -> PlaceCategory.STAY
                R.id.chip_etc -> PlaceCategory.ETC
                else -> PlaceCategory.ALL
            }
            viewModel.selectCategory(category)
        }
    }

    private fun setupDiceButton() {
        binding.fabDice.setOnClickListener {
            shakeViewModel.openDialogFresh()
        }
    }

    fun focusPlace(kakaoPlaceId: String) {
        val success = currentSuccessState ?: return
        val foundPlace = success.placeDetails.find {
            it.status == PlaceDetail.STATUS_FOUND && it.place.kakaoPlaceId == kakaoPlaceId
        } ?: return

        val map = kakaoMapInstance
        if (map != null && foundPlace.lat != null && foundPlace.lng != null) {
            map.moveCamera(CameraUpdateFactory.newCenterPosition(LatLng.from(foundPlace.lat, foundPlace.lng), 15))
        }

        bottomSheetBehavior?.state = BottomSheetBehavior.STATE_COLLAPSED
    }

    private fun setupMapView() {
        binding.mapView.start(object : MapLifeCycleCallback() {
            override fun onMapError(error: Exception) {
                Log.e(TAG, "onMapError: ${error.message} (cause: ${error.cause})", error)
                val isAuthError = error is com.kakao.vectormap.MapAuthException ||
                        error.javaClass.name.contains("Auth", ignoreCase = true) ||
                        error.message?.contains("auth", ignoreCase = true) == true ||
                        error.message?.contains("인증", ignoreCase = true) == true

                runOnUiThread {
                    binding.mapView.isVisible = false
                    binding.pbLoading.isVisible = false
                    binding.layoutError.isVisible = false
                    binding.tvMapUnavailable.isVisible = true
                    binding.tvMapUnavailable.text = getString(
                        if (isAuthError) R.string.room_map_auth_failed else R.string.room_map_load_error
                    )
                }
            }

            override fun onMapDestroy() {
                // Map destroyed
            }
        }, object : KakaoMapReadyCallback() {
            override fun onMapReady(kakaoMap: KakaoMap) {
                kakaoMapInstance = kakaoMap
                initLabelStyles(kakaoMap)
                setupLabelClickListener(kakaoMap)
                applyMapData()
            }
        })
    }

    private fun setupLabelClickListener(kakaoMap: KakaoMap) {
        kakaoMap.setOnLabelClickListener { _, _, label ->
            val kakaoPlaceId = labelPlaceMap[label]
            if (!kakaoPlaceId.isNullOrBlank()) {
                val roomId = intent.getStringExtra(EXTRA_ROOM_ID).orEmpty()
                startActivity(PlaceDetailActivity.newIntent(this@RoomMapActivity, roomId, kakaoPlaceId))
            }
            true
        }
    }

    private fun initLabelStyles(kakaoMap: KakaoMap) {
        if (labelStyles != null) return
        labelStyles = KakaoMapHelper.createLabelStyles(this, kakaoMap)
    }

    override fun onResume() {
        super.onResume()
        try {
            if (binding.mapView.isVisible) {
                binding.mapView.resume()
            }
        } catch (e: Throwable) {
            Log.e(TAG, "MapView resume failed: ${e.message}", e)
        }
        updateSensorState()
    }

    override fun onPause() {
        super.onPause()
        try {
            if (binding.mapView.isVisible) {
                binding.mapView.pause()
            }
        } catch (e: Throwable) {
            Log.e(TAG, "MapView pause failed: ${e.message}", e)
        }
        shakeDetector?.stop()
    }

    private fun setupListeners(roomId: String) {
        binding.btnRetry.setOnClickListener {
            viewModel.retry(roomId)
        }
        binding.btnAddPlace.setOnClickListener {
            val roomName = currentSuccessState?.roomName
                ?: binding.tvTitle.text.toString().ifBlank { roomId }
            showAddPlaceBottomSheet(roomId, roomName)
        }
    }

    private fun showAddPlaceBottomSheet(roomId: String, roomName: String) {
        if (addPlaceBottomSheetDialog?.isShowing == true) return

        val dialogBinding = com.pintrip.app.databinding.BottomSheetAddPlaceBinding.inflate(layoutInflater)
        addPlaceDialogBinding = dialogBinding

        dialogBinding.tvSheetTitle.setText(R.string.title_add_place)
        dialogBinding.tvSheetSubtitle.text = getString(R.string.add_place_subtitle_format, roomName)

        dialogBinding.btnClose.setOnClickListener {
            dismissAddPlaceBottomSheet()
        }

        dialogBinding.cardAddFromPhoto.setOnClickListener {
            pickPhotoLauncher.launch(
                androidx.activity.result.PickVisualMediaRequest(
                    ActivityResultContracts.PickVisualMedia.ImageOnly
                )
            )
        }

        dialogBinding.btnSearchLink.setOnClickListener {
            val linkText = dialogBinding.etLinkInput.text.toString().trim()
            if (linkText.isNotBlank()) {
                val intent = com.pintrip.app.extract.ShareReceiveActivity.newIntent(this, roomId).apply {
                    action = Intent.ACTION_SEND
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, linkText)
                }
                startActivity(intent)
                dismissAddPlaceBottomSheet()
            }
        }

        addPlaceBottomSheetDialog = com.google.android.material.bottomsheet.BottomSheetDialog(this).apply {
            setContentView(dialogBinding.root)
            setCancelable(true)

            behavior.state = BottomSheetBehavior.STATE_EXPANDED
            behavior.skipCollapsed = true

            setOnDismissListener {
                addPlaceDialogBinding = null
                addPlaceBottomSheetDialog = null
            }
            show()
        }
    }

    private fun dismissAddPlaceBottomSheet() {
        addPlaceBottomSheetDialog?.setOnDismissListener(null)
        addPlaceBottomSheetDialog?.dismiss()
        addPlaceBottomSheetDialog = null
        addPlaceDialogBinding = null
    }

    private fun observeViewModel() {
        viewModel.uiState.observe(this) { state ->
            when (state) {
                is RoomMapUiState.Loading -> {
                    binding.pbLoading.isVisible = true
                    binding.layoutError.isVisible = false
                    shakeViewModel.setHomeLoading()
                }
                is RoomMapUiState.Success -> {
                    binding.pbLoading.isVisible = false
                    binding.layoutError.isVisible = false
                    currentSuccessState = state

                    binding.tvTitle.text = state.roomName
                    val dateText = if (state.startDate != null && state.endDate != null) {
                        getString(
                            R.string.date_range_format,
                            headerDateFormat.format(state.startDate.toDate()),
                            headerDateFormat.format(state.endDate.toDate()),
                        )
                    } else {
                        getString(R.string.date_to_be_determined)
                    }
                    binding.tvSubtitle.text = getString(R.string.room_header_subtitle_format, dateText, state.totalPlaceCount)

                    val count = state.placeDetails.size
                    if (state.selectedCategory == PlaceCategory.ALL) {
                        binding.tvSheetTitle.text = getString(R.string.room_map_saved_places_all_format, count)
                    } else {
                        val categoryName = getString(state.selectedCategory.labelResId())
                        binding.tvSheetTitle.text = getString(R.string.room_map_saved_places_category_format, categoryName, count)
                    }

                    val targetChipId = when (state.selectedCategory) {
                        PlaceCategory.ALL -> R.id.chip_all
                        PlaceCategory.DINING -> R.id.chip_dining
                        PlaceCategory.CAFE -> R.id.chip_cafe
                        PlaceCategory.SIGHTS -> R.id.chip_sights
                        PlaceCategory.STAY -> R.id.chip_stay
                        PlaceCategory.ETC -> R.id.chip_etc
                    }
                    if (binding.chipGroupCategory.checkedChipId != targetChipId) {
                        binding.chipGroupCategory.check(targetChipId)
                    }

                    roomPlaceAdapter.submitList(state.placeDetails)

                    if (state.totalPlaceCount == 0) {
                        binding.tvEmptyPlaces.isVisible = true
                        binding.tvEmptyPlaces.text = getString(R.string.room_map_empty_no_places)
                        binding.rvPlaces.isVisible = false
                    } else if (state.placeDetails.isEmpty()) {
                        binding.tvEmptyPlaces.isVisible = true
                        binding.tvEmptyPlaces.text = getString(R.string.room_map_empty_category)
                        binding.rvPlaces.isVisible = false
                    } else {
                        binding.tvEmptyPlaces.isVisible = false
                        binding.rvPlaces.isVisible = true
                    }

                    applyMapData()

                    val roomId = intent.getStringExtra(EXTRA_ROOM_ID).orEmpty()
                    shakeViewModel.setPlacesInMemory(
                        roomId = roomId,
                        places = viewModel.getAllPlaceDetails(),
                        category = viewModel.getSelectedCategory(),
                    )
                }
                is RoomMapUiState.Error -> {
                    binding.pbLoading.isVisible = false
                    binding.layoutError.isVisible = true
                    binding.tvErrorMessage.text = getString(state.messageResId)
                    binding.btnRetry.isVisible = true
                    shakeViewModel.setHomeError(state.messageResId)
                }
            }
        }

        viewModel.isInviteCodeDialogOpen.observe(this) { isOpen ->
            if (isOpen) {
                showInviteCodeDialog()
            } else {
                dismissInviteCodeDialog()
            }
        }

        viewModel.inviteCodeUiState.observe(this) { state ->
            val dialogBinding = inviteCodeDialogBinding
            if (dialogBinding != null) {
                renderInviteCodeDialogState(dialogBinding, state)
            }
        }

        shakeViewModel.isShakeDialogOpen.observe(this) { isOpen ->
            if (isOpen) {
                showShakeToPickBottomSheet()
            } else {
                dismissShakeToPickBottomSheet()
            }
        }

        shakeViewModel.uiState.observe(this) { state ->
            val dialogBinding = shakeDialogBinding
            if (dialogBinding != null) {
                renderShakeDialogState(dialogBinding, state)
            }
            updateSensorState()
        }
    }

    private fun updateSensorState() {
        val isDialogOpen = shakeBottomSheetDialog?.isShowing == true
        val isResumedState = lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
        val currentState = shakeViewModel.uiState.value
        val isAwaitingWithCandidates = currentState is com.pintrip.app.place.ShakeToPickUiState.Ready && currentState.filteredCount > 0

        val dialogBinding = shakeDialogBinding

        if (isDialogOpen && isResumedState && isAwaitingWithCandidates) {
            val isSensorStarted = shakeDetector?.start() == true
            if (dialogBinding != null) {
                if (isSensorStarted) {
                    dialogBinding.tvReadyHeading.setText(R.string.shake_ready_heading_sensor)
                    dialogBinding.tvReadyGuide.setText(R.string.shake_ready_guide_sensor)
                } else {
                    dialogBinding.tvReadyHeading.setText(R.string.shake_ready_heading_touch)
                    dialogBinding.tvReadyGuide.setText(R.string.shake_ready_guide_touch)
                }
            }
        } else {
            shakeDetector?.stop()
        }
    }

    private fun showShakeToPickBottomSheet() {
        if (shakeBottomSheetDialog?.isShowing == true) return

        val dialogBinding = com.pintrip.app.databinding.BottomSheetShakeToPickBinding.inflate(layoutInflater)
        shakeDialogBinding = dialogBinding

        dialogBinding.btnClose.setOnClickListener {
            dismissShakeToPickBottomSheet()
        }

        dialogBinding.btnTouchPick.setOnClickListener {
            shakeViewModel.pickRandomPlace()
        }

        dialogBinding.btnPickAgain.setOnClickListener {
            shakeViewModel.resetToReady()
        }

        dialogBinding.btnViewOnMap.setOnClickListener {
            val currentState = shakeViewModel.uiState.value
            if (currentState is com.pintrip.app.place.ShakeToPickUiState.Result) {
                val placeId = currentState.selectedPlace.place.kakaoPlaceId
                focusPlace(placeId)
            }
            dismissShakeToPickBottomSheet()
        }

        dialogBinding.btnEmptyClose.setOnClickListener {
            dismissShakeToPickBottomSheet()
        }

        val currentState = shakeViewModel.uiState.value ?: com.pintrip.app.place.ShakeToPickUiState.Idle
        renderShakeDialogState(dialogBinding, currentState)

        shakeBottomSheetDialog = com.google.android.material.bottomsheet.BottomSheetDialog(this)
            .apply {
                setContentView(dialogBinding.root)
                setCancelable(true)

                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true

                setOnDismissListener {
                    shakeDialogBinding = null
                    shakeBottomSheetDialog = null
                    updateSensorState()
                    if (!isCleaningUpForDestroy && !isChangingConfigurations) {
                        shakeViewModel.closeDialog()
                    }
                }
                show()
            }

        dialogBinding.root.post {
            updateSensorState()
        }
    }

    private fun dismissShakeToPickBottomSheet() {
        val dialog = shakeBottomSheetDialog
        shakeDialogBinding = null
        shakeBottomSheetDialog = null
        dialog?.dismiss()
        updateSensorState()
    }

    private fun renderShakeDialogState(
        dialogBinding: com.pintrip.app.databinding.BottomSheetShakeToPickBinding,
        state: com.pintrip.app.place.ShakeToPickUiState,
    ) {
        when (state) {
            is com.pintrip.app.place.ShakeToPickUiState.Idle,
            is com.pintrip.app.place.ShakeToPickUiState.Loading -> {
                dialogBinding.layoutLoading.isVisible = true
                dialogBinding.layoutReady.isVisible = false
                dialogBinding.layoutResult.isVisible = false
                dialogBinding.layoutEmpty.isVisible = false
            }
            is com.pintrip.app.place.ShakeToPickUiState.Ready -> {
                dialogBinding.layoutLoading.isVisible = false
                dialogBinding.layoutReady.isVisible = true
                dialogBinding.layoutResult.isVisible = false
                dialogBinding.layoutEmpty.isVisible = false

                val categoryName = getString(state.category.labelResId())
                dialogBinding.tvReadySubtitle.text = getString(
                    R.string.shake_ready_subtitle_format,
                    categoryName,
                    state.filteredCount,
                )
                dialogBinding.tvReadyCategoryBadge.text = categoryName
                dialogBinding.btnTouchPick.isEnabled = state.filteredCount > 0
            }
            is com.pintrip.app.place.ShakeToPickUiState.Result -> {
                dialogBinding.layoutLoading.isVisible = false
                dialogBinding.layoutReady.isVisible = false
                dialogBinding.layoutResult.isVisible = true
                dialogBinding.layoutEmpty.isVisible = false

                val place = state.selectedPlace
                dialogBinding.tvResultPlaceName.text = place.name.orEmpty().ifBlank { place.place.searchKeyword }

                val actualCategoryEnum = com.pintrip.app.place.PlaceCategory.fromCode(place.category)
                val actualCategoryName = getString(actualCategoryEnum.labelResId())
                dialogBinding.tvResultCategoryBadge.text = actualCategoryName
                val address = place.address.orEmpty()
                val addedBy = place.place.addedByNickname.orEmpty()

                dialogBinding.tvResultPlaceInfo.text = when {
                    address.isNotBlank() && addedBy.isNotBlank() -> {
                        getString(R.string.shake_place_info_with_added_by_format, actualCategoryName, address, getString(R.string.place_added_by, addedBy))
                    }
                    address.isNotBlank() -> {
                        getString(R.string.shake_place_info_format, actualCategoryName, address)
                    }
                    addedBy.isNotBlank() -> {
                        getString(R.string.shake_place_info_format, actualCategoryName, getString(R.string.place_added_by, addedBy))
                    }
                    else -> actualCategoryName
                }
            }
            is com.pintrip.app.place.ShakeToPickUiState.Empty -> {
                dialogBinding.layoutLoading.isVisible = false
                dialogBinding.layoutReady.isVisible = false
                dialogBinding.layoutResult.isVisible = false
                dialogBinding.layoutEmpty.isVisible = true

                val categoryName = getString(state.category.labelResId())
                dialogBinding.tvEmptyMessage.text = when (state.reason) {
                    com.pintrip.app.place.EmptyReason.NO_SAVED_PLACES -> getString(R.string.shake_empty_no_saved_places)
                    com.pintrip.app.place.EmptyReason.NO_AVAILABLE_PLACES -> getString(R.string.shake_empty_no_available_places)
                    com.pintrip.app.place.EmptyReason.NO_FILTER_MATCHES -> getString(R.string.shake_empty_no_filter_matches, categoryName)
                }
                dialogBinding.btnEmptyClose.text = getString(R.string.btn_close)
                dialogBinding.btnEmptyClose.setOnClickListener { dismissShakeToPickBottomSheet() }
            }
            is com.pintrip.app.place.ShakeToPickUiState.Error -> {
                dialogBinding.layoutLoading.isVisible = false
                dialogBinding.layoutReady.isVisible = false
                dialogBinding.layoutResult.isVisible = false
                dialogBinding.layoutEmpty.isVisible = true
                dialogBinding.tvEmptyMessage.text = getString(state.messageResId)
                dialogBinding.btnEmptyClose.text = getString(R.string.btn_retry)
                dialogBinding.btnEmptyClose.setOnClickListener {
                    val roomId = intent.getStringExtra(EXTRA_ROOM_ID).orEmpty()
                    viewModel.retry(roomId)
                    dismissShakeToPickBottomSheet()
                }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        updateSensorState()
    }

    private fun showInviteCodeDialog() {
        if (inviteCodeDialog?.isShowing == true) return

        val dialogBinding = com.pintrip.app.databinding.DialogInviteCodeDisplayBinding.inflate(layoutInflater)
        inviteCodeDialogBinding = dialogBinding

        dialogBinding.tvTitle.text = getString(R.string.room_invite_code_title)
        dialogBinding.tvSubtitle.text = getString(R.string.room_invite_code_subtext)

        val currentState = viewModel.inviteCodeUiState.value ?: InviteCodeUiState.Idle
        renderInviteCodeDialogState(dialogBinding, currentState)

        dialogBinding.btnCopyCode.setOnClickListener {
            val inviteCode = dialogBinding.tvInviteCode.text?.toString().orEmpty()
            if (inviteCode.isNotBlank()) {
                copyToClipboard(inviteCode)
            }
        }

        dialogBinding.btnShareLink.setOnClickListener {
            val inviteCode = dialogBinding.tvInviteCode.text?.toString().orEmpty()
            if (inviteCode.isNotBlank()) {
                shareInviteLink(inviteCode)
            }
        }

        dialogBinding.btnRetry.setOnClickListener {
            val roomId = intent.getStringExtra(EXTRA_ROOM_ID).orEmpty()
            viewModel.retryFetchInviteCode(roomId)
        }

        dialogBinding.btnConfirm.setOnClickListener {
            dismissInviteCodeDialog()
        }

        inviteCodeDialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setView(dialogBinding.root)
            .setCancelable(true)
            .setOnDismissListener {
                inviteCodeDialogBinding = null
                inviteCodeDialog = null
                if (!isCleaningUpForDestroy && !isChangingConfigurations) {
                    viewModel.onInviteCodeDialogDismissed()
                }
            }
            .show()
    }

    private fun renderInviteCodeDialogState(
        dialogBinding: com.pintrip.app.databinding.DialogInviteCodeDisplayBinding,
        state: InviteCodeUiState,
    ) {
        when (state) {
            is InviteCodeUiState.Idle -> {
                dialogBinding.pbLoading.isVisible = false
                dialogBinding.cardCodeContainer.isVisible = false
                dialogBinding.tvErrorMessage.isVisible = false
                dialogBinding.btnCopyCode.isVisible = false
                dialogBinding.btnShareLink.isVisible = false
                dialogBinding.btnRetry.isVisible = false
                dialogBinding.btnConfirm.isVisible = true
                dialogBinding.btnConfirm.text = getString(R.string.btn_close)
            }
            is InviteCodeUiState.Loading -> {
                dialogBinding.pbLoading.isVisible = true
                dialogBinding.cardCodeContainer.isVisible = false
                dialogBinding.tvErrorMessage.isVisible = false
                dialogBinding.btnCopyCode.isVisible = false
                dialogBinding.btnShareLink.isVisible = false
                dialogBinding.btnRetry.isVisible = false
                dialogBinding.btnConfirm.isVisible = true
                dialogBinding.btnConfirm.text = getString(R.string.btn_close)
            }
            is InviteCodeUiState.Success -> {
                dialogBinding.pbLoading.isVisible = false
                dialogBinding.cardCodeContainer.isVisible = true
                dialogBinding.tvInviteCode.text = state.inviteCode
                dialogBinding.tvErrorMessage.isVisible = false
                dialogBinding.btnCopyCode.isVisible = true
                dialogBinding.btnCopyCode.isEnabled = true
                dialogBinding.btnShareLink.isVisible = true
                dialogBinding.btnShareLink.isEnabled = true
                dialogBinding.btnRetry.isVisible = false
                dialogBinding.btnConfirm.isVisible = true
                dialogBinding.btnConfirm.text = getString(R.string.btn_close)
            }
            is InviteCodeUiState.Error -> {
                dialogBinding.pbLoading.isVisible = false
                dialogBinding.cardCodeContainer.isVisible = false
                dialogBinding.tvErrorMessage.isVisible = true
                dialogBinding.tvErrorMessage.text = getString(state.messageResId)
                dialogBinding.btnCopyCode.isVisible = false
                dialogBinding.btnShareLink.isVisible = false
                dialogBinding.btnRetry.isVisible = true
                dialogBinding.btnRetry.isEnabled = true
                dialogBinding.btnConfirm.isVisible = true
                dialogBinding.btnConfirm.text = getString(R.string.btn_close)
            }
        }
    }

    private fun dismissInviteCodeDialog() {
        val dialog = inviteCodeDialog
        inviteCodeDialogBinding = null
        inviteCodeDialog = null
        dialog?.dismiss()
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("invite_code", text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, R.string.notice_invite_code_copied, Toast.LENGTH_SHORT).show()
    }

    private fun shareInviteLink(code: String) {
        val baseUrl = getString(R.string.invite_web_share_base_url)
        val shareLink = DeepLinkUtils.buildWebShareUrl(code, baseUrl)
        val shareText = if (shareLink != null) {
            getString(R.string.share_invite_link_format, code, shareLink)
        } else {
            getString(R.string.share_invite_code_only_format, code)
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, shareText)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.title_share_invite_link)))
    }

    private fun applyMapData() {
        val map = kakaoMapInstance ?: return
        val success = currentSuccessState ?: return

        val labelManager = map.labelManager
        val layer = labelManager?.layer
        layer?.removeAll()
        labelPlaceMap.clear()

        val foundPlaces = success.placeDetails.filter { it.status == PlaceDetail.STATUS_FOUND && it.lat != null && it.lng != null }
        var addedLabelCount = 0

        if (foundPlaces.isNotEmpty() && labelStyles != null) {
            foundPlaces.forEach { place ->
                val latLng = LatLng.from(place.lat!!, place.lng!!)
                val textBuilder = LabelTextBuilder().setTexts(place.name.orEmpty())
                val options = LabelOptions.from(latLng)
                    .setStyles(labelStyles)
                    .setTexts(textBuilder)
                val label = layer?.addLabel(options)
                if (label != null) {
                    labelPlaceMap[label] = place.place.kakaoPlaceId
                    addedLabelCount++
                }
            }
        }

        Log.d(TAG, "foundPlaces: ${foundPlaces.size}, addedLabels: $addedLabelCount, isStyleNull: ${labelStyles == null}")

        when (val cmd = success.cameraCommand) {
            is CameraCommand.FitPoints -> {
                val latLngs = cmd.points.map { LatLng.from(it.first, it.second) }.toTypedArray()
                try {
                    val update = CameraUpdateFactory.fitMapPoints(latLngs, 100)
                    map.moveCamera(update)
                } catch (e: Throwable) {
                    Log.w(TAG, "fitMapPoints failed, falling back to center: ${e.message}", e)
                    val minLat = cmd.points.minOf { it.first }
                    val maxLat = cmd.points.maxOf { it.first }
                    val minLng = cmd.points.minOf { it.second }
                    val maxLng = cmd.points.maxOf { it.second }
                    val centerLat = (minLat + maxLat) / 2.0
                    val centerLng = (minLng + maxLng) / 2.0
                    map.moveCamera(CameraUpdateFactory.newCenterPosition(LatLng.from(centerLat, centerLng), 13))
                }
            }
            is CameraCommand.MoveToCenter -> {
                map.moveCamera(CameraUpdateFactory.newCenterPosition(LatLng.from(cmd.lat, cmd.lng), cmd.zoom))
            }
            is CameraCommand.MoveToKorea -> {
                map.moveCamera(CameraUpdateFactory.newCenterPosition(LatLng.from(35.9078, 127.7669), 7))
            }
            null -> {
                // No camera move command needed
            }
        }
    }
}
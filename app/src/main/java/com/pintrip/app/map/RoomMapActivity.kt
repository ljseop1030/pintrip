package com.pintrip.app.map

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.kakao.vectormap.KakaoMap
import com.kakao.vectormap.KakaoMapReadyCallback
import com.kakao.vectormap.KakaoMapSdk
import com.kakao.vectormap.LatLng
import com.kakao.vectormap.MapLifeCycleCallback
import com.kakao.vectormap.camera.CameraUpdateFactory
import com.kakao.vectormap.label.LabelOptions
import com.kakao.vectormap.label.LabelStyle
import com.kakao.vectormap.label.LabelStyles
import com.kakao.vectormap.label.LabelTextBuilder
import com.kakao.vectormap.label.LabelTextStyle
import com.pintrip.app.BuildConfig
import com.pintrip.app.R
import com.pintrip.app.data.model.PlaceDetail
import com.pintrip.app.databinding.ActivityRoomMapBinding
import com.pintrip.app.place.InviteCodeUiState
import com.pintrip.app.place.PlaceCategory
import com.pintrip.app.room.DeepLinkUtils
import java.util.concurrent.atomic.AtomicReference

class RoomMapActivity : AppCompatActivity() {

    enum class SdkInitState {
        NOT_INITIALIZED,
        SUCCESS,
        FAILED
    }

    companion object {
        private const val TAG = "RoomMapActivity"
        const val EXTRA_ROOM_ID = "extra_room_id"
        private val sdkInitState = AtomicReference(SdkInitState.NOT_INITIALIZED)

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
    private var currentSuccessState: RoomMapUiState.Success? = null
    private var bottomSheetBehavior: BottomSheetBehavior<*>? = null
    private lateinit var roomPlaceAdapter: RoomPlaceAdapter

    private var shakeDetector: com.pintrip.app.place.ShakeDetector? = null
    private var shakeBottomSheetDialog: com.google.android.material.bottomsheet.BottomSheetDialog? = null
    private var shakeDialogBinding: com.pintrip.app.databinding.BottomSheetShakeToPickBinding? = null

    private var isCleaningUpForDestroy = false
    private var inviteCodeDialog: androidx.appcompat.app.AlertDialog? = null
    private var inviteCodeDialogBinding: com.pintrip.app.databinding.DialogInviteCodeDisplayBinding? = null

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

        setupToolbar()
        setupBottomSheet()
        setupRecyclerView()
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

        val currentState = sdkInitState.get()
        if (currentState == SdkInitState.FAILED) {
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
            if (currentState == SdkInitState.NOT_INITIALIZED) {
                KakaoMapSdk.init(applicationContext, BuildConfig.KAKAO_NATIVE_APP_KEY)
                sdkInitState.set(SdkInitState.SUCCESS)
            }
            binding.tvMapUnavailable.isVisible = false
            setupMapView()
            setupListeners(roomId)
            observeViewModel()

            viewModel.loadRoomMap(roomId)
        } catch (e: Throwable) {
            Log.e(TAG, "KakaoMapSdk init failed on this device: ${e.message}", e)
            sdkInitState.set(SdkInitState.FAILED)
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
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
        binding.toolbar.inflateMenu(R.menu.menu_room_home)
        binding.toolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.action_invite_code -> {
                    viewModel.onInviteCodeClick()
                    true
                }
                else -> false
            }
        }
    }

    private fun setupBottomSheet() {
        bottomSheetBehavior = BottomSheetBehavior.from(binding.bottomSheet)
    }

    private fun setupRecyclerView() {
        roomPlaceAdapter = RoomPlaceAdapter(
            onFoundClick = { kakaoPlaceId ->
                focusPlace(kakaoPlaceId)
            },
            onNotFoundClick = { kakaoPlaceUrl ->
                openKakaoPlaceUrl(kakaoPlaceUrl)
            }
        )
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

    private fun openKakaoPlaceUrl(url: String) {
        if (url.isBlank()) {
            Toast.makeText(this, R.string.place_list_error_cannot_open_url, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.place_list_error_cannot_open_url, Toast.LENGTH_SHORT).show()
        }
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
                applyMapData()
            }
        })
    }

    private fun initLabelStyles(kakaoMap: KakaoMap) {
        if (labelStyles != null) return

        val drawable = ContextCompat.getDrawable(this, R.drawable.ic_map_marker)
        val bitmap = drawable?.toBitmap()
        if (bitmap == null) {
            Log.e(TAG, "Failed to convert ic_map_marker to bitmap")
            return
        }

        val textColor = ContextCompat.getColor(this, R.color.map_label_text)
        val strokeColor = ContextCompat.getColor(this, R.color.map_label_stroke)

        val textSizePx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            13f,
            resources.displayMetrics
        ).toInt()
        val strokeWidthPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            2f,
            resources.displayMetrics
        ).toInt()

        val labelStyle = LabelStyle.from(bitmap)
            .setAnchorPoint(0.5f, 1.0f)
            .setTextStyles(LabelTextStyle.from(textSizePx, textColor, strokeWidthPx, strokeColor))

        labelStyles = kakaoMap.labelManager?.addLabelStyles(LabelStyles.from(labelStyle))
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
    }

    private fun getCategoryNameResId(category: PlaceCategory): Int {
        return when (category) {
            PlaceCategory.ALL -> R.string.category_all
            PlaceCategory.DINING -> R.string.category_dining
            PlaceCategory.CAFE -> R.string.category_cafe
            PlaceCategory.SIGHTS -> R.string.category_sights
            PlaceCategory.STAY -> R.string.category_stay
            PlaceCategory.ETC -> R.string.category_etc
        }
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

                    binding.toolbar.title = state.roomName

                    val count = state.placeDetails.size
                    if (state.selectedCategory == PlaceCategory.ALL) {
                        binding.tvSheetTitle.text = getString(R.string.room_map_saved_places_all_format, count)
                    } else {
                        val categoryName = getString(getCategoryNameResId(state.selectedCategory))
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

        shakeViewModel.shakeEvent.observe(this) { _ ->
            // Shake event received; result UI is updated directly on screen
        }
    }

    private fun updateSensorState() {
        val isDialogOpen = shakeBottomSheetDialog?.isShowing == true
        val isResumedState = lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
        val currentState = shakeViewModel.uiState.value
        val isAwaitingWithCandidates = currentState is com.pintrip.app.place.ShakeToPickUiState.Ready && currentState.filteredCount > 0

        if (isDialogOpen && isResumedState && isAwaitingWithCandidates) {
            shakeDetector?.start()
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

                val categoryName = getString(getCategoryNameResId(state.category))
                dialogBinding.tvReadyHeading.text = getString(R.string.shake_ready_heading)
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
                val actualCategoryName = getString(getCategoryNameResId(actualCategoryEnum))
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

                val categoryName = getString(getCategoryNameResId(state.category))
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

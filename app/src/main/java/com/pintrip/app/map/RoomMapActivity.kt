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
import com.pintrip.app.place.PlaceCategory
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

    private var kakaoMapInstance: KakaoMap? = null
    private var labelStyles: LabelStyles? = null
    private var currentSuccessState: RoomMapUiState.Success? = null
    private var bottomSheetBehavior: BottomSheetBehavior<*>? = null
    private lateinit var roomPlaceAdapter: RoomPlaceAdapter

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

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
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
            // TODO: 흔들어서 고르기 팝업 연결 (정혁) - 결과는 focusPlace(kakaoPlaceId)로
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

        val drawable = ContextCompat.getDrawable(this, R.drawable.ic_pintrip_pin)
        val bitmap = drawable?.toBitmap()
        if (bitmap == null) {
            Log.e(TAG, "Failed to convert ic_pintrip_pin to bitmap")
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
                }
                is RoomMapUiState.Error -> {
                    binding.pbLoading.isVisible = false
                    binding.layoutError.isVisible = true
                    binding.tvErrorMessage.text = getString(state.messageResId)
                    binding.btnRetry.isVisible = true
                }
            }
        }
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

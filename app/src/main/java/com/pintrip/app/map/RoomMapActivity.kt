package com.pintrip.app.map

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import com.pintrip.app.BuildConfig
import com.pintrip.app.R
import com.pintrip.app.data.model.PlaceDetail
import com.pintrip.app.databinding.ActivityRoomMapBinding
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
    private var currentSuccessState: RoomMapUiState.Success? = null

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

        val currentState = sdkInitState.get()
        if (currentState == SdkInitState.FAILED) {
            binding.mapView.isVisible = false
            binding.pbLoading.isVisible = false
            binding.layoutError.isVisible = false
            binding.tvMapUnavailable.isVisible = true
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
        }
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
    }

    private fun setupMapView() {
        binding.mapView.start(object : MapLifeCycleCallback() {
            override fun onMapError(error: Exception) {
                Log.e(TAG, "onMapError: ${error.message}", error)
                Toast.makeText(this@RoomMapActivity, R.string.room_map_error_load_failed, Toast.LENGTH_SHORT).show()
            }

            override fun onMapDestroy() {
                // Map destroyed
            }
        }, object : KakaoMapReadyCallback() {
            override fun onMapReady(kakaoMap: KakaoMap) {
                kakaoMapInstance = kakaoMap
                applyMapData()
            }
        })
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
        if (foundPlaces.isNotEmpty()) {
            val styles = labelManager?.addLabelStyles(
                LabelStyles.from(LabelStyle.from(R.drawable.ic_pintrip_pin))
            )
            if (styles != null) {
                foundPlaces.forEach { place ->
                    val latLng = LatLng.from(place.lat!!, place.lng!!)
                    val textBuilder = LabelTextBuilder().setTexts(place.name.orEmpty())
                    val options = LabelOptions.from(latLng)
                        .setStyles(styles)
                        .setTexts(textBuilder)
                    layer?.addLabel(options)
                }
            }
        }

        when (val cmd = success.cameraCommand) {
            is CameraCommand.FitPoints -> {
                val centerLat = cmd.points.map { it.first }.average()
                val centerLng = cmd.points.map { it.second }.average()
                val minLat = cmd.points.minOf { it.first }
                val maxLat = cmd.points.maxOf { it.first }
                val minLng = cmd.points.minOf { it.second }
                val maxLng = cmd.points.maxOf { it.second }
                val maxSpan = maxOf(maxLat - minLat, maxLng - minLng)
                val zoom = when {
                    maxSpan > 1.0 -> 8
                    maxSpan > 0.5 -> 9
                    maxSpan > 0.2 -> 10
                    maxSpan > 0.1 -> 11
                    maxSpan > 0.05 -> 12
                    maxSpan > 0.02 -> 13
                    else -> 14
                }
                map.moveCamera(CameraUpdateFactory.newCenterPosition(LatLng.from(centerLat, centerLng), zoom))
            }
            is CameraCommand.MoveToCenter -> {
                map.moveCamera(CameraUpdateFactory.newCenterPosition(LatLng.from(cmd.lat, cmd.lng), cmd.zoom))
            }
            is CameraCommand.MoveToKorea -> {
                map.moveCamera(CameraUpdateFactory.newCenterPosition(LatLng.from(35.9078, 127.7669), 7))
            }
        }
    }
}

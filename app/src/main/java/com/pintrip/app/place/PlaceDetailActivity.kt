package com.pintrip.app.place

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import com.kakao.vectormap.KakaoMap
import com.kakao.vectormap.KakaoMapReadyCallback
import com.kakao.vectormap.LatLng
import com.kakao.vectormap.MapLifeCycleCallback
import com.kakao.vectormap.camera.CameraUpdateFactory
import com.kakao.vectormap.label.LabelOptions
import com.kakao.vectormap.label.LabelTextBuilder
import com.pintrip.app.R
import com.pintrip.app.data.model.PlaceDetail
import com.pintrip.app.databinding.ActivityPlaceDetailBinding
import com.pintrip.app.map.KakaoMapHelper

class PlaceDetailActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "PlaceDetailActivity"
        const val EXTRA_ROOM_ID = "extra_room_id"
        const val EXTRA_KAKAO_PLACE_ID = "extra_kakao_place_id"

        fun newIntent(context: Context, roomId: String, kakaoPlaceId: String): Intent {
            return Intent(context, PlaceDetailActivity::class.java).apply {
                putExtra(EXTRA_ROOM_ID, roomId)
                putExtra(EXTRA_KAKAO_PLACE_ID, kakaoPlaceId)
            }
        }
    }

    private lateinit var binding: ActivityPlaceDetailBinding
    private val viewModel: PlaceDetailViewModel by viewModels()

    private var kakaoMapInstance: KakaoMap? = null
    private var isMapViewInitialized = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val roomId = intent.getStringExtra(EXTRA_ROOM_ID)
        val kakaoPlaceId = intent.getStringExtra(EXTRA_KAKAO_PLACE_ID)

        if (roomId.isNullOrBlank() || kakaoPlaceId.isNullOrBlank()) {
            Toast.makeText(this, R.string.place_list_error_no_room_id, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        enableEdgeToEdge()
        binding = ActivityPlaceDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        setupButtons()
        observeViewModel()

        val initState = KakaoMapHelper.getSdkInitState()
        if (initState == KakaoMapHelper.SdkInitState.NOT_INITIALIZED) {
            KakaoMapHelper.initSdk(applicationContext)
        }

        viewModel.loadPlaceDetail(roomId, kakaoPlaceId)
    }

    private fun setupButtons() {
        binding.btnBack.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        binding.btnRetry.setOnClickListener {
            val roomId = intent.getStringExtra(EXTRA_ROOM_ID).orEmpty()
            val kakaoPlaceId = intent.getStringExtra(EXTRA_KAKAO_PLACE_ID).orEmpty()
            viewModel.retry(roomId, kakaoPlaceId)
        }

        binding.btnLike.setOnClickListener {
            viewModel.toggleLike()
        }
    }

    private fun observeViewModel() {
        viewModel.toastEvent.observe(this) { event ->
            event.getContentIfNotHandled()?.let { resId ->
                Toast.makeText(this, getString(resId), Toast.LENGTH_SHORT).show()
            }
        }

        viewModel.uiState.observe(this) { state ->
            when (state) {
                is PlaceDetailUiState.Loading -> {
                    binding.pbLoading.isVisible = true
                    binding.scrollView.isVisible = false
                    binding.layoutError.isVisible = false
                }
                is PlaceDetailUiState.Success -> {
                    binding.pbLoading.isVisible = false
                    binding.scrollView.isVisible = true
                    binding.layoutError.isVisible = false
                    renderSuccess(state)
                }
                is PlaceDetailUiState.Error -> {
                    binding.pbLoading.isVisible = false
                    binding.scrollView.isVisible = false
                    binding.layoutError.isVisible = true
                    binding.tvErrorMessage.text = getString(state.messageResId)
                }
                is PlaceDetailUiState.NotFoundInFirestore -> {
                    Toast.makeText(this, R.string.place_detail_deleted_toast, Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }
    }

    private fun renderSuccess(state: PlaceDetailUiState.Success) {
        val detail = state.placeDetail
        val isFound = detail.status == PlaceDetail.STATUS_FOUND

        // 좋아요 버튼 상태
        binding.btnLike.isEnabled = !state.isLikeInProgress
        if (state.isLikedByMe) {
            binding.btnLike.setIconResource(R.drawable.ic_heart_filled)
            binding.btnLike.setIconTintResource(R.color.place_heart_tint)
            binding.btnLike.contentDescription = getString(R.string.place_detail_unlike)
        } else {
            binding.btnLike.setIconResource(R.drawable.ic_heart_outline)
            binding.btnLike.setIconTintResource(R.color.place_heart_unliked_tint)
            binding.btnLike.contentDescription = getString(R.string.place_detail_like)
        }

        // 카카오맵 자세히 보기
        binding.layoutKakaoMapDetail.setOnClickListener {
            openKakaoPlaceUrl(detail.place.kakaoPlaceUrl)
        }

        // overflow menu 설정 (하위 호환성 유지)
        binding.btnMenu.setOnClickListener {
            showOverflowMenu(detail.place.kakaoPlaceUrl)
        }

        if (isFound) {
            binding.tvCategory.setText(state.categoryResId)
            binding.tvCategory.isVisible = true

            binding.tvPlaceName.text = detail.name.takeUnless { it.isNullOrBlank() } ?: detail.place.searchKeyword
            binding.tvAddress.text = detail.address
            binding.tvAddress.isVisible = true

            if (!detail.phone.isNullOrBlank()) {
                binding.tvPhone.text = detail.phone
                binding.layoutPhone.isVisible = true
                binding.layoutPhone.setOnClickListener { openDialer(detail.phone) }
            } else {
                binding.layoutPhone.isVisible = false
            }

            binding.btnDirections.isVisible = true
            binding.btnDirections.setOnClickListener {
                openKakaoMapDirections(detail.place.kakaoPlaceId)
            }

            val sdkState = KakaoMapHelper.getSdkInitState()
            if (sdkState == KakaoMapHelper.SdkInitState.FAILED) {
                binding.layoutMapContainer.isVisible = true
                binding.mapView.isVisible = false
                binding.tvMapUnavailable.isVisible = true
            } else {
                binding.layoutMapContainer.isVisible = true
                binding.tvMapUnavailable.isVisible = false
                binding.mapView.isVisible = true
                setupMapView(detail)
            }

            binding.cardNotFoundNotice.isVisible = false
        } else {
            binding.tvPlaceName.text = detail.place.searchKeyword
            binding.tvCategory.isVisible = false
            binding.tvAddress.isVisible = false
            binding.layoutPhone.isVisible = false
            binding.layoutMapContainer.isVisible = false
            binding.btnDirections.isVisible = false
            binding.cardNotFoundNotice.isVisible = true
        }

        // Likes Card
        binding.tvLikesCount.text = getString(R.string.place_detail_likes_count_format, detail.place.likeCount)
        if (state.likedUsers.isEmpty()) {
            binding.tvLikedNicknames.text = getString(R.string.place_detail_no_likes)
        } else {
            binding.tvLikedNicknames.text = state.likedUsers.joinToString(", ") { it.nickname }
        }

        val avatarViews = listOf(binding.tvAvatar1, binding.tvAvatar2, binding.tvAvatar3, binding.tvAvatar4)
        avatarViews.forEachIndexed { index, tv ->
            if (index < state.likedUsers.size) {
                val user = state.likedUsers[index]
                tv.text = user.nickname.take(1).uppercase()
                val bg = ContextCompat.getDrawable(this, R.drawable.bg_circle_avatar_border)?.mutate()
                bg?.setTint(parseColorSafely(user.avatarColor, ContextCompat.getColor(this, R.color.pintrip_blue)))
                tv.background = bg
                tv.isVisible = true
            } else {
                tv.isVisible = false
            }
        }

        // Source Card
        val sourceType = detail.place.sourceType
        val sourceUrl = detail.place.sourceUrl
        when (sourceType) {
            "screenshot" -> {
                binding.tvSourceBadge.text = "IMG"
                binding.tvSourceBadge.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#E5EBFE"))
                binding.tvSourceBadge.setTextColor(Color.parseColor("#4B60C0"))
                binding.tvSourceText.text = getString(R.string.place_detail_source_screenshot)
                binding.tvSourceOpen.isVisible = false
                binding.cardSource.setOnClickListener(null)
            }
            "text" -> {
                binding.tvSourceBadge.text = "TXT"
                binding.tvSourceBadge.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#EDE7FB"))
                binding.tvSourceBadge.setTextColor(Color.parseColor("#5B45A8"))
                binding.tvSourceText.text = getString(R.string.place_detail_source_text)
                binding.tvSourceOpen.isVisible = false
                binding.cardSource.setOnClickListener(null)
            }
            "link" -> {
                val domain = extractDomain(sourceUrl)
                binding.tvSourceBadge.text = "LINK"
                binding.tvSourceBadge.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#DDF3EA"))
                binding.tvSourceBadge.setTextColor(Color.parseColor("#1E7A5C"))
                binding.tvSourceText.text = if (domain.isNotBlank()) {
                    getString(R.string.place_detail_source_link_format, domain)
                } else {
                    getString(R.string.place_detail_source_open)
                }
                binding.tvSourceOpen.isVisible = true
                binding.cardSource.setOnClickListener { openExternalUrl(sourceUrl) }
            }
            else -> {
                binding.tvSourceBadge.text = "PIN"
                binding.tvSourceBadge.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#FFEDE3"))
                binding.tvSourceBadge.setTextColor(Color.parseColor("#A85628"))
                binding.tvSourceText.text = detail.place.searchKeyword
                binding.tvSourceOpen.isVisible = false
                binding.cardSource.setOnClickListener(null)
            }
        }

        // Added By Card
        val addedBy = state.addedByUserInfo
        binding.tvAddedByNickname.text = addedBy.nickname
        binding.tvAddedByAvatar.text = addedBy.nickname.take(1).uppercase()
        val addedByBg = ContextCompat.getDrawable(this, R.drawable.bg_circle_avatar)?.mutate()
        addedByBg?.setTint(parseColorSafely(addedBy.avatarColor, ContextCompat.getColor(this, R.color.pintrip_blue)))
        binding.tvAddedByAvatar.background = addedByBg
        binding.tvCreatedAt.text = state.formattedCreatedAt
    }

    private fun setupMapView(placeDetail: PlaceDetail) {
        if (isMapViewInitialized) return
        val lat = placeDetail.lat ?: return
        val lng = placeDetail.lng ?: return

        isMapViewInitialized = true

        binding.mapView.start(object : MapLifeCycleCallback() {
            override fun onMapError(error: Exception) {
                Log.e(TAG, "onMapError: ${error.message}", error)
                runOnUiThread {
                    binding.mapView.isVisible = false
                    binding.tvMapUnavailable.isVisible = true
                }
            }

            override fun onMapDestroy() {}
        }, object : KakaoMapReadyCallback() {
            override fun onMapReady(kakaoMap: KakaoMap) {
                kakaoMapInstance = kakaoMap
                val labelStyles = KakaoMapHelper.createLabelStyles(this@PlaceDetailActivity, kakaoMap)
                if (labelStyles != null) {
                    val latLng = LatLng.from(lat, lng)
                    val textBuilder = LabelTextBuilder().setTexts(placeDetail.name.orEmpty())
                    val options = LabelOptions.from(latLng)
                        .setStyles(labelStyles)
                        .setTexts(textBuilder)
                    kakaoMap.labelManager?.layer?.addLabel(options)
                    kakaoMap.moveCamera(CameraUpdateFactory.newCenterPosition(latLng, 15))
                }
            }
        })
    }

    private fun showOverflowMenu(kakaoPlaceUrl: String) {
        val popup = PopupMenu(this, binding.btnMenu)
        popup.menu.add(0, 1, 0, R.string.place_detail_open_kakao_map)
        popup.setOnMenuItemClickListener { menuItem ->
            if (menuItem.itemId == 1) {
                openKakaoPlaceUrl(kakaoPlaceUrl)
                true
            } else {
                false
            }
        }
        popup.show()
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

    private fun openKakaoMapDirections(kakaoPlaceId: String) {
        val directionsUrl = "https://map.kakao.com/link/to/$kakaoPlaceId"
        openKakaoPlaceUrl(directionsUrl)
    }

    private fun openDialer(phone: String) {
        try {
            val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${phone.trim()}"))
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.place_detail_error_cannot_dial, Toast.LENGTH_SHORT).show()
        }
    }

    private fun openExternalUrl(url: String?) {
        if (url.isNullOrBlank()) return
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.place_list_error_cannot_open_url, Toast.LENGTH_SHORT).show()
        }
    }

    private fun parseColorSafely(colorString: String?, defaultColor: Int): Int {
        if (colorString.isNullOrBlank()) return defaultColor
        return try {
            Color.parseColor(colorString)
        } catch (e: Exception) {
            defaultColor
        }
    }

    private fun extractDomain(url: String?): String {
        if (url.isNullOrBlank()) return ""
        return try {
            val uri = Uri.parse(url)
            val host = uri.host
            if (!host.isNullOrBlank()) {
                host.removePrefix("www.")
            } else {
                url
            }
        } catch (e: Exception) {
            url
        }
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
}

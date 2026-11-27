package com.pintrip.app.place

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
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
import com.pintrip.app.R
import com.pintrip.app.data.model.PlaceDetail
import com.pintrip.app.databinding.ActivityPlaceDetailBinding
import com.pintrip.app.map.KakaoMapHelper
import com.pintrip.app.ui.common.AvatarPalette

class PlaceDetailActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "PlaceDetailActivity"
        /** 마스코트 마커는 꼬리가 없어 좌표를 이미지 중앙에 둔다 (시안 12) */
        private const val MASCOT_MARKER_ANCHOR_Y = 0.5f
        private const val CONTENT_ENTER_DURATION_MS = 320L
        private const val BOTTOM_BAR_ENTER_DELAY_MS = 40L
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
    /** 지도는 그대로 두고 콘텐츠만 올라오는 진입 연출. 화면 재생성 시에는 재생하지 않는다 */
    private var shouldPlayEnterAnimation = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val roomId = intent.getStringExtra(EXTRA_ROOM_ID)
        val kakaoPlaceId = intent.getStringExtra(EXTRA_KAKAO_PLACE_ID)

        if (roomId.isNullOrBlank() || kakaoPlaceId.isNullOrBlank()) {
            Toast.makeText(this, R.string.place_list_error_no_room_id, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // 창을 밀어 올리면 지도까지 움직여 연속성이 깨지므로 창은 페이드만 하고,
        // 올라오는 느낌은 콘텐츠 애니메이션이 담당한다
        overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, R.anim.place_detail_enter, R.anim.hold)
        overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, R.anim.hold, R.anim.place_detail_exit)
        shouldPlayEnterAnimation = savedInstanceState == null

        enableEdgeToEdge()
        binding = ActivityPlaceDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 지도는 상태바까지, 하단 바는 화면 맨 아래까지 채운다.
        // root에 top/bottom을 주면 지도가 밀리고 하단 바 아래에 배경색 띠가 하나 더 생긴다.
        val backGapTop = resources.getDimensionPixelSize(R.dimen.place_detail_back_gap_top)
        val barPaddingBottom = resources.getDimensionPixelSize(R.dimen.bottom_bar_padding_bottom)
        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, 0, systemBars.right, 0)

            val backMarginTop = systemBars.top + backGapTop
            val backParams = binding.btnBack.layoutParams as ViewGroup.MarginLayoutParams
            if (backParams.topMargin != backMarginTop) {
                backParams.topMargin = backMarginTop
                binding.btnBack.layoutParams = backParams
            }

            binding.layoutBottomBar.setPadding(
                binding.layoutBottomBar.paddingLeft,
                binding.layoutBottomBar.paddingTop,
                binding.layoutBottomBar.paddingRight,
                systemBars.bottom + barPaddingBottom
            )
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
                    playContentEnterAnimation()
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
                val colors = AvatarPalette.colorsFor(user.avatarColor, user.uid)
                val bg = ContextCompat.getDrawable(this, R.drawable.bg_circle_avatar_border)?.mutate()
                bg?.setTint(ContextCompat.getColor(this, colors.backgroundRes))
                tv.background = bg
                tv.setTextColor(ContextCompat.getColor(this, colors.textRes))
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
        val addedByColors = AvatarPalette.colorsFor(addedBy.avatarColor, addedBy.nickname)
        val addedByBg = ContextCompat.getDrawable(this, R.drawable.bg_circle_avatar)?.mutate()
        addedByBg?.setTint(ContextCompat.getColor(this, addedByColors.backgroundRes))
        binding.tvAddedByAvatar.background = addedByBg
        binding.tvAddedByAvatar.setTextColor(ContextCompat.getColor(this, addedByColors.textRes))
        binding.tvCreatedAt.text = state.formattedCreatedAt
    }

    /** 콘텐츠와 하단 바만 아래에서 올라오게 해서 바텀시트가 열리는 느낌을 만든다 */
    private fun playContentEnterAnimation() {
        if (!shouldPlayEnterAnimation) return
        shouldPlayEnterAnimation = false

        val distance = resources.getDimensionPixelSize(R.dimen.place_detail_enter_translation).toFloat()
        val targets = listOf(binding.scrollView, binding.layoutBottomBar)
        targets.forEachIndexed { index, view ->
            view.translationY = distance
            view.alpha = 0f
            view.animate()
                .translationY(0f)
                .alpha(1f)
                .setStartDelay(if (index == 0) 0L else BOTTOM_BAR_ENTER_DELAY_MS)
                .setDuration(CONTENT_ENTER_DURATION_MS)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
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
                // 시안 12는 핀 대신 마스코트를 좌표 중앙에 둔다. 장소명은 아래 카드에 있으므로
                // 마커에는 텍스트를 붙이지 않는다
                val labelStyles = KakaoMapHelper.createMascotLabelStyles(
                    context = this@PlaceDetailActivity,
                    kakaoMap = kakaoMap,
                    widthDimenResId = R.dimen.map_marker_mascot_detail_width,
                    anchorY = MASCOT_MARKER_ANCHOR_Y,
                    withText = false,
                )
                if (labelStyles != null) {
                    val latLng = LatLng.from(lat, lng)
                    val options = LabelOptions.from(latLng).setStyles(labelStyles)
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

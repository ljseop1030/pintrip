package com.pintrip.app.room

import android.content.Intent
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.Source
import com.pintrip.app.BuildConfig
import com.pintrip.app.R
import com.pintrip.app.auth.TestLoginActivity
import com.pintrip.app.data.model.Place
import com.pintrip.app.data.repository.PlaceRepository
import com.pintrip.app.databinding.ActivityTestRoomBinding
import com.pintrip.app.place.PlaceListActivity
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 개발(Debug) 전용 테스트 방(test_room_1) 장소 저장/조회 검증 화면.
 *
 * 로그인된 사용자만 진입 가능하며, 로그아웃 시 스태킹된 액티비티를 모두 비우고
 * [TestLoginActivity]로 이동하여 뒤로가기 재진입을 방지합니다.
 */
class TestRoomActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTestRoomBinding
    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val placeRepository: PlaceRepository by lazy { PlaceRepository(auth = auth) }

    companion object {
        private const val TEST_ROOM_ID = "test_room_1"
        private const val DEFAULT_NICKNAME = "테스트유저"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val currentUser = auth.currentUser
        // Debug 빌드가 아니거나 로그인되어 있지 않은 경우 진입 차단
        if (!BuildConfig.DEBUG || currentUser == null) {
            navigateToLogin()
            return
        }

        enableEdgeToEdge()
        binding = ActivityTestRoomBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        binding.tvRoomInfo.text = getString(R.string.test_room_info, TEST_ROOM_ID, currentUser.uid)

        binding.btnAddPlace.setOnClickListener {
            performAddPlace()
        }

        binding.btnGetPlaces.setOnClickListener {
            performGetPlaces()
        }

        binding.btnViewPlaceList.setOnClickListener {
            val intent = PlaceListActivity.newIntent(this, TEST_ROOM_ID)
            startActivity(intent)
        }

        binding.btnLogout.setOnClickListener {
            auth.signOut()
            navigateToLogin()
        }
    }

    private fun performAddPlace() {
        val currentUser = auth.currentUser ?: return

        val kakaoPlaceId = binding.etKakaoPlaceId.text?.toString()?.trim().orEmpty()
        val kakaoPlaceUrl = binding.etKakaoPlaceUrl.text?.toString()?.trim().orEmpty()
        val searchKeyword = binding.etSearchKeyword.text?.toString()?.trim().orEmpty()

        if (kakaoPlaceId.isEmpty()) {
            binding.tilKakaoPlaceId.error = getString(R.string.error_empty_place_id)
            return
        } else {
            binding.tilKakaoPlaceId.error = null
        }

        if (kakaoPlaceUrl.isEmpty()) {
            binding.tilKakaoPlaceUrl.error = getString(R.string.error_empty_place_url)
            return
        } else {
            binding.tilKakaoPlaceUrl.error = null
        }

        if (searchKeyword.isEmpty()) {
            binding.tilSearchKeyword.error = getString(R.string.error_empty_search_keyword)
            return
        } else {
            binding.tilSearchKeyword.error = null
        }

        val newPlace = Place(
            kakaoPlaceId = kakaoPlaceId,
            kakaoPlaceUrl = kakaoPlaceUrl,
            searchKeyword = searchKeyword,
            searchRegion = null,
            matchType = "manual",
            sourceType = "link",
            sourceUrl = null,
            addedBy = currentUser.uid,
            addedByNickname = DEFAULT_NICKNAME,
            likedBy = emptyList(),
            likeCount = 0,
            createdAt = null,
        )

        binding.tvPlaceResult.text = getString(R.string.status_add_in_progress, TEST_ROOM_ID)
        binding.btnAddPlace.isEnabled = false

        lifecycleScope.launch {
            try {
                val result = placeRepository.addPlaces(TEST_ROOM_ID, listOf(newPlace))
                binding.tvPlaceResult.text = getString(R.string.status_add_success, result.addedCount, result.skippedCount)
            } catch (e: Exception) {
                binding.tvPlaceResult.text = getString(R.string.status_add_failed, e.localizedMessage ?: e.message.orEmpty())
            } finally {
                binding.btnAddPlace.isEnabled = true
            }
        }
    }

    private fun performGetPlaces() {
        if (auth.currentUser == null) return

        binding.tvPlaceResult.text = getString(R.string.status_get_in_progress)
        binding.btnGetPlaces.isEnabled = false

        lifecycleScope.launch {
            try {
                val places = placeRepository.getPlaces(TEST_ROOM_ID, source = Source.SERVER)
                if (places.isEmpty()) {
                    binding.tvPlaceResult.text = getString(R.string.status_get_empty)
                } else {
                    val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                    val justNowStr = getString(R.string.place_item_date_just_now)
                    val sb = StringBuilder(getString(R.string.status_get_success, places.size))
                    places.forEachIndexed { index, place ->
                        val dateStr = place.createdAt?.toDate()?.let { dateFormat.format(it) } ?: justNowStr
                        sb.append(
                            getString(
                                R.string.place_item_format,
                                index + 1,
                                place.kakaoPlaceId,
                                place.searchKeyword,
                                place.addedByNickname,
                                place.addedBy,
                                dateStr,
                                place.kakaoPlaceUrl,
                            ),
                        )
                    }
                    binding.tvPlaceResult.text = sb.toString().trimEnd()
                }
            } catch (e: Exception) {
                binding.tvPlaceResult.text = getString(R.string.status_get_failed, e.localizedMessage ?: e.message.orEmpty())
            } finally {
                binding.btnGetPlaces.isEnabled = true
            }
        }
    }

    private fun navigateToLogin() {
        val intent = Intent(this, TestLoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }
}

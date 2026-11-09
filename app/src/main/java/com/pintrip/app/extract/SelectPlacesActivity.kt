package com.pintrip.app.extract

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pintrip.app.R
import com.pintrip.app.databinding.ActivitySelectPlacesBinding

/**
 * 장소 매칭 결과 목록을 확인하고 저장할 장소를 선택하는 화면.
 *
 * docs/data-contract.md §9 및 DESIGN.md (S07 장소 선택) 규격을 준수합니다:
 * 1. 화면 상태(매칭 결과, 체크 상태)를 [SelectPlacesViewModel]에서 관리하여 화면 회전 시에도 유지됩니다.
 * 2. 방 이름과 지역명은 Firestore `rooms/{roomId}`에서 단건 조회하여 하단 및 헤더에 표시합니다.
 * 3. NOT_FOUND 항목은 체크박스가 비활성화(disabled) 처리됩니다.
 * 4. regionMismatch 항목은 경고 배지와 함께 기본 해제(unchecked) 상태로 시작하며, 진입 시 Alert Dialog 팝업으로 안내합니다.
 * 5. CHECK 상태 항목은 첫 번째 후보가 자동 선택됩니다 (S08 개발 전 임시 처리).
 */
class SelectPlacesActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySelectPlacesBinding
    private lateinit var adapter: ItemSelectPlaceAdapter
    private val viewModel: SelectPlacesViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivitySelectPlacesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        parseIntentAndInitViewModel()
        setupViews()
        observeViewModel()
    }

    private fun parseIntentAndInitViewModel() {
        val roomId = intent.getStringExtra(EXTRA_ROOM_ID).orEmpty()

        val resultsExtra = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getSerializableExtra(EXTRA_MATCH_RESULTS, ArrayList::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getSerializableExtra(EXTRA_MATCH_RESULTS) as? ArrayList<*>
        }

        val initialResults = resultsExtra?.filterIsInstance<MatchResult>() ?: emptyList()
        viewModel.init(roomId, initialResults)
    }

    private fun setupViews() {
        binding.btnBack.setOnClickListener {
            finish()
        }

        adapter = ItemSelectPlaceAdapter(
            onItemCheckChanged = { index, isChecked ->
                viewModel.toggleCheck(index, isChecked)
            },
            onItemClicked = { index, item ->
                Log.d(TAG, "Clicked item at index $index: $item")
                // TODO: Navigate to S08 Confirm Location (candidate selection) screen when candidate choice is requested
            },
        )

        binding.rvPlaces.layoutManager = LinearLayoutManager(this)
        binding.rvPlaces.adapter = adapter

        binding.btnAddPlaces.setOnClickListener {
            val selectedCount = viewModel.getConfirmableCheckedCount()
            Log.d(TAG, "Add places clicked with $selectedCount places selected")
            // TODO: Connect place confirm / Firestore saving logic
        }
    }

    private fun observeViewModel() {
        viewModel.matchResults.observe(this) { results ->
            adapter.updateItems(results)

            val totalCount = results.size
            binding.tvCountBadge.text = getString(R.string.select_places_count_format, totalCount)
            binding.tvHeadline.text = getString(R.string.select_places_headline_format, totalCount)

            updateBottomBar()
        }

        viewModel.roomName.observe(this) { name ->
            if (!name.isNullOrBlank()) {
                binding.tvRoomTarget.text = getString(R.string.select_places_add_to_room_format, name)
            }
        }

        viewModel.roomRegion.observe(this) { region ->
            if (viewModel.roomName.value.isNullOrBlank() && !region.isNullOrBlank()) {
                binding.tvRoomTarget.text = getString(R.string.select_places_add_to_room_format, region)
            }
        }

        viewModel.regionMismatchNotice.observe(this) { event ->
            event.getContentIfNotHandled()?.let { notice ->
                showRegionMismatchDialog(notice.mismatchCount, notice.roomRegion)
            }
        }
    }

    private fun showRegionMismatchDialog(mismatchCount: Int, roomRegion: String) {
        val message = getString(R.string.select_places_region_mismatch_notice, mismatchCount, roomRegion)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.select_places_region_mismatch_title)
            .setMessage(message)
            .setPositiveButton(R.string.common_confirm, null)
            .show()
    }

    private fun updateBottomBar() {
        val confirmableCheckedCount = viewModel.getConfirmableCheckedCount()
        binding.btnAddPlaces.text = getString(R.string.select_places_btn_add_places_format, confirmableCheckedCount)
        binding.btnAddPlaces.isEnabled = (confirmableCheckedCount > 0)
    }

    companion object {
        private const val TAG = "SelectPlacesActivity"
        private const val EXTRA_ROOM_ID = "EXTRA_ROOM_ID"
        private const val EXTRA_MATCH_RESULTS = "EXTRA_MATCH_RESULTS"

        fun newIntent(context: Context, roomId: String, matchResults: List<MatchResult>): Intent {
            return Intent(context, SelectPlacesActivity::class.java).apply {
                putExtra(EXTRA_ROOM_ID, roomId)
                putExtra(EXTRA_MATCH_RESULTS, ArrayList(matchResults))
            }
        }
    }
}

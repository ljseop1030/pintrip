package com.pintrip.app.extract

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pintrip.app.R
import com.pintrip.app.databinding.ActivitySelectPlacesBinding
import com.pintrip.app.place.PlaceListActivity

/**
 * 장소 매칭 결과 목록을 확인하고 저장할 장소를 선택하는 화면.
 *
 * docs/data-contract.md §9 및 DESIGN.md (S07 장소 선택) 규격을 준수합니다:
 * 1. 화면 상태(매칭 결과, 체크 상태)를 [SelectPlacesViewModel]에서 관리하여 화면 회전 시에도 유지됩니다.
 * 2. 방 이름과 지역명은 Firestore `rooms/{roomId}`에서 단건 조회하여 하단 및 헤더에 표시합니다.
 * 3. NOT_FOUND 항목은 체크박스가 비활성화(disabled) 처리됩니다.
 * 4. regionMismatch 항목은 경고 배지와 함께 기본 해제(unchecked) 상태로 시작하며, 진입 시 Alert Dialog 팝업으로 안내합니다.
 * 5. CHECK 상태 항목을 탭하면 [ConfirmLocationActivity]에서 후보를 선택하거나 제외할 수 있습니다.
 * 6. 선택된 장소는 [Place] 객체로 변환하여 Firestore에 저장 후 토스트를 띄우고 화면을 닫습니다.
 */
class SelectPlacesActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySelectPlacesBinding
    private lateinit var adapter: ItemSelectPlaceAdapter
    private val viewModel: SelectPlacesViewModel by viewModels()

    private val confirmLocationLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val data = result.data ?: return@registerForActivityResult
            val position = ConfirmLocationActivity.getPosition(data)
            val updatedMatchResult = ConfirmLocationActivity.getMatchResult(data)
            if (position != -1 && updatedMatchResult != null) {
                viewModel.updateItemAt(position, updatedMatchResult)
            }
        }
    }

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
                if (item.status == MatchResult.STATUS_CHECK && item.candidates.isNotEmpty()) {
                    val intent = ConfirmLocationActivity.newIntent(
                        context = this,
                        matchResult = item,
                        roomRegion = viewModel.roomRegion.value.orEmpty(),
                        position = index,
                    )
                    confirmLocationLauncher.launch(intent)
                }
            },
        )

        binding.rvPlaces.layoutManager = LinearLayoutManager(this)
        binding.rvPlaces.adapter = adapter

        binding.btnAddPlaces.setOnClickListener {
            val selectedCount = viewModel.getConfirmableCheckedCount()
            Log.d(TAG, "Add places clicked with $selectedCount places selected")
            viewModel.saveSelectedPlaces()
        }
    }

    private fun observeViewModel() {
        viewModel.matchResults.observe(this) { results ->
            adapter.updateItems(results)

            val totalCount = results.size
            binding.tvCountBadge.text = getString(R.string.select_places_count_format, totalCount)

            val sourceType = results.firstOrNull()?.sourceType.orEmpty()
            val headlineFormatRes = when (sourceType) {
                "link" -> R.string.select_places_headline_link_format
                "text" -> R.string.select_places_headline_text_format
                else -> R.string.select_places_headline_screenshot_format
            }
            binding.tvHeadline.text = getString(headlineFormatRes, totalCount)

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

        viewModel.isSaving.observe(this) {
            updateBottomBar()
        }

        viewModel.saveResultEvent.observe(this) { event ->
            event.getContentIfNotHandled()?.let { result ->
                val message = formatSaveResultMessage(result)
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                Log.d(TAG, "Saved places: added=${result.addedCount}, skipped=${result.skippedCount}, excluded=${result.excludedCount}")

                val roomId = viewModel.roomId.value.orEmpty()
                startActivity(PlaceListActivity.newIntent(this, roomId))
                finish()
            }
        }

        viewModel.unconfirmedCheckNotice.observe(this) { event ->
            event.getContentIfNotHandled()?.let { unconfirmedCount ->
                showUnconfirmedDialog(unconfirmedCount)
            }
        }

        viewModel.regionMismatchNotice.observe(this) { event ->
            event.getContentIfNotHandled()?.let { notice ->
                showRegionMismatchDialog(notice.mismatchCount, notice.roomRegion)
            }
        }

        viewModel.noticeEvent.observe(this) { event ->
            event.getContentIfNotHandled()?.let { notice ->
                handleNoticeEvent(notice)
            }
        }
    }

    private fun formatSaveResultMessage(result: SavePlacesResultState): String {
        val detailsList = mutableListOf<String>()
        if (result.skippedCount > 0) {
            detailsList.add(getString(R.string.select_places_detail_duplicate, result.skippedCount))
        }
        if (result.excludedCount > 0) {
            detailsList.add(getString(R.string.select_places_detail_excluded, result.excludedCount))
        }
        if (result.notFoundCount > 0) {
            detailsList.add(getString(R.string.select_places_detail_not_found, result.notFoundCount))
        }
        val detailsStr = detailsList.joinToString(", ")

        return when {
            result.addedCount > 0 && detailsStr.isNotBlank() -> {
                getString(R.string.select_places_save_success_with_details_format, result.addedCount, detailsStr)
            }
            result.addedCount > 0 -> {
                getString(R.string.select_places_save_success_format, result.addedCount)
            }
            detailsStr.isNotBlank() -> {
                getString(R.string.select_places_save_none_with_details_format, detailsStr)
            }
            else -> {
                getString(R.string.select_places_save_none)
            }
        }
    }

    private fun showUnconfirmedDialog(unconfirmedCount: Int) {
        val message = getString(R.string.select_places_unconfirmed_dialog_message, unconfirmedCount)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.select_places_unconfirmed_dialog_title)
            .setMessage(message)
            .setPositiveButton(R.string.select_places_unconfirmed_continue) { _, _ ->
                viewModel.saveSelectedPlaces(skipUnconfirmed = true)
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun handleNoticeEvent(event: ShareNoticeEvent) {
        when (event) {
            is ShareNoticeEvent.ToastNotice -> {
                val message = if (event.formatArgs.isNotEmpty()) {
                    getString(event.messageResId, *event.formatArgs.toTypedArray())
                } else {
                    getString(event.messageResId)
                }
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
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
        val isSaving = viewModel.isSaving.value ?: false
        val confirmableCheckedCount = viewModel.getConfirmableCheckedCount()
        val hasCheckedItems = viewModel.hasAnyCheckedItems()
        binding.btnAddPlaces.text = getString(R.string.select_places_btn_add_places_format, confirmableCheckedCount)
        binding.btnAddPlaces.isEnabled = !isSaving && hasCheckedItems
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

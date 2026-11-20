package com.pintrip.app.extract

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.pintrip.app.R
import com.pintrip.app.databinding.ActivityConfirmLocationBinding

class ConfirmLocationActivity : AppCompatActivity() {

    private lateinit var binding: ActivityConfirmLocationBinding
    private lateinit var adapter: ItemConfirmCandidateAdapter
    private val viewModel: ConfirmLocationViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityConfirmLocationBinding.inflate(layoutInflater)
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
        val roomRegion = intent.getStringExtra(EXTRA_ROOM_REGION).orEmpty()
        val position = intent.getIntExtra(EXTRA_POSITION, -1)

        val matchResult = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getSerializableExtra(EXTRA_MATCH_RESULT, MatchResult::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getSerializableExtra(EXTRA_MATCH_RESULT) as? MatchResult
        }

        if (matchResult != null) {
            viewModel.init(matchResult, roomRegion, position)
        } else {
            finish()
        }
    }

    private fun setupViews() {
        binding.btnBack.setOnClickListener {
            finish()
        }

        adapter = ItemConfirmCandidateAdapter(
            onCandidateSelected = { candidate ->
                viewModel.selectCandidate(candidate.kakaoPlaceId)
            },
        )

        binding.rvCandidates.layoutManager = LinearLayoutManager(this)
        binding.rvCandidates.adapter = adapter

        binding.btnExclude.setOnClickListener {
            viewModel.onExcludeClicked()
        }

        binding.btnSelectThisLocation.setOnClickListener {
            viewModel.onSelectThisLocationClicked()
        }

        // Placeholder for manual search connection in the next phase
        binding.cardManualSearch.setOnClickListener {
            Log.d(TAG, "Manual search clicked (placeholder - to be connected in next phase)")
        }
    }

    private fun observeViewModel() {
        viewModel.matchResult.observe(this) { matchResult ->
            val rawText = matchResult.extracted.rawText.ifBlank { matchResult.extracted.name }
            binding.tvRecognizedText.text = getString(R.string.confirm_location_recognized_text_format, rawText)

            val candidateCount = matchResult.candidates.size
            binding.tvCandidatesGuide.text = getString(R.string.confirm_location_candidates_guide_format, candidateCount)

            adapter.updateData(matchResult.candidates, viewModel.selectedCandidateId.value, viewModel.roomRegion.value)
        }

        viewModel.selectedCandidateId.observe(this) { selectedId ->
            val candidates = viewModel.matchResult.value?.candidates ?: emptyList()
            adapter.updateData(candidates, selectedId, viewModel.roomRegion.value)
            val allOutside = viewModel.areAllCandidatesOutsideRegion.value == true
            binding.btnSelectThisLocation.isEnabled = (selectedId != null && !allOutside)
        }

        viewModel.areAllCandidatesOutsideRegion.observe(this) { allOutside ->
            if (allOutside) {
                binding.tvAllCandidatesOutsideNotice.visibility = View.VISIBLE
                binding.btnSelectThisLocation.isEnabled = false
            } else {
                binding.tvAllCandidatesOutsideNotice.visibility = View.GONE
                val selectedId = viewModel.selectedCandidateId.value
                binding.btnSelectThisLocation.isEnabled = (selectedId != null)
            }
        }

        viewModel.resultEvent.observe(this) { event ->
            event.getContentIfNotHandled()?.let { result ->
                val resultIntent = Intent().apply {
                    when (result) {
                        is ConfirmLocationResult.Selected -> {
                            putExtra(EXTRA_POSITION, result.position)
                            putExtra(EXTRA_UPDATED_MATCH_RESULT, result.updatedMatchResult)
                        }
                        is ConfirmLocationResult.Excluded -> {
                            putExtra(EXTRA_POSITION, result.position)
                            putExtra(EXTRA_UPDATED_MATCH_RESULT, result.updatedMatchResult)
                        }
                    }
                }
                setResult(RESULT_OK, resultIntent)
                finish()
            }
        }
    }

    companion object {
        private const val TAG = "ConfirmLocationActivity"
        private const val EXTRA_MATCH_RESULT = "EXTRA_MATCH_RESULT"
        private const val EXTRA_ROOM_REGION = "EXTRA_ROOM_REGION"
        private const val EXTRA_POSITION = "EXTRA_POSITION"
        private const val EXTRA_UPDATED_MATCH_RESULT = "EXTRA_UPDATED_MATCH_RESULT"

        fun newIntent(
            context: Context,
            matchResult: MatchResult,
            roomRegion: String,
            position: Int,
        ): Intent {
            return Intent(context, ConfirmLocationActivity::class.java).apply {
                putExtra(EXTRA_MATCH_RESULT, matchResult)
                putExtra(EXTRA_ROOM_REGION, roomRegion)
                putExtra(EXTRA_POSITION, position)
            }
        }

        fun getPosition(intent: Intent): Int {
            return intent.getIntExtra(EXTRA_POSITION, -1)
        }

        fun getMatchResult(intent: Intent): MatchResult? {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getSerializableExtra(EXTRA_UPDATED_MATCH_RESULT, MatchResult::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getSerializableExtra(EXTRA_UPDATED_MATCH_RESULT) as? MatchResult
            }
        }
    }
}

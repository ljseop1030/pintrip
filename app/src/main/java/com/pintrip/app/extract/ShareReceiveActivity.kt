package com.pintrip.app.extract

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.pintrip.app.R
import com.pintrip.app.databinding.ActivityShareReceiveBinding

class ShareReceiveActivity : AppCompatActivity() {

    private lateinit var binding: ActivityShareReceiveBinding
    private val viewModel: ShareReceiveViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityShareReceiveBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        setupRoomSelection()
        observeViewModel()

        viewModel.processIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        viewModel.processNewIntent(intent)
    }

    private fun setupRoomSelection() {
        binding.tvRoomName.text = getString(R.string.share_receive_room_name_format, viewModel.selectedRoomId)
    }

    private fun observeViewModel() {
        viewModel.shareInput.observe(this) { input ->
            Log.d(TAG, "Activity observed ShareInput: $input")
            bindShareInput(input)
        }

        viewModel.stepsState.observe(this) { stepsState ->
            bindStepsState(stepsState)
        }
    }

    private fun bindShareInput(input: ShareInput) {
        when (input) {
            is ShareInput.Screenshots -> {
                binding.ivItemIcon.setImageResource(R.drawable.ic_image)
                binding.tvItemTitle.text = getString(R.string.share_receive_screenshot_count_format, input.uris.size)
                binding.tvItemSubtitle.text = getString(R.string.share_receive_source_gallery)
                binding.tvStep1Text.text = getString(R.string.share_receive_step1_image_completed)
            }

            is ShareInput.LinkText -> {
                binding.ivItemIcon.setImageResource(R.drawable.ic_link)
                binding.tvItemTitle.text = getString(R.string.share_receive_link_text_label)
                binding.tvItemSubtitle.text = getString(R.string.share_receive_source_text)
                binding.tvStep1Text.text = getString(R.string.share_receive_step1_text_completed)
            }

            ShareInput.Empty -> {
                binding.ivItemIcon.setImageResource(R.drawable.ic_image)
                binding.tvItemTitle.text = getString(R.string.share_receive_empty_label)
                binding.tvItemSubtitle.text = getString(R.string.share_receive_source_none)
                binding.tvStep1Text.text = getString(R.string.share_receive_step1_empty)
            }
        }
    }

    private fun bindStepsState(stepsState: ProgressStepsState) {
        bindStepItem(
            status = stepsState.step1Status,
            ivStatus = binding.ivStep1Status,
            pbLoading = binding.pbStep1Loading,
        )

        bindStepItem(
            status = stepsState.step2Status,
            ivStatus = binding.ivStep2Status,
            pbLoading = binding.pbStep2Loading
        )
        binding.tvStep2Text.text = when (stepsState.step2Status) {
            StepStatus.COMPLETED -> getString(R.string.share_receive_step2_completed)
            StepStatus.IN_PROGRESS -> getString(R.string.share_receive_step2_in_progress)
            StepStatus.PENDING, StepStatus.ERROR -> getString(R.string.share_receive_step2_pending)
        }

        bindStepItem(
            status = stepsState.step3Status,
            ivStatus = binding.ivStep3Status,
            pbLoading = binding.pbStep3Loading
        )
        binding.tvStep3Text.text = when (stepsState.step3Status) {
            StepStatus.COMPLETED -> {
                val count = stepsState.matchedCount ?: 0
                getString(R.string.share_receive_step3_completed_format, count)
            }

            StepStatus.IN_PROGRESS -> getString(R.string.share_receive_step3_in_progress)
            StepStatus.PENDING, StepStatus.ERROR -> getString(R.string.share_receive_step3_pending)
        }
    }

    private fun bindStepItem(
        status: StepStatus,
        ivStatus: ImageView,
        pbLoading: ProgressBar
    ) {
        when (status) {
            StepStatus.COMPLETED -> {
                ivStatus.visibility = View.VISIBLE
                pbLoading.visibility = View.GONE
                ivStatus.setImageResource(R.drawable.ic_check_circle)
            }

            StepStatus.IN_PROGRESS -> {
                ivStatus.visibility = View.GONE
                pbLoading.visibility = View.VISIBLE
            }

            StepStatus.PENDING, StepStatus.ERROR -> {
                ivStatus.visibility = View.VISIBLE
                pbLoading.visibility = View.GONE
                ivStatus.setImageResource(R.drawable.ic_pending_circle)
            }
        }
    }

    companion object {
        private const val TAG = "SHARETEST"
    }
}

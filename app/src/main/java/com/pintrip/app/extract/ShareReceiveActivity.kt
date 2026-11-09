package com.pintrip.app.extract

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.Toast
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
        setupListeners()
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

    private fun setupListeners() {
        binding.btnCheckPlaces.setOnClickListener {
            viewModel.onCheckPlacesClicked()
        }
    }

    private fun observeViewModel() {
        viewModel.shareInput.observe(this) { input ->
            Log.d(TAG, "Activity observed ShareInput: $input")
            bindShareInput(input)
        }

        viewModel.stepsState.observe(this) { stepsState ->
            bindStepsState(stepsState)
        }

        viewModel.noticeEvent.observe(this) { event ->
            event.getContentIfNotHandled()?.let { notice ->
                handleNoticeEvent(notice)
            }
        }

        viewModel.navigateToExtract.observe(this) { event ->
            event.getContentIfNotHandled()?.let { args ->
                Log.d(TAG, "Navigating to next step (Extract -> Match -> SelectPlaces) with roomId=${args.roomId}, shareInput=${args.shareInput}")
                startExtractStep(args.roomId, args.shareInput)
            }
        }

        viewModel.navigateToSelectPlaces.observe(this) { event ->
            event.getContentIfNotHandled()?.let { args ->
                Log.d(TAG, "Navigating to SelectPlaces with roomId=${args.roomId}, matchResultsCount=${args.matchResults.size}")
                // TODO: Navigate to SelectPlacesActivity with args.roomId and args.matchResults
            }
        }
    }

    private fun startExtractStep(roomId: String, shareInput: ShareInput) {
        Log.d(TAG, "Connection point triggered: roomId=$roomId, shareInput=$shareInput")
        viewModel.startExtractStep(applicationContext, roomId, shareInput)
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

    private fun bindShareInput(input: ShareInput) {
        val isValidInput = when (input) {
            is ShareInput.Screenshots -> true
            is ShareInput.LinkText -> input.url != null
            ShareInput.Empty -> false
        }

        if (!isValidInput) {
            binding.tvHeaderTitle.text = getString(R.string.share_receive_title_error)
            binding.btnCheckPlaces.isEnabled = false
        } else {
            val isExtracting = (viewModel.stepsState.value?.step2Status == StepStatus.IN_PROGRESS)
            binding.tvHeaderTitle.text = if (isExtracting) {
                getString(R.string.share_receive_title_in_progress)
            } else {
                getString(R.string.share_receive_title_initial)
            }
            binding.btnCheckPlaces.isEnabled = !isExtracting
        }

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
                if (input.url != null) {
                    binding.tvStep1Text.text = getString(R.string.share_receive_step1_text_completed)
                } else {
                    binding.tvStep1Text.text = getString(R.string.share_receive_step1_empty)
                }
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
        val input = viewModel.shareInput.value
        val isValidInput = when (input) {
            is ShareInput.Screenshots -> true
            is ShareInput.LinkText -> input.url != null
            else -> false
        }

        if (isValidInput) {
            val isWorking = stepsState.step2Status == StepStatus.IN_PROGRESS ||
                    stepsState.step3Status == StepStatus.IN_PROGRESS
            binding.tvHeaderTitle.text = if (isWorking) {
                getString(R.string.share_receive_title_in_progress)
            } else {
                getString(R.string.share_receive_title_initial)
            }
            binding.btnCheckPlaces.isEnabled = !isWorking
        }
        bindStepItem(
            status = stepsState.step1Status,
            ivStatus = binding.ivStep1Status,
            pbLoading = binding.pbStep1Loading,
        )

        bindStepItem(
            status = stepsState.step2Status,
            ivStatus = binding.ivStep2Status,
            pbLoading = binding.pbStep2Loading,
        )
        binding.tvStep2Text.text = when (stepsState.step2Status) {
            StepStatus.COMPLETED -> getString(R.string.share_receive_step2_completed)
            StepStatus.IN_PROGRESS -> getString(R.string.share_receive_step2_in_progress)
            StepStatus.PENDING, StepStatus.ERROR -> getString(R.string.share_receive_step2_pending)
        }

        bindStepItem(
            status = stepsState.step3Status,
            ivStatus = binding.ivStep3Status,
            pbLoading = binding.pbStep3Loading,
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
        pbLoading: ProgressBar,
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

            StepStatus.PENDING -> {
                ivStatus.visibility = View.VISIBLE
                pbLoading.visibility = View.GONE
                ivStatus.setImageResource(R.drawable.ic_pending_circle)
            }

            StepStatus.ERROR -> {
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

package com.pintrip.app.extract

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.pintrip.app.R
import com.pintrip.app.databinding.ActivityShareReceiveBinding

class ShareReceiveActivity : AppCompatActivity() {

    private lateinit var binding: ActivityShareReceiveBinding
    private val viewModel: ShareReceiveViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return ShareReceiveViewModel(
                    isOnline = { isNetworkAvailable(applicationContext) }
                ) as T
            }
        }
    }

    private val pickMultiplePhotosLauncher = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(ShareReceiveViewModel.MAX_SCREENSHOTS),
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.retryWithNewScreenshots(applicationContext, uris)
        }
    }

    private fun isNetworkAvailable(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

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

        setupListeners()
        observeViewModel()
        setupFragmentResultListeners()

        viewModel.fetchRooms()
        viewModel.processIntent(intent)
    }

    private fun setupFragmentResultListeners() {
        supportFragmentManager.setFragmentResultListener(
            ManualSearchBottomSheetFragment.REQUEST_KEY_MANUAL_SEARCH,
            this,
        ) { _, bundle ->
            val candidate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                bundle.getSerializable(ManualSearchBottomSheetFragment.EXTRA_PICKED_CANDIDATE, MatchCandidate::class.java)
            } else {
                @Suppress("DEPRECATION")
                bundle.getSerializable(ManualSearchBottomSheetFragment.EXTRA_PICKED_CANDIDATE) as? MatchCandidate
            }
            val manualQuery = bundle.getString(ManualSearchBottomSheetFragment.EXTRA_MANUAL_QUERY).orEmpty()

            if (candidate != null) {
                val input = viewModel.shareInput.value
                val (sourceType, sourceUrl) = when (input) {
                    is ShareInput.Screenshots -> "screenshot" to null
                    is ShareInput.LinkText -> {
                        if (input.url != null) "link" to input.url else "text" to null
                    }
                    else -> "text" to null
                }

                val manualMatchResult = MatchResult(
                    localId = "manual_p0",
                    extracted = com.pintrip.app.data.model.GeminiPlace(name = manualQuery),
                    status = MatchResult.STATUS_MATCHED,
                    candidates = listOf(candidate),
                    selectedPlaceId = candidate.kakaoPlaceId,
                    manualQuery = manualQuery,
                    regionMismatch = false,
                    checked = true,
                    sourceType = sourceType,
                    sourceUrl = sourceUrl,
                )

                val roomId = viewModel.selectedRoom.value?.id.orEmpty()
                if (roomId.isNotBlank()) {
                    val intent = SelectPlacesActivity.newIntent(this, roomId, listOf(manualMatchResult))
                    startActivity(intent)
                    finish()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        viewModel.processNewIntent(intent)
    }

    private fun setupListeners() {
        binding.btnCheckPlaces.setOnClickListener {
            viewModel.onCheckPlacesClicked()
        }
        
        binding.cardRoomSelect.setOnClickListener {
            val error = viewModel.roomLoadError.value
            if (error != null) {
                // Retry loading rooms on error
                viewModel.fetchRooms(forceRefresh = true)
                return@setOnClickListener
            }

            val rooms = viewModel.rooms.value
            if (rooms.isNullOrEmpty() || rooms.size == 1) return@setOnClickListener

            val roomNames = rooms.map { it.name }.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle(R.string.share_receive_room_label)
                .setItems(roomNames) { _, which ->
                    viewModel.selectRoom(rooms[which])
                }
                .show()
        }

        binding.btnSelectOtherPhotos.setOnClickListener {
            pickMultiplePhotosLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
            )
        }

        binding.btnFailureManualSearch.setOnClickListener {
            val roomRegion = viewModel.selectedRoom.value?.region.orEmpty()
            val sheet = ManualSearchBottomSheetFragment.newInstance(roomRegion = roomRegion)
            sheet.show(supportFragmentManager, ManualSearchBottomSheetFragment.TAG)
        }

        binding.btnRetryExtraction.setOnClickListener {
            viewModel.retryCurrentExtraction(applicationContext)
        }
    }

    private fun observeViewModel() {
        viewModel.shareInput.observe(this) { input ->
            Log.d(TAG, "Activity observed ShareInput: $input")
            bindShareInput(input)
        }

        viewModel.selectedRoom.observe(this) {
            updateRoomSelectUi()
        }

        viewModel.roomLoadError.observe(this) {
            updateRoomSelectUi()
        }
        
        viewModel.rooms.observe(this) {
            updateRoomSelectUi()
        }

        viewModel.stepsState.observe(this) { stepsState ->
            bindStepsState(stepsState)
        }

        viewModel.extractFailureState.observe(this) { failureState ->
            if (failureState == null) {
                binding.cardExtractFailure.visibility = View.GONE
            } else {
                binding.cardExtractFailure.visibility = View.VISIBLE
                when (failureState) {
                    is ExtractFailureState.NoPlaces -> {
                        binding.tvFailureMessage.text = getString(failureState.messageResId)
                        binding.btnSelectOtherPhotos.visibility = View.VISIBLE
                        binding.btnFailureManualSearch.visibility = View.VISIBLE
                        binding.btnRetryExtraction.visibility = View.GONE
                    }
                    is ExtractFailureState.Error -> {
                        binding.tvFailureMessage.text = getString(R.string.share_receive_error_occurred)
                        binding.btnSelectOtherPhotos.visibility = View.GONE
                        binding.btnFailureManualSearch.visibility = View.GONE
                        binding.btnRetryExtraction.visibility = View.VISIBLE
                    }
                }
            }
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
                Log.d(TAG, "Navigating to SelectPlacesActivity with roomId=${args.roomId}, matchResultsCount=${args.matchResults.size}")
                val intent = SelectPlacesActivity.newIntent(this, args.roomId, args.matchResults)
                startActivity(intent)
                finish()
            }
        }
    }

    private fun updateRoomSelectUi() {
        val selectedRoom = viewModel.selectedRoom.value
        val rooms = viewModel.rooms.value
        val errorResId = viewModel.roomLoadError.value

        when {
            selectedRoom != null -> {
                binding.tvRoomName.text = selectedRoom.name
                binding.ivRoomSelectArrow.visibility = View.GONE
            }
            errorResId != null -> {
                binding.tvRoomName.text = getString(errorResId).split("\n")[0]
                binding.ivRoomSelectArrow.visibility = View.GONE
            }
            !rooms.isNullOrEmpty() -> {
                if (rooms.size == 1) {
                    binding.tvRoomName.text = rooms.first().name
                    binding.ivRoomSelectArrow.visibility = View.GONE
                } else {
                    binding.tvRoomName.text = getString(R.string.share_receive_room_label)
                    binding.ivRoomSelectArrow.visibility = View.VISIBLE
                }
            }
            else -> {
                binding.tvRoomName.text = getString(R.string.share_receive_room_label)
                binding.ivRoomSelectArrow.visibility = View.GONE
            }
        }

        updateCheckButtonState()
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

    private fun updateCheckButtonState() {
        val input = viewModel.shareInput.value
        val isValidInput = when (input) {
            is ShareInput.Screenshots -> true
            is ShareInput.LinkText -> input.text.isNotBlank()
            else -> false
        }
        val isWorking = (viewModel.stepsState.value?.step2Status == StepStatus.IN_PROGRESS || viewModel.stepsState.value?.step3Status == StepStatus.IN_PROGRESS)
        val hasRoomSelected = (viewModel.selectedRoom.value != null)
        binding.btnCheckPlaces.isEnabled = isValidInput && !isWorking && hasRoomSelected
    }

    private fun bindShareInput(input: ShareInput) {
        val isValidInput = when (input) {
            is ShareInput.Screenshots -> true
            is ShareInput.LinkText -> input.text.isNotBlank()
            ShareInput.Empty -> false
        }

        if (!isValidInput) {
            binding.tvHeaderTitle.text = getString(R.string.share_receive_title_error)
        } else {
            val isExtracting = (viewModel.stepsState.value?.step2Status == StepStatus.IN_PROGRESS)
            binding.tvHeaderTitle.text = if (isExtracting) {
                getString(R.string.share_receive_title_in_progress)
            } else {
                getString(R.string.share_receive_title_initial)
            }
        }
        
        updateCheckButtonState()

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
                if (input.text.isNotBlank()) {
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
            is ShareInput.LinkText -> input.text.isNotBlank()
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
        }
        
        updateCheckButtonState()
        
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

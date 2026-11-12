package com.pintrip.app.room

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.util.Pair
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.firebase.Timestamp
import com.pintrip.app.R
import com.pintrip.app.databinding.ActivityCreateRoomBinding
import com.pintrip.app.place.PlaceListActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 여행방 생성 화면 (Figma S04).
 */
class CreateRoomActivity : AppCompatActivity() {

    companion object {
        private const val DATE_PICKER_TAG = "DATE_RANGE_PICKER"
    }

    private lateinit var binding: ActivityCreateRoomBinding
    private val viewModel: CreateRoomViewModel by viewModels()
    private val dateFormat = SimpleDateFormat("MM.dd (E)", Locale.KOREA).apply {
        timeZone = java.util.TimeZone.getTimeZone("UTC")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        binding = ActivityCreateRoomBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        setupListeners()
        setupBackPressHandler()
        restoreDatePickerListenerIfNeeded()
        observeViewModel()
    }

    private fun setupListeners() {
        binding.ivBack.setOnClickListener {
            handleBackPress()
        }

        binding.layoutDateSelection.setOnClickListener {
            showDateRangePicker()
        }

        binding.btnClearDates.setOnClickListener {
            viewModel.clearDates()
        }

        binding.chipGroupIcons.setOnCheckedStateChangeListener { _, checkedIds ->
            val icon = when (checkedIds.firstOrNull()) {
                R.id.chipFood -> "food"
                R.id.chipNature -> "nature"
                R.id.chipCity -> "city"
                R.id.chipHealing -> "healing"
                else -> "sea"
            }
            viewModel.setIcon(icon)
        }

        binding.btnCreate.setOnClickListener {
            val name = binding.etRoomName.text?.toString().orEmpty()
            val region = binding.etRegion.text?.toString().orEmpty()
            viewModel.createRoom(name, region)
        }
    }

    private fun setupBackPressHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBackPress()
            }
        })
    }

    private fun handleBackPress() {
        if (viewModel.isLoading.value == true) {
            Toast.makeText(this, R.string.notice_creating_room_in_progress, Toast.LENGTH_SHORT).show()
        } else {
            finish()
        }
    }

    private fun showDateRangePicker() {
        val existingPicker = supportFragmentManager.findFragmentByTag(DATE_PICKER_TAG)
        if (existingPicker != null) return

        val startVal = viewModel.selectedStartDate.value
        val endVal = viewModel.selectedEndDate.value

        val builder = MaterialDatePicker.Builder.dateRangePicker()
            .setTitleText(R.string.label_travel_period)

        if (startVal != null && endVal != null) {
            builder.setSelection(Pair(startVal.toDate().time, endVal.toDate().time))
        }

        val datePicker = builder.build()
        attachDatePickerListener(datePicker)
        datePicker.show(supportFragmentManager, DATE_PICKER_TAG)
    }

    private fun restoreDatePickerListenerIfNeeded() {
        @Suppress("UNCHECKED_CAST")
        val existingPicker = supportFragmentManager.findFragmentByTag(DATE_PICKER_TAG)
            as? MaterialDatePicker<Pair<Long, Long>>
        if (existingPicker != null) {
            attachDatePickerListener(existingPicker)
        }
    }

    private fun attachDatePickerListener(datePicker: MaterialDatePicker<Pair<Long, Long>>) {
        datePicker.addOnPositiveButtonClickListener { selection ->
            if (selection?.first != null && selection.second != null) {
                val startDate = Timestamp(Date(selection.first))
                val endDate = Timestamp(Date(selection.second))
                viewModel.setDates(startDate, endDate)
            }
        }
    }

    private fun observeViewModel() {
        viewModel.selectedStartDate.observe(this) { start ->
            updateDateDisplay(start, viewModel.selectedEndDate.value)
        }

        viewModel.selectedEndDate.observe(this) { end ->
            updateDateDisplay(viewModel.selectedStartDate.value, end)
        }

        viewModel.isLoading.observe(this) { isLoading ->
            binding.btnCreate.isEnabled = !isLoading
            binding.btnCreate.text = if (isLoading) {
                getString(R.string.btn_creating_room_in_progress)
            } else {
                getString(R.string.btn_create_room_action)
            }
        }

        viewModel.errorMessageEvent.observe(this) { event ->
            event.getContentIfNotHandled()?.let { message ->
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            }
        }

        viewModel.createSuccessEvent.observe(this) { event ->
            event.getContentIfNotHandled()?.let { room ->
                Toast.makeText(this, R.string.status_room_created, Toast.LENGTH_SHORT).show()
                val resultIntent = Intent().apply {
                    putExtra(PlaceListActivity.EXTRA_ROOM_ID, room.id)
                }
                setResult(RESULT_OK, resultIntent)
                finish()
            }
        }
    }

    private fun updateDateDisplay(start: Timestamp?, end: Timestamp?) {
        if (start != null && end != null) {
            val startStr = dateFormat.format(start.toDate())
            val endStr = dateFormat.format(end.toDate())
            binding.tvDateDisplay.text = getString(R.string.date_range_format, startStr, endStr)
            binding.btnClearDates.isVisible = true
        } else {
            binding.tvDateDisplay.text = getString(R.string.date_to_be_determined)
            binding.btnClearDates.isVisible = false
        }
    }
}

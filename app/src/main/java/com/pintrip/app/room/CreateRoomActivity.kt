package com.pintrip.app.room

import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
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

    private lateinit var binding: ActivityCreateRoomBinding
    private val viewModel: CreateRoomViewModel by viewModels()
    private val dateFormat = SimpleDateFormat("MM.dd (E)", Locale.KOREA)

    private val presetRegions = listOf("부산", "서울", "제주", "강릉", "경주", "여수")

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

        setupRegionDropdown()
        setupListeners()
        observeViewModel()
    }

    private fun setupRegionDropdown() {
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, presetRegions)
        binding.actvRegion.setAdapter(adapter)
    }

    private fun setupListeners() {
        binding.ivBack.setOnClickListener {
            finish()
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
            val region = binding.actvRegion.text?.toString().orEmpty()
            viewModel.createRoom(name, region)
        }
    }

    private fun showDateRangePicker() {
        val datePicker = MaterialDatePicker.Builder.dateRangePicker()
            .setTitleText(R.string.label_travel_period)
            .setSelection(
                Pair(
                    MaterialDatePicker.todayInUtcMilliseconds(),
                    MaterialDatePicker.todayInUtcMilliseconds() + (2 * 24 * 60 * 60 * 1000L),
                ),
            )
            .build()

        datePicker.addOnPositiveButtonClickListener { selection ->
            if (selection?.first != null && selection.second != null) {
                val startDate = Timestamp(Date(selection.first))
                val endDate = Timestamp(Date(selection.second))
                viewModel.setDates(startDate, endDate)
            }
        }

        datePicker.show(supportFragmentManager, "DATE_RANGE_PICKER")
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
            binding.btnCreate.text = if (isLoading) "생성 중…" else getString(R.string.btn_create_room_action)
        }

        viewModel.errorMessageEvent.observe(this) { event ->
            event.getContentIfNotHandled()?.let { message ->
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            }
        }

        viewModel.createSuccessEvent.observe(this) { event ->
            event.getContentIfNotHandled()?.let { room ->
                Toast.makeText(this, "여행방이 생성되었습니다.", Toast.LENGTH_SHORT).show()
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

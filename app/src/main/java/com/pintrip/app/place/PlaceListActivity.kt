package com.pintrip.app.place

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import com.pintrip.app.R
import com.pintrip.app.databinding.ActivityPlaceListBinding

class PlaceListActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ROOM_ID = "extra_room_id"

        fun newIntent(context: Context, roomId: String): Intent {
            return Intent(context, PlaceListActivity::class.java).apply {
                putExtra(EXTRA_ROOM_ID, roomId)
            }
        }
    }

    private lateinit var binding: ActivityPlaceListBinding
    private val viewModel: PlaceListViewModel by viewModels()
    private lateinit var placeAdapter: PlaceAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val roomId = intent.getStringExtra(EXTRA_ROOM_ID)
        if (roomId.isNullOrBlank()) {
            Toast.makeText(this, R.string.place_list_error_no_room_id, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        enableEdgeToEdge()
        binding = ActivityPlaceListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        setupToolbar()
        setupRecyclerView()
        setupListeners()
        observeViewModel()

        viewModel.loadPlaces(roomId)
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
    }

    private fun setupRecyclerView() {
        placeAdapter = PlaceAdapter { place ->
            openKakaoPlaceUrl(place.kakaoPlaceUrl)
        }
        binding.rvPlaces.apply {
            layoutManager = LinearLayoutManager(this@PlaceListActivity)
            adapter = placeAdapter
        }
    }

    private fun setupListeners() {
        binding.btnRetry.setOnClickListener {
            viewModel.retry()
        }
    }

    private fun observeViewModel() {
        viewModel.uiState.observe(this) { state ->
            when (state) {
                is PlaceListUiState.Loading -> {
                    binding.pbLoading.isVisible = true
                    binding.rvPlaces.isVisible = false
                    binding.tvEmpty.isVisible = false
                    binding.layoutError.isVisible = false
                }
                is PlaceListUiState.Empty -> {
                    binding.pbLoading.isVisible = false
                    binding.rvPlaces.isVisible = false
                    binding.tvEmpty.isVisible = true
                    binding.layoutError.isVisible = false
                }
                is PlaceListUiState.Success -> {
                    binding.pbLoading.isVisible = false
                    binding.rvPlaces.isVisible = true
                    binding.tvEmpty.isVisible = false
                    binding.layoutError.isVisible = false
                    placeAdapter.submitList(state.places)
                }
                is PlaceListUiState.Error -> {
                    binding.pbLoading.isVisible = false
                    binding.rvPlaces.isVisible = false
                    binding.tvEmpty.isVisible = false
                    binding.layoutError.isVisible = true
                    binding.tvErrorMessage.text = state.message ?: getString(R.string.place_list_error_default)
                }
            }
        }
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
}

package com.pintrip.app.extract

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.pintrip.app.R
import com.pintrip.app.data.remote.KakaoLocalApiClient
import com.pintrip.app.databinding.FragmentManualSearchBottomSheetBinding

class ManualSearchBottomSheetFragment : BottomSheetDialogFragment() {

    var clientFactory: () -> KakaoLocalApiClient = { KakaoLocalApiClient() }

    private var _binding: FragmentManualSearchBottomSheetBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: ItemConfirmCandidateAdapter
    private val viewModel: ManualSearchViewModel by lazy {
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return ManualSearchViewModel(clientFactory = clientFactory) as T
            }
        }
        ViewModelProvider(this, factory)[ManualSearchViewModel::class.java]
    }

    private var isTextUpdating = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentManualSearchBottomSheetBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val roomRegion = arguments?.getString(ARG_ROOM_REGION).orEmpty()
        val initialQuery = arguments?.getString(ARG_INITIAL_QUERY).orEmpty()

        viewModel.init(roomRegionInput = roomRegion, initialQueryInput = initialQuery)

        binding.tvRoomRegionNotice.text = getString(R.string.manual_search_room_region_notice, roomRegion)

        adapter = ItemConfirmCandidateAdapter(
            onCandidateSelected = { pickedCandidate ->
                val currentTypedQuery = viewModel.currentQuery.value?.trim().orEmpty().ifBlank { pickedCandidate.name }
                val targetIndex = arguments?.getInt(ARG_TARGET_INDEX, -1) ?: -1
                val resultBundle = bundleOf(
                    EXTRA_PICKED_CANDIDATE to pickedCandidate,
                    EXTRA_MANUAL_QUERY to currentTypedQuery,
                    EXTRA_TARGET_INDEX to targetIndex,
                )
                parentFragmentManager.setFragmentResult(REQUEST_KEY_MANUAL_SEARCH, resultBundle)
                dismiss()
            },
        )

        binding.rvSearchResults.layoutManager = LinearLayoutManager(requireContext())
        binding.rvSearchResults.adapter = adapter

        setupTextChangeListener()
        observeViewModel(roomRegion)
    }

    private fun setupTextChangeListener() {
        binding.etSearchQuery.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!isTextUpdating) {
                    viewModel.onQueryChanged(s?.toString().orEmpty())
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun observeViewModel(roomRegion: String) {
        viewModel.currentQuery.observe(viewLifecycleOwner) { query ->
            if (binding.etSearchQuery.text?.toString() != query) {
                isTextUpdating = true
                binding.etSearchQuery.setText(query)
                binding.etSearchQuery.setSelection(query.length)
                isTextUpdating = false
            }
        }

        viewModel.uiState.observe(viewLifecycleOwner) { state ->
            when (state) {
                is ManualSearchUiState.Idle -> {
                    binding.pbSearchLoading.visibility = View.GONE
                    binding.tvEmptyNotice.visibility = View.GONE
                    binding.rvSearchResults.visibility = View.GONE
                    adapter.updateData(emptyList(), null, roomRegion)
                }

                is ManualSearchUiState.Loading -> {
                    binding.pbSearchLoading.visibility = View.VISIBLE
                    binding.tvEmptyNotice.visibility = View.GONE
                    binding.rvSearchResults.visibility = View.GONE
                }

                is ManualSearchUiState.Success -> {
                    binding.pbSearchLoading.visibility = View.GONE
                    binding.tvEmptyNotice.visibility = View.GONE
                    binding.rvSearchResults.visibility = View.VISIBLE
                    adapter.updateData(state.candidates, null, roomRegion)
                }

                is ManualSearchUiState.Empty -> {
                    binding.pbSearchLoading.visibility = View.GONE
                    binding.tvEmptyNotice.text = getString(R.string.manual_search_empty_result)
                    binding.tvEmptyNotice.visibility = View.VISIBLE
                    binding.rvSearchResults.visibility = View.GONE
                    adapter.updateData(emptyList(), null, roomRegion)
                }

                is ManualSearchUiState.Error -> {
                    binding.pbSearchLoading.visibility = View.GONE
                    binding.tvEmptyNotice.text = getString(state.messageResId)
                    binding.tvEmptyNotice.visibility = View.VISIBLE
                    binding.rvSearchResults.visibility = View.GONE
                    adapter.updateData(emptyList(), null, roomRegion)
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "ManualSearchBottomSheetFragment"
        const val REQUEST_KEY_MANUAL_SEARCH = "REQUEST_KEY_MANUAL_SEARCH"
        const val ARG_ROOM_REGION = "ARG_ROOM_REGION"
        const val ARG_INITIAL_QUERY = "ARG_INITIAL_QUERY"
        const val ARG_TARGET_INDEX = "ARG_TARGET_INDEX"
        const val EXTRA_PICKED_CANDIDATE = "EXTRA_PICKED_CANDIDATE"
        const val EXTRA_MANUAL_QUERY = "EXTRA_MANUAL_QUERY"
        const val EXTRA_TARGET_INDEX = "EXTRA_TARGET_INDEX"

        fun newInstance(
            roomRegion: String,
            initialQuery: String? = null,
            targetIndex: Int = -1,
        ): ManualSearchBottomSheetFragment {
            return ManualSearchBottomSheetFragment().apply {
                arguments = bundleOf(
                    ARG_ROOM_REGION to roomRegion,
                    ARG_INITIAL_QUERY to initialQuery,
                    ARG_TARGET_INDEX to targetIndex,
                )
            }
        }
    }
}

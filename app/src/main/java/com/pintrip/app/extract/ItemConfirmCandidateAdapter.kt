package com.pintrip.app.extract

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.pintrip.app.R
import com.pintrip.app.databinding.ItemConfirmCandidateBinding

class ItemConfirmCandidateAdapter(
    private var candidates: List<MatchCandidate> = emptyList(),
    private var selectedCandidateId: String? = null,
    private val onCandidateSelected: (candidate: MatchCandidate) -> Unit = {},
) : RecyclerView.Adapter<ItemConfirmCandidateAdapter.ViewHolder>() {

    fun updateData(newCandidates: List<MatchCandidate>, newSelectedId: String?) {
        candidates = newCandidates
        selectedCandidateId = newSelectedId
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemConfirmCandidateBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false,
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(candidates[position])
    }

    override fun getItemCount(): Int = candidates.size

    inner class ViewHolder(private val binding: ItemConfirmCandidateBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(candidate: MatchCandidate) {
            val context = binding.root.context
            val isSelected = (candidate.kakaoPlaceId == selectedCandidateId)

            binding.tvCandidateName.text = candidate.name
            binding.tvCandidateAddress.text = candidate.address.ifBlank { context.getString(R.string.select_places_item_not_found_subtitle) }

            binding.rbCandidateSelect.isChecked = isSelected

            if (isSelected) {
                binding.cardCandidateContainer.setCardBackgroundColor(ContextCompat.getColor(context, R.color.item_check_bg))
                binding.cardCandidateContainer.strokeColor = ContextCompat.getColor(context, R.color.item_check_border)
            } else {
                binding.cardCandidateContainer.setCardBackgroundColor(ContextCompat.getColor(context, R.color.card_background))
                binding.cardCandidateContainer.strokeColor = ContextCompat.getColor(context, R.color.card_stroke)
            }

            binding.cardCandidateContainer.setOnClickListener {
                onCandidateSelected(candidate)
            }
        }
    }
}

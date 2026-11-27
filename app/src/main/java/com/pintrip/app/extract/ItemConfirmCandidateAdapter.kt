package com.pintrip.app.extract

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.pintrip.app.R
import com.pintrip.app.databinding.ItemConfirmCandidateBinding

class ItemConfirmCandidateAdapter(
    private var candidates: List<MatchCandidate> = emptyList(),
    private var selectedCandidateId: String? = null,
    private var roomRegion: String? = null,
    private val onCandidateSelected: (candidate: MatchCandidate) -> Unit = {},
) : RecyclerView.Adapter<ItemConfirmCandidateAdapter.ViewHolder>() {

    fun updateData(newCandidates: List<MatchCandidate>, newSelectedId: String?, newRoomRegion: String?) {
        candidates = newCandidates
        selectedCandidateId = newSelectedId
        roomRegion = newRoomRegion
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
            val isOutsideRegion = isCandidateAddressOutsideRoomRegion(
                candidateAddress = candidate.address,
                roomRegion = roomRegion,
                geminiOutsideRoomRegion = false,
            )

            binding.tvCandidateName.text = candidate.name
            binding.tvCandidateAddress.text = candidate.address.ifBlank { context.getString(R.string.select_places_item_not_found_subtitle) }

            binding.tvCandidateOutsideNotice.visibility = if (isOutsideRegion) View.VISIBLE else View.GONE
            binding.rbCandidateSelect.isEnabled = true
            binding.cardCandidateContainer.isEnabled = true
            binding.cardCandidateContainer.alpha = 1.0f

            val isSelected = (candidate.kakaoPlaceId == selectedCandidateId)
            binding.rbCandidateSelect.isChecked = isSelected

            if (isSelected) {
                binding.cardCandidateContainer.setCardBackgroundColor(ContextCompat.getColor(context, R.color.card_background))
                binding.cardCandidateContainer.strokeColor = ContextCompat.getColor(context, R.color.pintrip_blue)
                binding.cardCandidateContainer.strokeWidth = (2 * context.resources.displayMetrics.density).toInt()
            } else {
                binding.cardCandidateContainer.setCardBackgroundColor(ContextCompat.getColor(context, R.color.card_background))
                binding.cardCandidateContainer.strokeColor = ContextCompat.getColor(context, R.color.card_stroke)
                binding.cardCandidateContainer.strokeWidth = 0
            }

            binding.cardCandidateContainer.setOnClickListener {
                onCandidateSelected(candidate)
            }
        }
    }
}

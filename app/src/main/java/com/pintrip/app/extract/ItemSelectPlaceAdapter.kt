package com.pintrip.app.extract

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.pintrip.app.R
import com.pintrip.app.databinding.ItemSelectPlaceBinding

class ItemSelectPlaceAdapter(
    private var items: List<MatchResult> = emptyList(),
    private val onItemCheckChanged: (index: Int, isChecked: Boolean) -> Unit = { _, _ -> },
    private val onItemClicked: (index: Int, item: MatchResult) -> Unit = { _, _ -> },
) : RecyclerView.Adapter<ItemSelectPlaceAdapter.ViewHolder>() {

    fun updateItems(newItems: List<MatchResult>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSelectPlaceBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false,
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position], position)
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(private val binding: ItemSelectPlaceBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: MatchResult, position: Int) {
            val context = binding.root.context

            // 1. CheckBox 상태 연동
            binding.cbSelectPlace.setOnCheckedChangeListener(null)
            binding.cbSelectPlace.isChecked = item.checked
            binding.cbSelectPlace.isEnabled = (item.status != MatchResult.STATUS_NOT_FOUND)
            binding.cbSelectPlace.setOnCheckedChangeListener { _, isChecked ->
                onItemCheckChanged(position, isChecked)
            }

            // 2. 장소명
            binding.tvPlaceName.text = if (item.status == MatchResult.STATUS_MATCHED && item.selectedCandidate != null) {
                item.selectedCandidate?.name ?: item.extracted.name
            } else {
                item.extracted.name
            }

            // 3. 서브타이틀 (카테고리, 주소, 후보 정보 등)
            val subtitleText = when (item.status) {
                MatchResult.STATUS_NOT_FOUND -> {
                    binding.tvPlaceSubtitle.setTextColor(ContextCompat.getColor(context, R.color.badge_exception_text))
                    context.getString(R.string.select_places_item_not_found_subtitle)
                }

                MatchResult.STATUS_CHECK -> {
                    binding.tvPlaceSubtitle.setTextColor(ContextCompat.getColor(context, R.color.badge_check_text))
                    context.getString(R.string.select_places_item_check_subtitle, item.candidates.size)
                }

                else -> {
                    binding.tvPlaceSubtitle.setTextColor(ContextCompat.getColor(context, R.color.text_sub_gray))
                    val candidate = item.selectedCandidate
                    val category = candidate?.category ?: item.extracted.categoryHint.orEmpty()
                    val address = candidate?.address ?: item.extracted.region.orEmpty()

                    listOf(category, address).filter { it.isNotBlank() }.joinToString(" · ")
                }
            }

            val finalSubtitle = if (item.regionMismatch) {
                "$subtitleText (${context.getString(R.string.select_places_item_region_mismatch_subtitle)})"
            } else {
                subtitleText
            }
            binding.tvPlaceSubtitle.text = finalSubtitle

            // 4. 상태 뱃지 표출
            when (item.status) {
                MatchResult.STATUS_MATCHED -> {
                    binding.tvStatusBadge.text = context.getString(R.string.select_places_badge_matched)
                    binding.tvStatusBadge.backgroundTintList = ColorStateList.valueOf(
                        ContextCompat.getColor(context, R.color.badge_blue_bg),
                    )
                    binding.tvStatusBadge.setTextColor(ContextCompat.getColor(context, R.color.badge_blue_text))
                }

                MatchResult.STATUS_CHECK -> {
                    binding.tvStatusBadge.text = context.getString(R.string.select_places_badge_check)
                    binding.tvStatusBadge.backgroundTintList = ColorStateList.valueOf(
                        ContextCompat.getColor(context, R.color.badge_check_bg),
                    )
                    binding.tvStatusBadge.setTextColor(ContextCompat.getColor(context, R.color.badge_check_text))
                }

                else -> {
                    binding.tvStatusBadge.text = context.getString(R.string.select_places_badge_exception)
                    binding.tvStatusBadge.backgroundTintList = ColorStateList.valueOf(
                        ContextCompat.getColor(context, R.color.badge_exception_bg),
                    )
                    binding.tvStatusBadge.setTextColor(ContextCompat.getColor(context, R.color.badge_exception_text))
                }
            }

            // 5. 카드 테두리 및 배경 스타일
            if (item.status == MatchResult.STATUS_CHECK) {
                binding.cardContainer.setCardBackgroundColor(ContextCompat.getColor(context, R.color.item_check_bg))
                binding.cardContainer.strokeColor = ContextCompat.getColor(context, R.color.item_check_border)
            } else {
                binding.cardContainer.setCardBackgroundColor(ContextCompat.getColor(context, R.color.card_background))
                binding.cardContainer.strokeColor = ContextCompat.getColor(context, R.color.card_stroke)
            }

            binding.root.setOnClickListener {
                onItemClicked(position, item)
            }
        }
    }
}

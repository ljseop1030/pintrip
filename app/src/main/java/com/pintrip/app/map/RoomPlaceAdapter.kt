package com.pintrip.app.map

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.pintrip.app.R
import com.pintrip.app.data.model.PlaceDetail
import com.pintrip.app.databinding.ItemPlaceBinding
import com.pintrip.app.place.PlaceCategory
import com.pintrip.app.place.iconResId
import com.pintrip.app.place.labelResId

class RoomPlaceAdapter(
    private val onPlaceClick: (kakaoPlaceId: String) -> Unit,
) : ListAdapter<PlaceDetail, RoomPlaceAdapter.RoomPlaceViewHolder>(PlaceDetailDiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RoomPlaceViewHolder {
        val binding = ItemPlaceBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return RoomPlaceViewHolder(binding, onPlaceClick)
    }

    override fun onBindViewHolder(holder: RoomPlaceViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class RoomPlaceViewHolder(
        private val binding: ItemPlaceBinding,
        private val onPlaceClick: (kakaoPlaceId: String) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(detail: PlaceDetail) {
            val title = if (detail.status == PlaceDetail.STATUS_FOUND) {
                detail.name.takeUnless { it.isNullOrBlank() } ?: detail.place.searchKeyword
            } else {
                detail.place.searchKeyword
            }
            binding.tvSearchKeyword.text = title

            // 카테고리 아이콘과 "관광지 · Minji님이 추가" (캔버스 11 방 홈). 카테고리가 없으면 기타로 표시
            val context = binding.root.context
            val category = PlaceCategory.fromCode(detail.category)
            binding.ivCategoryIcon.setImageResource(category.iconResId())
            binding.tvAddedBy.text = context.getString(
                R.string.place_category_added_by_format,
                context.getString(category.labelResId()),
                detail.place.addedByNickname
            )
            binding.tvLikeCount.text = detail.place.likeCount.toString()

            binding.root.setOnClickListener {
                onPlaceClick(detail.place.kakaoPlaceId)
            }
        }
    }

    private object PlaceDetailDiffCallback : DiffUtil.ItemCallback<PlaceDetail>() {
        override fun areItemsTheSame(oldItem: PlaceDetail, newItem: PlaceDetail): Boolean {
            return oldItem.place.kakaoPlaceId == newItem.place.kakaoPlaceId
        }

        override fun areContentsTheSame(oldItem: PlaceDetail, newItem: PlaceDetail): Boolean {
            return oldItem == newItem
        }
    }
}
package com.pintrip.app.map

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.pintrip.app.R
import com.pintrip.app.data.model.PlaceDetail
import com.pintrip.app.databinding.ItemPlaceBinding

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
            binding.tvAddedBy.text = binding.root.context.getString(
                R.string.place_added_by,
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

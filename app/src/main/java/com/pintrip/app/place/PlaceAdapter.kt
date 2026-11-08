package com.pintrip.app.place

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.pintrip.app.R
import com.pintrip.app.data.model.Place
import com.pintrip.app.databinding.ItemPlaceBinding

class PlaceAdapter(
    private val onItemClick: (Place) -> Unit,
) : ListAdapter<Place, PlaceAdapter.PlaceViewHolder>(PlaceDiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlaceViewHolder {
        val binding = ItemPlaceBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return PlaceViewHolder(binding, onItemClick)
    }

    override fun onBindViewHolder(holder: PlaceViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class PlaceViewHolder(
        private val binding: ItemPlaceBinding,
        private val onItemClick: (Place) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(place: Place) {
            binding.tvSearchKeyword.text = place.searchKeyword
            binding.tvAddedBy.text = binding.root.context.getString(
                R.string.place_added_by,
                place.addedByNickname
            )
            binding.tvLikeCount.text = place.likeCount.toString()

            binding.root.setOnClickListener {
                onItemClick(place)
            }
        }
    }

    private object PlaceDiffCallback : DiffUtil.ItemCallback<Place>() {
        override fun areItemsTheSame(oldItem: Place, newItem: Place): Boolean {
            return oldItem.kakaoPlaceId == newItem.kakaoPlaceId
        }

        override fun areContentsTheSame(oldItem: Place, newItem: Place): Boolean {
            return oldItem == newItem
        }
    }
}

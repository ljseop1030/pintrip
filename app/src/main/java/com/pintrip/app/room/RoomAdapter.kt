package com.pintrip.app.room

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.pintrip.app.R
import com.pintrip.app.data.model.Room
import com.pintrip.app.databinding.ItemRoomCardBinding
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 여행방 목록을 표시하는 RecyclerView Adapter.
 */
class RoomAdapter(
    private val onRoomClick: (Room) -> Unit,
) : ListAdapter<Room, RoomAdapter.RoomViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RoomViewHolder {
        val binding = ItemRoomCardBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false,
        )
        return RoomViewHolder(binding, onRoomClick)
    }

    override fun onBindViewHolder(holder: RoomViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class RoomViewHolder(
        private val binding: ItemRoomCardBinding,
        private val onRoomClick: (Room) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {

        private val dateFormat = SimpleDateFormat("MM.dd", Locale.getDefault())

        fun bind(room: Room) {
            binding.tvRoomName.text = room.name
            binding.tvLocationBadge.text = binding.root.context.getString(
                R.string.location_count_format,
                room.placeCount,
            )

            // 일자 정보 바인딩 (null일 경우 숨김)
            if (room.startDate != null && room.endDate != null) {
                val dateStr = "${dateFormat.format(room.startDate.toDate())} – ${dateFormat.format(room.endDate.toDate())}"
                binding.tvRoomDate.text = dateStr
                binding.tvRoomDate.visibility = View.VISIBLE
            } else {
                binding.tvRoomDate.visibility = View.GONE
            }

            // 아바타 텍스트 바인딩 (owner/member 닉네임 첫 글자)
            val firstMember = room.members.values.firstOrNull()
            val avatarText = firstMember?.nickname?.take(1)?.uppercase() ?: "M"
            binding.tvMemberAvatar.text = avatarText

            binding.cardRoomItem.setOnClickListener {
                onRoomClick(room)
            }
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<Room>() {
        override fun areItemsTheSame(oldItem: Room, newItem: Room): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Room, newItem: Room): Boolean {
            return oldItem == newItem
        }
    }
}

package com.pintrip.app.room

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
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

        private val dateFormat = SimpleDateFormat("MM.dd", Locale.getDefault()).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }

        fun bind(room: Room) {
            val context = binding.root.context
            binding.tvRoomName.text = room.name
            binding.tvLocationBadge.text = context.getString(
                R.string.location_count_format,
                room.placeCount,
            )

            // 일자 정보 바인딩 (null일 경우 일정 미정 표시)
            if (room.startDate != null && room.endDate != null) {
                val dateStr = "${dateFormat.format(room.startDate.toDate())} – ${dateFormat.format(room.endDate.toDate())}"
                binding.tvRoomDate.text = dateStr
                binding.tvRoomDate.visibility = View.VISIBLE
            } else {
                binding.tvRoomDate.text = context.getString(R.string.date_to_be_determined)
                binding.tvRoomDate.visibility = View.VISIBLE
            }

            // 아이콘 종류별 리소스 매핑
            val iconRes = when (room.icon) {
                "sea" -> R.drawable.ic_room_category_sea
                "food" -> R.drawable.ic_room_category_food
                "nature" -> R.drawable.ic_room_category_nature
                "city" -> R.drawable.ic_room_category_city
                "healing" -> R.drawable.ic_room_category_healing
                else -> R.drawable.ic_room_category_sea
            }
            binding.ivRoomIcon.setImageResource(iconRes)

            // 실제 멤버 프로필 닉네임 첫 글자 및 아바타 색상 반영
            val firstMember = room.members.values.firstOrNull()
            if (firstMember != null && firstMember.nickname.isNotBlank()) {
                binding.tvMemberAvatar.text = firstMember.nickname.take(1).uppercase()
                val colorHex = firstMember.avatarColor
                if (colorHex.isNotBlank()) {
                    try {
                        val parsedColor = Color.parseColor(colorHex)
                        binding.tvMemberAvatar.backgroundTintList = ColorStateList.valueOf(parsedColor)
                    } catch (_: Exception) {
                        val defaultBlue = ContextCompat.getColor(context, R.color.pintrip_blue)
                        binding.tvMemberAvatar.backgroundTintList = ColorStateList.valueOf(defaultBlue)
                    }
                } else {
                    val defaultBlue = ContextCompat.getColor(context, R.color.pintrip_blue)
                    binding.tvMemberAvatar.backgroundTintList = ColorStateList.valueOf(defaultBlue)
                }
                binding.tvMemberAvatar.visibility = View.VISIBLE
            } else {
                binding.tvMemberAvatar.visibility = View.GONE
            }

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

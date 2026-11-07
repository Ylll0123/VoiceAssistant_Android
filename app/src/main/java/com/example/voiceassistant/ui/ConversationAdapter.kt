package com.example.voiceassistant.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.voiceassistant.R
import com.example.voiceassistant.data.Conversation
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 抽屉里的对话记录列表。
 *
 * 点一条切过去；长按删掉（还会问一次）。当前打开的那条左边有个小圆点，
 * 不用靠标题去猜「我现在在哪一段对话里」。
 */
class ConversationAdapter(
    private val onClick: (Conversation) -> Unit,
    private val onLongClick: (Conversation) -> Unit
) : RecyclerView.Adapter<ConversationAdapter.Holder>() {

    private val items = mutableListOf<Conversation>()
    private var currentId: String? = null

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_conversation, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items.getOrNull(position) ?: return
        holder.title.text = item.title
        holder.time.text = relative(item.updatedAt)
        holder.dot.visibility =
            if (item.id == currentId) View.VISIBLE else View.INVISIBLE
        holder.itemView.setOnClickListener { onClick(item) }
        holder.itemView.setOnLongClickListener {
            onLongClick(item)
            true
        }
    }

    fun submit(list: List<Conversation>, current: String?) {
        items.clear()
        items.addAll(list)
        currentId = current
        notifyDataSetChanged()
    }

    /** 「刚刚 / 12 分钟前 / 昨天 / 3 月 5 日」——够判断新旧就行 */
    private fun relative(at: Long): String {
        if (at <= 0) return ""
        val zone = ZoneId.systemDefault()
        val time = LocalDateTime.ofInstant(Instant.ofEpochMilli(at), zone)
        val today = LocalDate.now(zone)
        val minutes = java.time.Duration.between(time, LocalDateTime.now(zone)).toMinutes()
        return when {
            minutes < 1L -> "刚刚"
            minutes < 60L -> "${minutes} 分钟前"
            time.toLocalDate() == today -> time.format(HOUR_MINUTE)
            time.toLocalDate() == today.minusDays(1) -> "昨天"
            time.year == today.year -> time.format(MONTH_DAY)
            else -> time.format(FULL_DATE)
        }
    }

    class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val title: TextView = itemView.findViewById(R.id.tvConvTitle)
        val time: TextView = itemView.findViewById(R.id.tvConvTime)
        val dot: View = itemView.findViewById(R.id.dotCurrent)
    }

    private companion object {
        val HOUR_MINUTE: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        val MONTH_DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("M月d日")
        val FULL_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy年M月d日")
    }
}

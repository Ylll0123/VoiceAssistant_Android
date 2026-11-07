package com.example.voiceassistant.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
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
 * - 平时：点一条切过去，当前那条左边有个小圆点
 * - 编辑模式：每条右侧露出删除按钮，点标题不再切换；长按任意一条，或点抽屉右上角，都能进来
 * - 列表最后永远跟着一个加号，新建对话就在那儿
 */
class ConversationAdapter(
    private val onOpen: (Conversation) -> Unit,
    private val onDelete: (Conversation) -> Unit,
    private val onAdd: () -> Unit,
    private val onEnterEdit: () -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = mutableListOf<Conversation>()
    private var currentId: String? = null
    private var editing = false

    /** 最后多出来的一项是加号 */
    override fun getItemCount(): Int = items.size + 1

    override fun getItemViewType(position: Int): Int =
        if (position == items.size) TYPE_ADD else TYPE_ITEM

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_ADD) {
            AddHolder(inflater.inflate(R.layout.item_conversation_add, parent, false))
        } else {
            Holder(inflater.inflate(R.layout.item_conversation, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is AddHolder -> holder.bind()
            is Holder -> holder.bind(items.getOrNull(position))
        }
    }

    fun submit(list: List<Conversation>, current: String?) {
        items.clear()
        items.addAll(list)
        currentId = current
        notifyDataSetChanged()
    }

    /** 进出编辑模式：只影响每条的删除按钮，不重建列表数据 */
    fun setEditing(value: Boolean) {
        if (editing == value) return
        editing = value
        notifyDataSetChanged()
    }

    fun isEditing(): Boolean = editing

    inner class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val title: TextView = itemView.findViewById(R.id.tvConvTitle)
        val time: TextView = itemView.findViewById(R.id.tvConvTime)
        val dot: View = itemView.findViewById(R.id.dotCurrent)
        private val delete: ImageView = itemView.findViewById(R.id.btnDelete)

        fun bind(item: Conversation?) {
            if (item == null) return
            title.text = item.title
            time.text = relative(item.updatedAt)
            dot.visibility = if (item.id == currentId) View.VISIBLE else View.INVISIBLE
            delete.visibility = if (editing) View.VISIBLE else View.GONE

            delete.setOnClickListener { onDelete(item) }
            itemView.setOnClickListener {
                // 编辑模式下点标题不切会话，免得想删却跳走了
                if (!editing) onOpen(item)
            }
            itemView.setOnLongClickListener {
                if (editing) {
                    true
                } else {
                    onEnterEdit()
                    true
                }
            }
        }
    }

    inner class AddHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val button: ImageView = itemView.findViewById(R.id.btnAddChat)

        fun bind() {
            // 编辑模式下不让新建：先把删的事做完，逻辑简单也不容易误触
            button.alpha = if (editing) 0.35f else 1f
            button.isEnabled = !editing
            button.setOnClickListener { if (!editing) onAdd() }
        }
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

    private companion object {
        const val TYPE_ITEM = 0
        const val TYPE_ADD = 1
        val HOUR_MINUTE: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        val MONTH_DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("M月d日")
        val FULL_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy年M月d日")
    }
}

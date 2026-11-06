package com.example.voiceassistant.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.voiceassistant.R
import com.example.voiceassistant.data.ChatMessage

/**
 * 对话气泡列表：用户消息靠右（蓝底），助手消息靠左（白底）。
 *
 * 点自己的气泡会回调 [onUserClick]，位置由 Adapter 给——
 * 界面层拿到位置后弹「修改 / 删除」，不需要自己反查是哪一条。
 */
class ChatAdapter(
    private val onUserClick: ((Int) -> Unit)? = null
) : RecyclerView.Adapter<ChatAdapter.MessageViewHolder>() {

    private val items = mutableListOf<ChatMessage>()

    override fun getItemCount(): Int = items.size

    override fun getItemViewType(position: Int): Int {
        val item = items[position]
        return when {
            item.kind == ChatMessage.KIND_THINKING -> TYPE_THINKING
            item.isUser -> TYPE_USER
            else -> TYPE_BOT
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val layoutRes = when (viewType) {
            TYPE_USER -> R.layout.item_message_user
            TYPE_THINKING -> R.layout.item_message_thinking
            else -> R.layout.item_message_bot
        }
        val view = LayoutInflater.from(parent.context).inflate(layoutRes, parent, false)
        return MessageViewHolder(view)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        val message = items[position]
        holder.bind(message)

        // 只有自己说的话能改能删：助手的回答是执行结果，改它没有意义
        holder.itemView.setOnClickListener(
            if (message.isUser && onUserClick != null) {
                View.OnClickListener {
                    val index = holder.bindingAdapterPosition
                    if (index != RecyclerView.NO_POSITION) onUserClick.invoke(index)
                }
            } else {
                null
            }
        )
        holder.itemView.isClickable = message.isUser && onUserClick != null
    }

    /**
     * 用完整列表刷新。
     * 消息条数不多，全量刷新足够；好处是 Fragment 重建后也能一步恢复画面。
     */
    fun submit(messages: List<ChatMessage>) {
        items.clear()
        items.addAll(messages)
        notifyDataSetChanged()
    }

    class MessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val textView: TextView = itemView.findViewById(R.id.tvMessage)

        fun bind(message: ChatMessage) {
            textView.text = message.text
        }
    }

    private companion object {
        const val TYPE_USER = 1
        const val TYPE_BOT = 2
        const val TYPE_THINKING = 3
    }
}

package com.example.voiceassistant.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.voiceassistant.R
import com.example.voiceassistant.data.ChatMessage

/** 对话气泡列表：用户消息靠右（蓝底），助手消息靠左（白底） */
class ChatAdapter(
    private val items: MutableList<ChatMessage> = mutableListOf()
) : RecyclerView.Adapter<ChatAdapter.MessageViewHolder>() {

    override fun getItemCount(): Int = items.size

    override fun getItemViewType(position: Int): Int =
        if (items[position].isUser) TYPE_USER else TYPE_BOT

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val layoutRes = if (viewType == TYPE_USER) {
            R.layout.item_message_user
        } else {
            R.layout.item_message_bot
        }
        val view = LayoutInflater.from(parent.context).inflate(layoutRes, parent, false)
        return MessageViewHolder(view)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        holder.bind(items[position])
    }

    /** 追加一条消息并滚动到底部 */
    fun add(message: ChatMessage) {
        items.add(message)
        notifyItemInserted(items.size - 1)
    }

    fun clear() {
        val size = items.size
        items.clear()
        if (size > 0) notifyItemRangeRemoved(0, size)
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
    }
}

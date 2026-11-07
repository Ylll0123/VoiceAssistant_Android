package com.example.voiceassistant.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.voiceassistant.R
import com.example.voiceassistant.data.ChatMessage

/**
 * 对话气泡列表：用户消息靠右（蓝底），助手消息靠左（白底）。
 *
 * 交互只有一种入口：**长按**自己的气泡 → 弹出「修改 / 删除」（在 Fragment 里弹）。
 * 单击不做任何事，避免误触——聊天记录被误改比改不了更烦。
 *
 * 修改是**行内**的：那条气泡自己变成输入框 + 保存 / 取消，
 * 改的就是眼前这一句，不再另开一个对话框。
 */
class ChatAdapter(
    private val onLongPress: (Int) -> Unit,
    private val onSaveEdit: (Int, String) -> Unit
) : RecyclerView.Adapter<ChatAdapter.MessageViewHolder>() {

    private val items = mutableListOf<ChatMessage>()

    /** 正在编辑第几条；-1 表示没有 */
    private var editing = -1

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
        val message = items.getOrNull(position) ?: return
        holder.bind(message, position == editing)

        if (!message.isUser) {
            holder.itemView.setOnLongClickListener(null)
            return
        }

        // 长按弹操作：只有最后一条用户消息会真的被 Fragment 接受
        val longPress = View.OnLongClickListener {
            onLongPress(position)
            true
        }
        holder.text.setOnLongClickListener(longPress)
        holder.editPanel?.setOnLongClickListener(null)

        holder.btnSave?.setOnClickListener {
            val text = holder.edit?.text?.toString().orEmpty()
            onSaveEdit(position, text)
        }
        holder.btnCancel?.setOnClickListener { stopEdit() }
    }

    /**
     * 用完整列表刷新。
     * 消息条数不多，全量刷新足够；好处是切换会话 / 重建视图后一步恢复画面。
     *
     * 刷新一律退出行内编辑：内容已经变了（保存 / 删除 / 切换会话），
     * 还留着编辑态会出现「改完还在改」的错觉。
     */
    fun submit(messages: List<ChatMessage>) {
        items.clear()
        items.addAll(messages)
        editing = -1
        notifyDataSetChanged()
    }

    /** 进入行内编辑；越界或不是用户消息时什么也不做 */
    fun startEdit(index: Int): Boolean {
        val message = items.getOrNull(index) ?: return false
        if (!message.isUser) return false
        editing = index
        notifyItemChanged(index)
        return true
    }

    fun stopEdit() {
        if (editing < 0) return
        val old = editing
        editing = -1
        if (old in items.indices) notifyItemChanged(old)
    }

    fun isEditing(): Boolean = editing >= 0

    class MessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val text: TextView = itemView.findViewById(R.id.tvMessage)
        val editPanel: LinearLayout? = itemView.findViewById(R.id.editPanel)
        val edit: EditText? = itemView.findViewById(R.id.etEdit)
        val btnSave: TextView? = itemView.findViewById(R.id.btnEditSave)
        val btnCancel: TextView? = itemView.findViewById(R.id.btnEditCancel)

        fun bind(message: ChatMessage, editingNow: Boolean) {
            text.text = message.text
            if (editPanel == null) return
            text.visibility = if (editingNow) View.GONE else View.VISIBLE
            editPanel.visibility = if (editingNow) View.VISIBLE else View.GONE
            if (editingNow) {
                edit?.setText(message.text)
                edit?.setSelection(edit?.text?.length ?: 0)
                edit?.requestFocus()
            }
        }
    }

    private companion object {
        const val TYPE_USER = 1
        const val TYPE_BOT = 2
    }
}

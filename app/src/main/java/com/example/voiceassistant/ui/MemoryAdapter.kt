package com.example.voiceassistant.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.voiceassistant.R
import com.example.voiceassistant.memory.MemoryEntry

/**
 * 记忆列表适配器。
 *
 * 用 ListAdapter + DiffUtil，增删一条只刷新那一行，
 * 不会整表重绘（长列表滚动位置也不会跳）。
 */
class MemoryAdapter(
    private val onDelete: (MemoryEntry) -> Unit
) : ListAdapter<MemoryEntry, MemoryAdapter.ViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_memory, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        private val tvQuestion: TextView = itemView.findViewById(R.id.tvMemoryQuestion)
        private val tvAnswer: TextView = itemView.findViewById(R.id.tvMemoryAnswer)
        private val tvSource: TextView = itemView.findViewById(R.id.tvMemorySource)
        private val btnDelete: ImageButton = itemView.findViewById(R.id.btnDeleteMemory)

        fun bind(entry: MemoryEntry) {
            tvQuestion.text = entry.question
            tvAnswer.text = entry.answer
            tvSource.text = if (entry.isTaught) {
                itemView.context.getString(R.string.memory_source_taught)
            } else {
                itemView.context.getString(R.string.memory_source_seed)
            }
            btnDelete.setOnClickListener { onDelete(entry) }
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<MemoryEntry>() {
            override fun areItemsTheSame(oldItem: MemoryEntry, newItem: MemoryEntry): Boolean =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: MemoryEntry, newItem: MemoryEntry): Boolean =
                oldItem == newItem
        }
    }
}

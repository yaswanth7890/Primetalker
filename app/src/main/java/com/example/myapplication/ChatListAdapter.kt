package com.example.myapplication

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.myapplication.db.ChatListItem

class ChatListAdapter(
    private val onClick: (String) -> Unit,
    private val onLongPress: (String) -> Unit
) : ListAdapter<ChatListItem, ChatListAdapter.VH>(DIFF) {

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<ChatListItem>() {
            override fun areItemsTheSame(a: ChatListItem, b: ChatListItem) =
                a.peerIdentity == b.peerIdentity

            override fun areContentsTheSame(a: ChatListItem, b: ChatListItem) =
                a == b
        }
    }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.tvPeer)
        val last: TextView = v.findViewById(R.id.tvLastMessage)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chat_list, parent, false)
        return VH(v)
    }



    override fun onBindViewHolder(h: VH, pos: Int) {
        val item = getItem(pos)
        h.name.text = item.peerIdentity
        h.last.text = item.lastMessage ?: ""

        // Normal click → open chat
        h.itemView.setOnClickListener {
            onClick(item.peerIdentity)

            // Long press → delete chat
            h.itemView.setOnLongClickListener {
                onLongPress(item.peerIdentity)
                true
            }
        }
    }
}

package com.example.myapplication

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import kotlin.random.Random

class ContactsAdapter(
    private var items: List<String>,
    private val onAudioClick: (String) -> Unit,
    private val onVideoClick: (String) -> Unit
) : RecyclerView.Adapter<ContactsAdapter.ContactViewHolder>() {

    private val colors = listOf(
        "#FF5722", "#4CAF50", "#3F51B5", "#9C27B0",
        "#009688", "#FF9800", "#795548", "#607D8B"
    )

    inner class ContactViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val avatar: TextView = view.findViewById(R.id.contactAvatar)
        val name: TextView = view.findViewById(R.id.contactName)
        val btnAudio: ImageView = view.findViewById(R.id.btnContactAudio)
        val btnVideo: ImageView = view.findViewById(R.id.btnContactVideo)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ContactViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_contact, parent, false)
        return ContactViewHolder(view)
    }

    override fun onBindViewHolder(holder: ContactViewHolder, position: Int) {
        val contact = items[position]

        holder.name.text = contact
        holder.avatar.text = contact.firstOrNull()?.uppercase() ?: "?"

        val color = android.graphics.Color.parseColor(colors[Random.nextInt(colors.size)])
        holder.avatar.setBackgroundColor(color)

        if (contact == "No contact found") {
            holder.btnAudio.visibility = View.GONE
            holder.btnVideo.visibility = View.GONE
        } else {
            holder.btnAudio.visibility = View.VISIBLE
            holder.btnVideo.visibility = View.VISIBLE

            holder.btnAudio.setOnClickListener { onAudioClick(contact) }
            holder.btnVideo.setOnClickListener { onVideoClick(contact) }
        }
    }

    override fun getItemCount(): Int = items.size

    fun updateList(newList: List<String>) {
        items = newList
        notifyDataSetChanged()
    }
}

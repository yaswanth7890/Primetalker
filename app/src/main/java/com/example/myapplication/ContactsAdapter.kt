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
    private val onVideoClick: (String) -> Unit,
    private val onDeleteClick: (String) -> Unit
) : RecyclerView.Adapter<ContactsAdapter.ContactViewHolder>() {


    inner class ContactViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val direction: ImageView = view.findViewById(R.id.imgCallDirection)
        val name: TextView = view.findViewById(R.id.contactName)
        val details: TextView = view.findViewById(R.id.contactDetails)
        val btnAudio: ImageView = view.findViewById(R.id.btnContactAudio)
        val btnVideo: ImageView = view.findViewById(R.id.btnContactVideo)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ContactViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_contact, parent, false)
        return ContactViewHolder(view)
    }



    override fun onBindViewHolder(holder: ContactViewHolder, position: Int) {

        val item = items[position]

        if (item == "No calls yet" || item == "No contact found") {
            holder.name.text = item
            holder.details.visibility = View.GONE
            holder.direction.visibility = View.GONE
            holder.btnAudio.visibility = View.GONE
            holder.btnVideo.visibility = View.GONE

            return
        }

        val parts = item.split('|')

        if (parts.size == 3 && parts[2] == "contact") {

            val name = parts[0]
            val number = parts[1]

            holder.name.text = name
            holder.details.visibility = View.VISIBLE
            holder.details.text = number
            holder.direction.visibility = View.GONE

            holder.btnAudio.visibility = View.VISIBLE
            holder.btnVideo.visibility = View.VISIBLE

            holder.btnAudio.setOnClickListener {
                onAudioClick(number)
            }

            holder.btnVideo.setOnClickListener {
                onVideoClick(number)
            }
            holder.itemView.setOnClickListener {

                val context = holder.itemView.context

                val intent = android.content.Intent(
                    context,
                    ChatActivity::class.java
                )

                intent.putExtra("peer_identity", number)

                context.startActivity(intent)
            }
            return
        }

        if (parts.size < 5) return

        val displayName = parts[0]
        val peer = parts[1]
        val totalCalls = parts[2]
        val time = parts[3]
        val direction = parts[4]

        holder.name.text = displayName

        holder.details.visibility = View.VISIBLE
        holder.details.text =
            "${formatInternational(peer)}\n($totalCalls) • $time"
        holder.direction.visibility = View.VISIBLE

        if (direction == "outgoing") {
            holder.direction.setImageResource(android.R.drawable.arrow_up_float)
            holder.direction.setColorFilter(android.graphics.Color.parseColor("#4CAF50"))
        } else {
            holder.direction.setImageResource(android.R.drawable.arrow_down_float)
            holder.direction.setColorFilter(android.graphics.Color.parseColor("#F44336"))
        }



        holder.btnAudio.visibility = View.VISIBLE
        holder.btnVideo.visibility = View.VISIBLE

        holder.btnAudio.setOnClickListener {
            onAudioClick(peer)
        }

        holder.btnVideo.setOnClickListener {
            onVideoClick(peer)
        }


        holder.itemView.setOnLongClickListener {

            android.app.AlertDialog.Builder(holder.itemView.context)
                .setTitle("Delete call history")
                .setMessage("Delete all call history with +$peer?")
                .setPositiveButton("Delete") { _, _ ->
                    onDeleteClick(peer)
                }
                .setNegativeButton("Cancel", null)
                .show()

            true
        }

    }

    override fun getItemCount(): Int = items.size

    fun updateList(newList: List<String>) {
        items = newList
        notifyDataSetChanged()
    }

    private fun formatInternational(number: String): String {
        return try {
            val phoneUtil = com.google.i18n.phonenumbers.PhoneNumberUtil.getInstance()
            val parsed = phoneUtil.parse("+$number", null)
            phoneUtil.format(parsed, com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberFormat.INTERNATIONAL)
        } catch (e: Exception) {
            "+$number"
        }
    }
}

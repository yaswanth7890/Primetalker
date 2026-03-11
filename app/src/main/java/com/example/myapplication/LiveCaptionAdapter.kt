package com.example.myapplication

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView



class LiveCaptionAdapter(
    private val items: List<LiveCaption>
) : RecyclerView.Adapter<LiveCaptionAdapter.CaptionVH>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CaptionVH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_caption, parent, false)
        return CaptionVH(view)
    }

    override fun onBindViewHolder(holder: CaptionVH, position: Int) {
        val item = items[position]

        holder.original.text = item.original

        if (item.translated.isBlank()) {
            holder.translated.visibility = View.GONE
        } else {
            holder.translated.visibility = View.VISIBLE
            holder.translated.text = item.translated
        }

        // Align bubble left or right safely
        holder.container.gravity =
            if (item.isMine) Gravity.END else Gravity.START

        // Optional: Different background for sender/receiver
        if (item.isMine) {
            holder.original.setBackgroundColor(Color.parseColor("#2E7D32")) // Green
        } else {
            holder.original.setBackgroundColor(Color.parseColor("#333333")) // Dark grey
        }
    }

    override fun getItemCount() = items.size

    class CaptionVH(view: View) : RecyclerView.ViewHolder(view) {
        val container: LinearLayout = view.findViewById(R.id.captionContainer)
        val original: TextView = view.findViewById(R.id.txtOriginal)
        val translated: TextView = view.findViewById(R.id.txtTranslated)
    }
}


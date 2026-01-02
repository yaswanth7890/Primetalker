package com.example.myapplication

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.myapplication.db.DbProvider
import kotlinx.coroutines.launch
import android.widget.ImageButton
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog


class ChatListActivity : AppCompatActivity() {

    private lateinit var recycler: RecyclerView
    private lateinit var adapter: ChatListAdapter
    private lateinit var myIdentity: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat_list)

        recycler = findViewById(R.id.chatListRecycler)
        recycler.layoutManager = LinearLayoutManager(this)

        findViewById<ImageButton>(R.id.btnNewChat).setOnClickListener {
            showNewChatDialog()
        }



        adapter = ChatListAdapter(
            onClick = { peer ->
                val i = Intent(this, ChatActivity::class.java)
                i.putExtra("peer_identity", peer)
                startActivity(i)
            },
            onLongPress = { peer ->
                confirmDeleteChat(peer)
            }
        )


        recycler.adapter = adapter

        myIdentity = getSharedPreferences("app_prefs", MODE_PRIVATE)
            .getString("identity", "")!!
            .replace("\\D".toRegex(), "")

        loadChats()
    }

    private fun showNewChatDialog() {
        val et = EditText(this).apply {
            hint = "Enter phone number"
            inputType = android.text.InputType.TYPE_CLASS_PHONE
        }

        AlertDialog.Builder(this)
            .setTitle("New Chat")
            .setView(et)
            .setPositiveButton("Start") { _, _ ->
                val number = et.text.toString().trim()
                    .replace("\\D".toRegex(), "")

                if (number.isNotEmpty()) {
                    val i = Intent(this, ChatActivity::class.java)
                    i.putExtra("peer_identity", number)
                    startActivity(i)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }



    override fun onResume() {
        super.onResume()
        loadChats()
    }

    private fun loadChats() {
        lifecycleScope.launch {
            val chats = DbProvider.db.chatDao().getChatList(myIdentity)
            adapter.submitList(chats)

            if (chats.isEmpty()) {
                Toast.makeText(
                    this@ChatListActivity,
                    "No chats yet. Tap + to start one",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }


    private fun confirmDeleteChat(peer: String) {
        AlertDialog.Builder(this)
            .setTitle("Delete chat?")
            .setMessage("This will delete all messages with $peer")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    DbProvider.db.chatDao().deleteChat(myIdentity, peer)
                    loadChats()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }


}

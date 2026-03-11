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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.core.widget.addTextChangedListener




class ChatListActivity : AppCompatActivity() {

    private lateinit var recycler: RecyclerView
    private lateinit var adapter: ChatListAdapter
    private lateinit var myIdentity: String

    // Dock
    private lateinit var dockChats: LinearLayout
    private lateinit var dockTranslate: LinearLayout
    private lateinit var dockCalls: LinearLayout
    private lateinit var dockSettings: LinearLayout

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            loadChats()
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat_list)

        recycler = findViewById(R.id.chatListRecycler)
        recycler.layoutManager = LinearLayoutManager(this)

        // -------- Dock --------
        dockChats = findViewById(R.id.dock_chats_layout)
        dockTranslate = findViewById(R.id.dock_translate_layout)
        dockCalls = findViewById(R.id.dock_calls_layout)
        dockSettings = findViewById(R.id.dock_settings_layout)

        setupDock()

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

        myIdentity = PhoneUtils.normalizeIdentity(
            getSharedPreferences("app_prefs", MODE_PRIVATE)
                .getString("identity", "")!!
        )

        loadChats()
    }


    // ================= Dock =================

    private fun setupDock() {
        highlightCurrentPage(dockChats)

        dockChats.setOnClickListener {
            startActivity(Intent(this, ChatListActivity::class.java))
            finish()
        }


        dockTranslate.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }

        dockCalls.setOnClickListener {
            startActivity(Intent(this, CallActivity::class.java))
            finish()
        }

        dockSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
            finish()
        }
    }

    private fun highlightCurrentPage(current: LinearLayout) {
        val docks = listOf(dockChats, dockTranslate, dockCalls, dockSettings)
        docks.forEach { dock ->
            try {
                val img = dock.getChildAt(0) as ImageView
                val text = dock.getChildAt(1) as TextView
                if (dock == current) {
                    img.setColorFilter(0xFFFFA500.toInt())
                    text.setTextColor(0xFFFFA500.toInt())
                } else {
                    img.setColorFilter(0xFFFFFFFF.toInt())
                    text.setTextColor(0xFFFFFFFF.toInt())
                }
            } catch (_: Exception) { }
        }
    }



    private fun loadContacts(): List<String> {

        val list = mutableListOf<String>()

        val cursor = contentResolver.query(
            android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            null,
            null,
            android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
        )

        cursor?.use {

            while (it.moveToNext()) {

                val name = it.getString(0)
                val number = PhoneUtils.normalizeIdentity(
                    it.getString(1)
                )

                list.add("$name|$number|contact")
            }
        }

        if (list.isEmpty()) {
            list.add("No contact found")
        }

        return list
    }


    private fun showNewChatDialog() {

        val dialogView = layoutInflater.inflate(R.layout.dialog_new_chat, null)

        val etSearch = dialogView.findViewById<EditText>(R.id.etSearch)
        val recycler = dialogView.findViewById<RecyclerView>(R.id.recyclerContacts)

        recycler.layoutManager = LinearLayoutManager(this)

        val contactList = loadContacts()

        val adapter = ContactsAdapter(
            contactList,
            onAudioClick = {},
            onVideoClick = {},
            onDeleteClick = {}
        )

        recycler.adapter = adapter

        // search filtering
        etSearch.addTextChangedListener { text ->

            val query = text.toString().lowercase()

            val filtered = contactList.filter { item ->
                item.lowercase().contains(query)
            }

            adapter.updateList(filtered)
        }

        AlertDialog.Builder(this)
            .setTitle("New Chat")
            .setView(dialogView)
            .setPositiveButton("Start") { _, _ ->

                val number = PhoneUtils.normalizeIdentity(
                    etSearch.text.toString()
                )

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

    override fun onStart() {
        super.onStart()

        val filter = IntentFilter(ChatActions.ACTION_REFRESH_CHATLIST)

        ContextCompat.registerReceiver(
            this,
            refreshReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }





    override fun onStop() {
        super.onStop()
        unregisterReceiver(refreshReceiver)
    }



}

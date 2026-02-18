package com.example.myapplication.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "chats")
data class ChatEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val myIdentity: String,      // logged-in user
    val peerIdentity: String,    // chat partner
    val fromIdentity: String,    // who sent this message

    val originalText: String,
    val timestamp: Long = System.currentTimeMillis(),

    val isRead: Boolean = false




)




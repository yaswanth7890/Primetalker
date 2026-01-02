package com.example.myapplication.db


data class ChatListItem(
    val peerIdentity: String,
    val lastMessage: String,
    val lastTime: Long
)

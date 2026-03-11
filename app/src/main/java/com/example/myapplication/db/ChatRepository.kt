package com.example.myapplication.db

import android.content.Context

class ChatRepository(context: Context) {

    private val dao = DbProvider.db.chatDao()


    suspend fun saveMessage(
        me: String,
        peer: String,
        from: String,
        text: String
    ) {

        val messageId = java.util.UUID.randomUUID().toString()

        dao.insert(
            ChatEntity(
                messageId = messageId,
                myIdentity = me,
                peerIdentity = peer,
                fromIdentity = from,
                originalText = text,
                status = "PENDING",
                isRead = false
            )
        )
    }

    suspend fun getConversation(me: String, peer: String) =
        dao.getConversation(me, peer)

    suspend fun getChatList(me: String) =
        dao.getChatList(me)
}

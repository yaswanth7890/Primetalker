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
        dao.insert(
            ChatEntity(
                myIdentity = me,
                peerIdentity = peer,
                fromIdentity = from,
                originalText = text
            )
        )
    }

    suspend fun getConversation(me: String, peer: String) =
        dao.getConversation(me, peer)

    suspend fun getChatList(me: String) =
        dao.getChatList(me)
}

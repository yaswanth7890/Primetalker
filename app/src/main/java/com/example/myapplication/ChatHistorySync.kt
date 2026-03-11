package com.example.myapplication

import android.content.Context
import com.example.myapplication.db.DbProvider
import com.example.myapplication.db.ChatEntity
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ChatHistorySync {

    private const val URL =
        "https://nodical-earlie-unyieldingly.ngrok-free.dev/chat/history"

    suspend fun sync(context: Context, identity: String) {

        withContext(Dispatchers.IO) {

            try {

                val req = Request.Builder()
                    .url("$URL?identity=$identity")
                    .build()

                val res = OkHttpClient().newCall(req).execute()

                if (!res.isSuccessful) return@withContext

                val body = res.body?.string() ?: return@withContext

                val json = JSONObject(body)
                val arr = json.getJSONArray("messages")

                val dao = DbProvider.db.chatDao()

                for (i in 0 until arr.length()) {

                    val m = arr.getJSONObject(i)

                    val messageId = m.getString("id")
                    val from = m.getString("from_identity")
                    val to = m.getString("to_identity")

                    val encrypted = m.getString("original_text")
                    val text = CryptoUtils.decrypt(encrypted)

                    val peer =
                        if (from == identity) to else from

                    val exists = dao.getMessageById(messageId)

                    if (exists == null) {

                        dao.insert(
                            ChatEntity(
                                messageId = messageId,
                                myIdentity = identity,
                                peerIdentity = peer,
                                fromIdentity = from,
                                originalText = text,
                                timestamp = System.currentTimeMillis(),
                                isRead = true,
                                status = "DELIVERED"
                            )
                        )

                    }
                }

            } catch (e: Exception) {

                // 🔥 VERY IMPORTANT
                // Never crash the app if history sync fails

                android.util.Log.e(
                    "ChatHistorySync",
                    "History sync failed: ${e.message}"
                )
            }
        }
    }
}
package com.example.myapplication

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

object ChatNetwork {

    private const val URL =
        "https://nodical-earlie-unyieldingly.ngrok-free.dev/chat/send"

    fun send(
        messageId: String,
        from: String,
        to: String,
        text: String
    ) {

        val json = JSONObject().apply {
            put("messageId", messageId)
            put("from", from)
            put("to", to)
            put("original", text)
        }

        val body =
            json.toString()
                .toRequestBody("application/json".toMediaType())

        val req =
            Request.Builder()
                .url(URL)
                .post(body)
                .build()

        OkHttpClient().newCall(req).execute()
    }
}
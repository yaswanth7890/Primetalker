package com.example.myapplication

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.myapplication.db.DbProvider

class PendingMessageWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {

        val db = DbProvider.db

        val pending = db.chatDao().getPendingMessages()

        pending.forEach {

            try {

                ChatNetwork.send(
                    it.messageId,
                    it.fromIdentity,
                    it.peerIdentity,
                    it.originalText
                )

                db.chatDao().markMessageSent(it.messageId)

            } catch (e: Exception) {}

        }

        return Result.success()
    }
}
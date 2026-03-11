package com.example.myapplication.db

import androidx.room.*

@Dao
interface ChatDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(chat: ChatEntity)

    // Load messages for ONE chat (like WhatsApp conversation)
    @Query("""
        SELECT * FROM chats
        WHERE myIdentity = :me
          AND peerIdentity = :peer
        ORDER BY timestamp ASC
    """)
    suspend fun getConversation(me: String, peer: String): List<ChatEntity>

    // Load chat list (WhatsApp home screen)
    @Query("""
SELECT 
    peerIdentity,
    COUNT(CASE WHEN isRead = false THEN 1 END) AS unreadCount,
    MAX(timestamp) AS lastTime,
    (
        SELECT originalText FROM chats c2
        WHERE c2.peerIdentity = chats.peerIdentity
          AND c2.myIdentity = :me
        ORDER BY timestamp DESC
        LIMIT 1
    ) AS lastMessage
FROM chats
WHERE myIdentity = :me
GROUP BY peerIdentity
ORDER BY lastTime DESC
""")
    suspend fun getChatList(me: String): List<ChatListItem>




    @Query("DELETE FROM chats WHERE messageId = :messageId")
    suspend fun deleteMessage(messageId: String)


    @Query("""
DELETE FROM chats
WHERE (myIdentity = :me AND peerIdentity = :peer)
   OR (myIdentity = :peer AND peerIdentity = :me)
""")
    suspend fun deleteChat(me: String, peer: String)



    @Query("""
UPDATE chats 
SET isRead = true 
WHERE myIdentity = :me 
AND peerIdentity = :peer
""")
    suspend fun markChatRead(me: String, peer: String)

    @Query("SELECT * FROM chats WHERE status='PENDING'")
    suspend fun getPendingMessages(): List<ChatEntity>
    @Query("UPDATE chats SET status='SENT' WHERE messageId=:messageId")
    suspend fun markMessageSent(messageId: String)

    @Query("UPDATE chats SET status='DELIVERED' WHERE messageId=:messageId")
    suspend fun markMessageDelivered(messageId: String)

    @Query("UPDATE chats SET status='DELIVERED' WHERE messageId=:messageId")
    suspend fun markDelivered(messageId:String)

    @Query("UPDATE chats SET status='READ' WHERE messageId=:messageId")
    suspend fun markRead(messageId:String)

    @Query("SELECT * FROM chats WHERE messageId = :id LIMIT 1")
    suspend fun getMessageById(id: String): ChatEntity?

}

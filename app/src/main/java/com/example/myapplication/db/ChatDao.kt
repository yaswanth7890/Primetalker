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




    @Query("DELETE FROM chats WHERE id = :messageId")
    suspend fun deleteMessage(messageId: Long)


    @Query("""
    DELETE FROM chats
    WHERE myIdentity = :me AND peerIdentity = :peer
""")
    suspend fun deleteChat(me: String, peer: String)



    @Query("""
UPDATE chats 
SET isRead = true 
WHERE myIdentity = :me 
AND peerIdentity = :peer
""")
    suspend fun markChatRead(me: String, peer: String)





}

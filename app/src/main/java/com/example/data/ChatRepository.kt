package com.example.data

import android.content.Context
import android.content.SharedPreferences
import com.example.persona.PersonaConfig
import kotlinx.coroutines.flow.Flow

class ChatRepository(
    private val dao: ChatMessageDao,
    context: Context
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("hamed_preferences", Context.MODE_PRIVATE)

    val allMessages: Flow<List<ChatMessageEntity>> = dao.getAllMessages()

    suspend fun getRecentMessages(limit: Int = 16): List<ChatMessageEntity> {
        return dao.getRecentMessages(limit).reversed()
    }

    suspend fun saveMessage(content: String, isUser: Boolean, personaId: String): Long {
        return dao.insertMessage(
            ChatMessageEntity(
                content = content,
                isUser = isUser,
                personaId = personaId,
                timestamp = System.currentTimeMillis()
            )
        )
    }

    suspend fun clearHistory() {
        dao.clearAllMessages()
    }

    fun getSelectedPersonaId(): String {
        return prefs.getString("selected_persona_id", PersonaConfig.DEFAULT_PERSONA.id)
            ?: PersonaConfig.DEFAULT_PERSONA.id
    }

    fun setSelectedPersonaId(personaId: String) {
        prefs.edit().putString("selected_persona_id", personaId).apply()
    }
}

package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.ChatMessageEntity
import com.example.data.ChatRepository
import com.example.network.GeminiClient
import com.example.persona.Persona
import com.example.persona.PersonaConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: ChatRepository
    private val geminiClient: GeminiClient = GeminiClient()

    val messages: StateFlow<List<ChatMessageEntity>>

    private val _currentPersona = MutableStateFlow(PersonaConfig.DEFAULT_PERSONA)
    val currentPersona: StateFlow<Persona> = _currentPersona.asStateFlow()

    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private var lastUserPrompt: String? = null

    init {
        val database = AppDatabase.getDatabase(application)
        repository = ChatRepository(database.chatMessageDao(), application)

        messages = repository.allMessages.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

        // Restore saved persona
        val savedPersonaId = repository.getSelectedPersonaId()
        _currentPersona.value = PersonaConfig.getById(savedPersonaId)
    }

    fun onInputTextChanged(newText: String) {
        _inputText.value = newText
    }

    fun selectPersona(persona: Persona) {
        _currentPersona.value = persona
        repository.setSelectedPersonaId(persona.id)
    }

    fun sendMessage(customPrompt: String? = null) {
        val promptToSend = (customPrompt ?: _inputText.value).trim()
        if (promptToSend.isBlank() || _isLoading.value) return

        if (customPrompt == null) {
            _inputText.value = ""
        }
        _errorMessage.value = null
        lastUserPrompt = promptToSend

        val activePersona = _currentPersona.value

        viewModelScope.launch {
            // 1. Save user message to Room
            repository.saveMessage(
                content = promptToSend,
                isUser = true,
                personaId = activePersona.id
            )

            _isLoading.value = true

            // 2. Fetch conversation history for context
            val recentEntities = repository.getRecentMessages(limit = 12)
            // exclude the message we just inserted so it's not duplicated in prompt
            val historyPairs = recentEntities
                .dropLast(1)
                .map { it.content to it.isUser }

            // 3. Call Gemini
            val result = geminiClient.generateReply(
                conversationHistory = historyPairs,
                currentPrompt = promptToSend,
                systemInstructionText = activePersona.systemInstruction
            )

            _isLoading.value = false

            result.onSuccess { replyText ->
                // 4. Save model response to Room
                repository.saveMessage(
                    content = replyText,
                    isUser = false,
                    personaId = activePersona.id
                )
            }.onFailure { throwable ->
                _errorMessage.value = throwable.localizedMessage
                    ?: "خطای ناشناخته رخ داده است. لطفاً دوباره امتحان کنید."
            }
        }
    }

    fun retryLastMessage() {
        val prompt = lastUserPrompt
        if (!prompt.isNullOrBlank()) {
            _errorMessage.value = null
            sendMessage(prompt)
        }
    }

    fun clearChat() {
        viewModelScope.launch {
            repository.clearHistory()
            _errorMessage.value = null
            lastUserPrompt = null
        }
    }

    fun dismissError() {
        _errorMessage.value = null
    }
}

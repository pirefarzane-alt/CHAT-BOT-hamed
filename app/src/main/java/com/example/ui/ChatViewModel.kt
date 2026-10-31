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

    // Holds the failed prompt so retry can re-send without duplicate insertion in Room
    private var lastFailedPrompt: String? = null

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
        lastFailedPrompt = null

        val activePersona = _currentPersona.value

        viewModelScope.launch {
            // 1. Save user message to Room (once)
            repository.saveMessage(
                content = promptToSend,
                isUser = true,
                personaId = activePersona.id
            )

            _isLoading.value = true

            // 2. Fetch conversation history for context
            val recentEntities = repository.getRecentMessages(limit = 14)
            // Exclude the user message we just inserted so it's not duplicated in history context
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
                lastFailedPrompt = null
            }.onFailure { throwable ->
                // Record the failed prompt so Retry can re-send without re-saving to Room
                lastFailedPrompt = promptToSend
                _errorMessage.value = throwable.localizedMessage
                    ?: "خطای ناشناخته رخ داده است. لطفاً دوباره امتحان کنید."
            }
        }
    }

    fun retryLastMessage() {
        val promptToRetry = lastFailedPrompt
        // If there is no failed request or already loading, do nothing
        if (promptToRetry.isNullOrBlank() || _isLoading.value || _errorMessage.value == null) {
            return
        }

        _errorMessage.value = null
        _isLoading.value = true
        val activePersona = _currentPersona.value

        viewModelScope.launch {
            // Re-send the last user message to Gemini WITHOUT saving it again to Room.
            // The user's message is already in Room from the previous attempt.
            val recentEntities = repository.getRecentMessages(limit = 14)
            val historyPairs = if (recentEntities.isNotEmpty() && recentEntities.last().isUser) {
                recentEntities.dropLast(1).map { it.content to it.isUser }
            } else {
                recentEntities.map { it.content to it.isUser }
            }

            val result = geminiClient.generateReply(
                conversationHistory = historyPairs,
                currentPrompt = promptToRetry,
                systemInstructionText = activePersona.systemInstruction
            )

            _isLoading.value = false

            result.onSuccess { replyText ->
                // Only save the model's reply if the retry succeeds
                repository.saveMessage(
                    content = replyText,
                    isUser = false,
                    personaId = activePersona.id
                )
                lastFailedPrompt = null
            }.onFailure { throwable ->
                _errorMessage.value = throwable.localizedMessage
                    ?: "خطای ناشناخته رخ داده است. لطفاً دوباره امتحان کنید."
            }
        }
    }

    fun clearChat() {
        viewModelScope.launch {
            repository.clearHistory()
            _errorMessage.value = null
            lastFailedPrompt = null
        }
    }

    fun dismissError() {
        _errorMessage.value = null
    }
}

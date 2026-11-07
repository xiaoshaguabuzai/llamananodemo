package com.example.llamananodemo

data class ChatMessage(
    val content: String,
    val isUser: Boolean
)

data class ChatUiState(
    val messageList: List<ChatMessage> = emptyList(),
    val isGenerating: Boolean = false,
    val errorInfo: String? = null
)

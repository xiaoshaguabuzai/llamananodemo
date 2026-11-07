package com.example.llamananodemo

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ChatViewModel(
    private val context: Context
) : ViewModel() {

    private val chatManager = LlamaChatManager(context.applicationContext)

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val systemPrompt = "你是一名简洁干练的Android开发助手，回答简短精炼。"

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val copyOk = chatManager.copyModelFromAssets()
            if (!copyOk) {
                _uiState.value = _uiState.value.copy(errorInfo = "模型文件复制失败")
                return@launch
            }
            val loadOk = chatManager.initModel()
            if (!loadOk) {
                _uiState.value = _uiState.value.copy(errorInfo = "模型加载失败")
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    fun sendMessage(inputText: String) {
        if (inputText.isBlank()) return
        // 防止并发推理
        if (_uiState.value.isGenerating) return

        val userMsg = ChatMessage(inputText, isUser = true)
        val listAfterUserAdd = _uiState.value.messageList + userMsg

        _uiState.value = _uiState.value.copy(
            messageList = listAfterUserAdd,
            isGenerating = true,
            errorInfo = null
        )

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val sb = StringBuilder()
                var lastUpdate = 0L
                chatManager.chatStream(systemPrompt, inputText).collect { token ->
                    sb.append(token)
                    // 节流：每 80ms 才更新一次 UI 状态，
                    // 避免"每 token 拷贝整个消息列表 + 触发重组"的抖动
                    val now = System.currentTimeMillis()
                    if (now - lastUpdate >= 80) {
                        lastUpdate = now
                        updateAssistantMessage(sb.toString())
                    }
                }
                // 收尾必须刷新一次，保证最后 80ms 内的内容不丢
                updateAssistantMessage(sb.toString())
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(errorInfo = "生成异常：${e.message}")
            } finally {
                _uiState.value = _uiState.value.copy(isGenerating = false)
            }
        }
    }

    private fun updateAssistantMessage(text: String) {
        // 取当前最新列表，替换最后一条助手消息，而不是基于快照追加
        val currentList = _uiState.value.messageList.toMutableList()
        if (currentList.lastOrNull()?.isUser == false) {
            currentList.removeLast()
        }
        currentList.add(ChatMessage(text, isUser = false))
        _uiState.value = _uiState.value.copy(messageList = currentList)
    }

    /**
     * 停止生成（native 置标志位，流结束后 finally 会把 isGenerating 归位）
     */
    fun stopGeneration() {
        chatManager.stopGeneration()
    }

    override fun onCleared() {
        chatManager.destroy()
        super.onCleared() // ✅ 必须保留！
    }

    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(
                modelClass: Class<T>,
                extras: CreationExtras
            ): T {
                val app = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application
                return ChatViewModel(context = app) as T
            }
        }
    }
}

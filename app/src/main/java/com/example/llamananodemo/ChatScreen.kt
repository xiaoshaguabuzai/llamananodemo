package com.example.llamananodemo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

// ========= 微信配色 =========
private val WcPageBg = Color(0xFFEDEDED) // 聊天页背景灰
private val WcBarBg = Color(0xFFF7F7F7) // 顶栏/输入栏底色
private val WcBubbleUser = Color(0xFF95EC69) // 微信经典绿
private val WcBubbleAi = Color(0xFFFFFFFF) // 对方气泡白
private val WcText = Color(0xFF191919)
private val WcTextSecondary = Color(0xFF999999)
private val WcAccent = Color(0xFF07C160) // 微信绿（按钮）
private val WcAvatarUser = Color(0xFF7EC16E)
private val WcAvatarAi = Color(0xFF4A90D9)

@Composable
fun ChatPage() {
    val vm: ChatViewModel = viewModel(factory = ChatViewModel.Factory)
    val uiState by vm.uiState.collectAsStateWithLifecycle()

    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // 消息更新时滚到底部（流式期间高频触发，用 scrollToItem 避免动画堆积）
    LaunchedEffect(uiState.messageList.size, uiState.isGenerating) {
        if (uiState.messageList.isNotEmpty()) {
            listState.scrollToItem(uiState.messageList.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(WcPageBg)
    ) {
        // ========= 顶栏：标题 + "正在输入…" =========
        Surface(color = WcBarBg) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .height(52.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "AI 助手",
                        color = WcText,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Medium
                    )
                    if (uiState.isGenerating) {
                        Text(
                            text = "正在输入…",
                            color = WcTextSecondary,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }

        // ========= 消息列表 =========
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            state = listState,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)
        ) {
            items(uiState.messageList) { msg ->
                ChatBubble(message = msg)
            }
        }

        if (!uiState.errorInfo.isNullOrBlank()) {
            Text(
                text = uiState.errorInfo!!,
                color = MaterialTheme.colorScheme.error,
                fontSize = 13.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }

        // ========= 底部输入栏 =========
        Surface(color = WcBarBg) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("说点什么...", color = WcTextSecondary, fontSize = 15.sp) },
                    shape = RoundedCornerShape(8.dp),
                    maxLines = 4,
                    textStyle = LocalTextStyle.current.copy(color = WcText, fontSize = 16.sp),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor = Color.White,
                        focusedContainerColor = Color.White,
                        unfocusedBorderColor = Color.Transparent,
                        focusedBorderColor = Color.Transparent,
                        cursorColor = WcAccent
                    )
                )
                TextButton(
                    onClick = {
                        if (uiState.isGenerating) {
                            vm.stopGeneration()
                        } else {
                            val trimText = inputText.trim()
                            if (trimText.isNotBlank()) {
                                vm.sendMessage(trimText)
                                inputText = ""
                            }
                        }
                    }
                ) {
                    Text(
                        text = if (uiState.isGenerating) "停止" else "发送",
                        color = if (uiState.isGenerating) WcTextSecondary else WcAccent,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

@Composable
fun ChatBubble(message: ChatMessage) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.Top
    ) {
        if (message.isUser) {
            Spacer(modifier = Modifier.weight(1f))
            MessageBubble(message)
            Spacer(modifier = Modifier.width(8.dp))
            Avatar("我", WcAvatarUser)
        } else {
            Avatar("AI", WcAvatarAi)
            Spacer(modifier = Modifier.width(8.dp))
            MessageBubble(message)
            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    Surface(
        modifier = Modifier.widthIn(max = 280.dp),
        color = if (message.isUser) WcBubbleUser else WcBubbleAi,
        // 朝向头像的那一角收窄，模拟微信气泡的小尾巴
        shape = RoundedCornerShape(
            topStart = if (message.isUser) 8.dp else 2.dp,
            topEnd = if (message.isUser) 2.dp else 8.dp,
            bottomStart = 8.dp,
            bottomEnd = 8.dp
        )
    ) {
        Text(
            text = message.content.ifBlank { "…".take(3) },
            color = WcText,
            fontSize = 16.sp,
            lineHeight = 22.sp,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun Avatar(label: String, bg: Color) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(bg),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

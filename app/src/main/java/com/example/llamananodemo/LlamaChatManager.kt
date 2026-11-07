package com.example.llamananodemo

import android.content.Context
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import java.io.File
import java.io.InputStream

class LlamaChatManager(private val ctx: Context) {
    // 和CMake add_library(llama_wrapper SHARED ...) 保持一致
    init {
        System.loadLibrary("llama_wrapper")
    }

    private external fun loadModel(path: String): Boolean
    private external fun streamChat(sysPrompt: String, userPrompt: String)
    private external fun stopGenerate()
    private external fun release()

    // C++回调入口
    @Suppress("unused")
    fun onToken(text: String) {
        tokenCallback?.invoke(text)
    }

    private var tokenCallback: ((String) -> Unit)? = null

    /**
     * 复制assets里面gguf到app私有目录
     */
    fun copyModelFromAssets(): Boolean {
        val dstFile = File(ctx.filesDir, "qwen2.5-0.5b.gguf")
        if (dstFile.exists()) return true
        return try {
            val inputStream: InputStream = ctx.assets.open("qwen2.5-0.5b-instruct-q4_k_m.gguf")
            dstFile.outputStream().use { out ->
                inputStream.copyTo(out)
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * 加载模型
     */
    fun initModel(): Boolean {
        val modelFile = File(ctx.filesDir, "qwen2.5-0.5b.gguf")
        if (!modelFile.exists()) return false
        return loadModel(modelFile.absolutePath)
    }

    /**
     * 流式对话
     * 注意：streamChat是阻塞native调用，必须在IO协程里调用
     */
    fun chatStream(systemPrompt: String, userInput: String): Flow<String> = callbackFlow {
        tokenCallback = { token ->
            if (isActive) {
                trySend(token)
            }
        }
        // 阻塞native调用，推理结束后往下走
        streamChat(systemPrompt, userInput)
        tokenCallback = null
        close()
        awaitClose {
            // 协程取消时清理回调
            tokenCallback = null
        }
    }

    /**
     * 停止当前生成（native 里只置标志位，循环退出后流自然关闭）
     */
    fun stopGeneration() {
        stopGenerate()
    }

    fun destroy() {
        release()
        tokenCallback = null
    }
}

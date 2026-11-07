#include <jni.h>
#include <string>
#include <vector>
#include <algorithm>
#include <cstring>
#include <chrono>
#include <thread>
#include <cstdio>
#include <android/log.h>
#include "llama.h"

#define LOG_TAG "LLAMA_CPP"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

static llama_model* g_model = nullptr;
static llama_context* g_ctx = nullptr;
static jclass g_callback_cls = nullptr;
static jmethodID g_on_token_method = nullptr;
static bool g_stop_flag = false;
// 多轮对话历史（不含 system 段）。每轮生成完把"用户问 + 助手答"追加进来，
// 下一轮拼 prompt 时带上，模型才知道上下文。超过长度限制时整体清空重来
static std::string g_history;
static const int HISTORY_TOKEN_LIMIT = 1500; // n_ctx=2048，给回复留 500+ 余量

// 计算字符串中"完整 UTF-8 前缀"的长度。
// BPE 分词会把多字节字符（中文）拆成不完整的字节序列，
// 直接把半个字符喂给 NewStringUTF 会导致 JNI abort。
static size_t utf8_valid_prefix_len(const std::string & s) {
    size_t i = 0;
    size_t last_ok = 0;
    while (i < s.size()) {
        unsigned char c = (unsigned char) s[i];
        int len;
        if      ((c & 0x80) == 0x00) len = 1;
        else if ((c & 0xE0) == 0xC0) len = 2;
        else if ((c & 0xF0) == 0xE0) len = 3;
        else if ((c & 0xF8) == 0xF0) len = 4;
        else return last_ok; // 非法首字节
        if (i + len > s.size()) break; // 末尾是半个字符，等下一片拼上
        bool ok = true;
        for (int k = 1; k < len; k++) {
            if (((unsigned char) s[i + k] & 0xC0) != 0x80) { ok = false; break; }
        }
        if (!ok) return last_ok;
        i += len;
        last_ok = i;
    }
    return last_ok;
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_example_llamananodemo_LlamaChatManager_loadModel(
        JNIEnv *env,
        jobject thiz,
        jstring path_jstr) {
    const char* path = env->GetStringUTFChars(path_jstr, nullptr);

    llama_model_params m_params = llama_model_default_params();
    m_params.n_gpu_layers = 99;

    llama_context_params c_params = llama_context_default_params();
    c_params.n_ctx = 2048;
    // 线程数只取"大核"：小核（能效核）参与推理会拖慢整体，
    // 全部核心一起跑反而比只用大核慢（异构架构调度开销 + 小核算力弱）
    int n_threads = 4; // 检测失败时兜底
    {
        std::vector<long> freqs;
        for (int i = 0; i < 16; i++) {
            std::string p = "/sys/devices/system/cpu/cpu" + std::to_string(i) + "/cpufreq/cpuinfo_max_freq";
            FILE* f = fopen(p.c_str(), "r");
            if (!f) break;
            long khz = 0;
            if (fscanf(f, "%ld", &khz) == 1 && khz > 0) freqs.push_back(khz);
            fclose(f);
        }
        if (freqs.size() >= 4) {
            long top = *std::max_element(freqs.begin(), freqs.end());
            int big = 0;
            for (long fq : freqs) {
                if (fq * 10 >= top * 7) big++; // 最高频 70% 以上算大核
            }
        // 所有核报的频率都一样 → 检测无效（有些 SoC 就这么报），
        // 骁龙 8 Elite 这类 2 超大核 + 6 大核的机器，取 6 个线程效果最好
        if (big >= (int) freqs.size()) {
            big = std::max(4, (int) freqs.size() - 2);
        }
            if (big > 0) n_threads = std::min(big, 8);
        }
    }
    c_params.n_threads = n_threads;
    LOGD("推理线程数=%d (检测到 %u 个CPU)", n_threads, std::thread::hardware_concurrency());

    g_model = llama_model_load_from_file(path, m_params);
    env->ReleaseStringUTFChars(path_jstr, path);

    if (!g_model) {
        LOGD("模型加载失败");
        return JNI_FALSE;
    }
    g_ctx = llama_init_from_model(g_model, c_params);
    if (!g_ctx) {
        LOGD("创建ctx失败");
        llama_model_free(g_model);
        g_model = nullptr;
        return JNI_FALSE;
    }

    jclass cls = env->GetObjectClass(thiz);
    g_callback_cls = (jclass) env->NewGlobalRef(cls);
    g_on_token_method = env->GetMethodID(g_callback_cls, "onToken", "(Ljava/lang/String;)V");
    LOGD("模型加载成功");
    g_stop_flag = false;
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL Java_com_example_llamananodemo_LlamaChatManager_streamChat(
        JNIEnv *env,
        jobject thiz,
        jstring sys_prompt_jstr,
        jstring user_prompt_jstr) {
    if (!g_ctx || !g_model) return;
    g_stop_flag = false; // 停止信号只作用于当轮生成，不能让下一句话永远发不出来

    const char* sys_prompt = env->GetStringUTFChars(sys_prompt_jstr, nullptr);
    const char* user_prompt = env->GetStringUTFChars(user_prompt_jstr, nullptr);
    std::string system_part = std::string("<|im_start|>system\n") + sys_prompt + "<|im_end|>\n";
    std::string user_part = std::string("<|im_start|>user\n") + user_prompt + "<|im_end|>\n";
    env->ReleaseStringUTFChars(sys_prompt_jstr, sys_prompt);
    env->ReleaseStringUTFChars(user_prompt_jstr, user_prompt);

    const llama_vocab* vocab = llama_model_get_vocab(g_model);

    // 多轮 prompt：system + 历史对话 + 本轮 user + assistant 起始符。
    // 之前每轮只发"system + 当前一句"，上一轮说过什么模型完全不知道，
    // 表现就是"回答之间毫无联系"
    std::string prompt = system_part + g_history + user_part + "<|im_start|>assistant\n";

    // 第一次调用：tokens 传 nullptr 时 n_tokens_max 必须传 0，
    // 才会返回"负的所需 token 数"，否则内部会往空指针写数据直接 SIGSEGV
    int token_count = llama_tokenize(vocab, prompt.c_str(), (int)prompt.size(), nullptr, 0, true, true);
    if (token_count < 0) token_count = -token_count;
    // 历史太长快顶到 n_ctx 了：清空历史重新拼，宁可失忆也不能 decode 失败
    if (token_count > HISTORY_TOKEN_LIMIT) {
        LOGD("历史过长(%d tokens)，清空对话历史", token_count);
        g_history.clear();
        prompt = system_part + user_part + "<|im_start|>assistant\n";
        token_count = llama_tokenize(vocab, prompt.c_str(), (int)prompt.size(), nullptr, 0, true, true);
        if (token_count < 0) token_count = -token_count;
    }
    if (token_count <= 0) {
        LOGD("tokenize 失败");
        return;
    }
    std::vector<llama_token> tokens(token_count);
    llama_tokenize(vocab, prompt.c_str(), (int)prompt.size(), tokens.data(), token_count, true, true);

    llama_seq_id seq0 = {0};
    llama_batch batch = llama_batch_init(token_count, 0, 1);
    // 注意：这版 llama.cpp 的 llama_batch_init 只 malloc 不初始化，
    // n_seq_id / seq_id 必须手动填，否则 decode 内部读垃圾值直接崩溃
    for (int i = 0; i < token_count; i++) {
        batch.token[i] = tokens[i];
        batch.pos[i] = i;
        batch.n_seq_id[i] = 1;
        batch.seq_id[i][0] = seq0;
        batch.logits[i] = false;
    }
    batch.logits[token_count - 1] = true;
    batch.n_tokens = token_count;

    // 关键：清掉上一轮残留的 KV cache。
    // 不清的话第二轮 prompt 还是从 pos 0 写，cache 里的位置已被占用，
    // find_slot 失败 → llama_decode 返回非 0 → "prompt decode 失败"，只能回答一次
    llama_memory_clear(llama_get_memory(g_ctx), false);

    auto t_prompt = std::chrono::steady_clock::now();
    if (llama_decode(g_ctx, batch) != 0) {
        LOGD("prompt decode 失败");
        llama_batch_free(batch);
        return;
    }
    llama_batch_free(batch); // prompt batch 用完即释放
    {
        double ms = std::chrono::duration<double, std::milli>(
                std::chrono::steady_clock::now() - t_prompt).count();
        LOGD("prompt 预填充: %d tokens / %.1fms", token_count, ms);
    }

    int n_cur = token_count;
    int max_new_tokens = 512;
    llama_token eos_token = llama_vocab_eos(vocab);

    llama_batch batch_next = llama_batch_init(1, 0, 1);

    std::string pending; // 未拼成完整 UTF-8 的字节先攒着
    std::string reply_acc; // 本轮完整回复，生成完追加进 g_history
    auto last_flush = std::chrono::steady_clock::now();
    auto t_start = std::chrono::steady_clock::now();
    double dec_ms = 0; // 纯 llama_decode 耗时（不含采样/detokenize/回调）
    int n_generated = 0;

    for (int i = 0; i < max_new_tokens; i++) {
        if (g_stop_flag) {
            LOGD("收到停止信号");
            break;
        }

        // ========= 贪心采样：argmax 线性扫描。
        // 原来对整个词表（15万+）做全量排序，每个 token 纯浪费十几毫秒 =========
        float* logits_ptr = llama_get_logits(g_ctx);
        int n_vocab = llama_vocab_n_tokens(vocab);
        int best_id = 0;
        float best_logit = logits_ptr[0];
        for (int tid = 1; tid < n_vocab; tid++) {
            if (logits_ptr[tid] > best_logit) {
                best_logit = logits_ptr[tid];
                best_id = tid;
            }
        }
        llama_token new_token_id = best_id;

        if (new_token_id == eos_token) {
            break;
        }

        // ========= detokenize 单个 token =========
        std::vector<char> buf(1024);
        int ret = llama_detokenize(
                vocab,
                &new_token_id,
                1,
                buf.data(),
                (int) buf.size(),
                true,
                false
        );
        if (ret < 0) {
            buf.resize((size_t)(-ret) + 1); // 空间不够，按需要的长度重试一次
            ret = llama_detokenize(vocab, &new_token_id, 1, buf.data(), (int) buf.size(), true, false);
        }
        if (ret > 0) {
            // 双保险：就算 eos 没触发，模型开始自问自答/翻新轮次也立刻刹车
            std::string piece(buf.data(), (size_t) ret);
            if (piece.find("<|im_end|>") != std::string::npos ||
                piece.find("<|im_start|>") != std::string::npos ||
                piece.find("用户：") != std::string::npos ||
                piece.find("用户:") != std::string::npos) {
                LOGD("检测到模型翻轮次，提前刹车");
                break;
            }
            pending.append(buf.data(), (size_t) ret);
            reply_acc.append(buf.data(), (size_t) ret);
            // 节流：每 50ms 回调一次 JNI/Kotlin，而不是每个 token 一次，
            // 大幅减少跨层调用和 UI 重组次数；首个 token 立即吐，保证首字响应
            auto now = std::chrono::steady_clock::now();
            bool due = (i == 0) ||
                std::chrono::duration_cast<std::chrono::milliseconds>(now - last_flush).count() >= 50;
            if (due) {
                size_t n = utf8_valid_prefix_len(pending);
                if (n > 0) {
                    jstring j_piece = env->NewStringUTF(pending.substr(0, n).c_str());
                    env->CallVoidMethod(thiz, g_on_token_method, j_piece);
                    env->DeleteLocalRef(j_piece);
                    pending.erase(0, n);
                }
                last_flush = now;
            }
        }

        // 填充下一个batch（batch_next 复用，循环外统一释放，避免 double free）
        batch_next.token[0] = new_token_id;
        batch_next.pos[0] = n_cur;
        batch_next.n_seq_id[0] = 1;
        batch_next.seq_id[0][0] = seq0;
        batch_next.logits[0] = true;
        batch_next.n_tokens = 1;

        auto t_dec = std::chrono::steady_clock::now();
        if (llama_decode(g_ctx, batch_next) != 0) {
            LOGD("生成token decode失败");
            break;
        }
        dec_ms += std::chrono::duration<double, std::milli>(
                std::chrono::steady_clock::now() - t_dec).count();
        n_generated++;
        n_cur += 1;
    }
    llama_batch_free(batch_next);

    // 吞吐统计：adb logcat -s LLAMA_CPP 里看 tok/s
    {
        double secs = std::chrono::duration<double>(std::chrono::steady_clock::now() - t_start).count();
        if (secs > 0.01) {
            double avg_dec = n_generated > 0 ? dec_ms / n_generated : 0;
            LOGD("生成统计: %d tokens / %.2fs = %.1f tok/s (纯decode均值 %.1fms/token)",
                 n_generated, secs, n_generated / secs, avg_dec);
        }
    }

    // 收尾：把残余字节吐出去（尽力而为）
    if (!pending.empty()) {
        jstring j_piece = env->NewStringUTF(pending.c_str());
        env->CallVoidMethod(thiz, g_on_token_method, j_piece);
        env->DeleteLocalRef(j_piece);
    }

    // 本轮问答追加进历史，下一轮带上（加了 <|im_end|>，模型知道这轮结束了）
    g_history += user_part + "<|im_start|>assistant\n" + reply_acc + "<|im_end|>\n";
}

extern "C" JNIEXPORT void JNICALL Java_com_example_llamananodemo_LlamaChatManager_stopGenerate(
        JNIEnv *env, jobject thiz) {
    g_stop_flag = true;
}

extern "C" JNIEXPORT void JNICALL Java_com_example_llamananodemo_LlamaChatManager_release(JNIEnv *env, jobject thiz) {
    g_stop_flag = true;
    g_history.clear();
    if (g_ctx) {
        llama_free(g_ctx);
        g_ctx = nullptr;
    }
    if (g_model) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
    if (g_callback_cls) {
        env->DeleteGlobalRef(g_callback_cls);
        g_callback_cls = nullptr;
    }
    LOGD("释放模型资源");
}

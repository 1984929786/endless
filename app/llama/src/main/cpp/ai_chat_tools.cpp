// ai_chat_tools.cpp —— 给内嵌 llama.cpp 开工具调用（function calling）
//
// 为什么单独一个文件：ai_chat.cpp 是 llama.cpp 上游 example 的逐 token 流式实现，
// 它维护自己的 chat_msgs 状态机，不适合塞工具调用。这里另起一个「整段生成」入口，
// 直接走 llama.cpp 的 common_chat_* 全家桶：
//   common_chat_templates_apply(tools)  → 拼带工具说明的 prompt
//   common_chat_parse(generation)       → 解析出 content / tool_calls
//   msg.to_json_oaicompat()            → 直接出 OpenAI 兼容 JSON
//
// 判据：不自己写 JSON 解析器和模板拼接，一律用上游实现（SOUL.md 工程纪律五条）。

#include <android/log.h>
#include <jni.h>
#include <string>
#include <vector>
#include <chrono>

#include "chat.h"
#include "common.h"
#include "llama.h"
#include "logging.h"
// common_sampler_init / common_sampler_sample / common_sampler_free 声明在 sampling.h。
// ai_chat.cpp 有这行，我刚开始漏了 → CI 报 use of undeclared identifier 'common_sampler_init'。
#include <sampling.h>

// ---- 复用 ai_chat.cpp 里的全局状态（同进程同库，用 extern 取） ----
extern llama_model *g_model;
extern llama_context *g_context;
extern common_chat_templates_ptr g_chat_templates;

namespace {

// JSON 字符串转义（只处理必须的，避免引入依赖）
std::string jsonEscape(const std::string &s) {
    std::string o;
    o.reserve(s.size() + 16);
    for (unsigned char c : s) {
        switch (c) {
            case '"':  o += "\\\""; break;
            case '\\': o += "\\\\"; break;
            case '\n': o += "\\n";  break;
            case '\r': o += "\\r";  break;
            case '\t': o += "\\t";  break;
            case '\b': o += "\\b";  break;
            case '\f': o += "\\f";  break;
            default:
                if (c < 0x20) {
                    char buf[8];
                    snprintf(buf, sizeof(buf), "\\u%04x", c);
                    o += buf;
                } else {
                    o += (char) c;
                }
        }
    }
    return o;
}

// 简易 JSON 数组/对象扫描：从 JS 侧传来的 tools 已经保证是合法 JSON，
// 这里只按顶层 `{...}` 切分，避免引入 common_json 的编译期依赖。
std::vector<std::string> splitTopLevelObjects(const std::string &json) {
    std::vector<std::string> out;
    int depth = 0;
    bool inStr = false, esc = false;
    size_t start = std::string::npos;
    for (size_t i = 0; i < json.size(); i++) {
        char c = json[i];
        if (esc) { esc = false; continue; }
        if (c == '\\' && inStr) { esc = true; continue; }
        if (c == '"') { inStr = !inStr; continue; }
        if (inStr) continue;
        if (c == '{') {
            if (depth == 0) start = i;
            depth++;
        } else if (c == '}') {
            depth--;
            if (depth == 0 && start != std::string::npos) {
                out.push_back(json.substr(start, i - start + 1));
                start = std::string::npos;
            }
        }
    }
    return out;
}

// 从 "name": "xxx" 这种扁平字段里取值（工具名/描述都是扁平字符串）
std::string extractFlatString(const std::string &obj, const std::string &key) {
    const std::string pat = "\"" + key + "\"";
    size_t p = obj.find(pat);
    if (p == std::string::npos) return "";
    p = obj.find(':', p + pat.size());
    if (p == std::string::npos) return "";
    p++;
    while (p < obj.size() && isspace((unsigned char) obj[p])) p++;
    if (p >= obj.size() || obj[p] != '"') return "";
    p++;
    std::string out;
    bool esc = false;
    for (; p < obj.size(); p++) {
        char c = obj[p];
        if (esc) {
            switch (c) {
                case 'n': out += '\n'; break;
                case 't': out += '\t'; break;
                case 'r': out += '\r'; break;
                case '"': out += '"';  break;
                case '\\': out += '\\'; break;
                case '/': out += '/';  break;
                default: out += c;
            }
            esc = false;
            continue;
        }
        if (c == '\\') { esc = true; continue; }
        if (c == '"') break;
        out += c;
    }
    return out;
}

// 取 "parameters": {...} 的原始 JSON 文本
std::string extractRawObject(const std::string &obj, const std::string &key) {
    const std::string pat = "\"" + key + "\"";
    size_t p = obj.find(pat);
    if (p == std::string::npos) return "";
    p = obj.find(':', p + pat.size());
    if (p == std::string::npos) return "";
    p++;
    while (p < obj.size() && isspace((unsigned char) obj[p])) p++;
    if (p >= obj.size() || obj[p] != '{') return "";
    int depth = 0;
    bool inStr = false, esc = false;
    size_t start = p;
    for (; p < obj.size(); p++) {
        char c = obj[p];
        if (esc) { esc = false; continue; }
        if (c == '\\' && inStr) { esc = true; continue; }
        if (c == '"') { inStr = !inStr; continue; }
        if (inStr) continue;
        if (c == '{') depth++;
        else if (c == '}') {
            depth--;
            if (depth == 0) return obj.substr(start, p - start + 1);
        }
    }
    return "";
}

} // namespace

extern "C"
JNIEXPORT jstring JNICALL
Java_com_xundao_yongwuzhijing_LlamaToolEngine_nativeGenerateWithTools(
        JNIEnv *env,
        jobject /*unused*/,
        jstring jmessages,   // [{"role":"user","content":"..."}, ...] 原始 OpenAI 消息数组
        jstring jtools,      // [{"type":"function","function":{...}}] 可为 "[]" 或 null
        jint nPredict
) {
    auto fail = [&](const std::string &msg) -> jstring {
        std::string j = "{\"error\":{\"message\":\"" + jsonEscape(msg) + "\",\"type\":\"inference_error\"}}";
        return env->NewStringUTF(j.c_str());
    };

    if (g_model == nullptr || g_context == nullptr || g_chat_templates == nullptr) {
        return fail("引擎未加载");
    }

    const auto *msgsC = env->GetStringUTFChars(jmessages, nullptr);
    std::string msgsJson(msgsC ? msgsC : "");
    env->ReleaseStringUTFChars(jmessages, msgsC);

    std::string toolsJson;
    if (jtools != nullptr) {
        const auto *tC = env->GetStringUTFChars(jtools, nullptr);
        toolsJson = tC ? tC : "";
        env->ReleaseStringUTFChars(jtools, tC);
    }

    if (msgsJson.empty()) return fail("消息为空");

    // ---- 1. 解析消息 ----
    // 顶层消息对象按 { 拆分；每条取 role/content，tool 消息额外取 tool_call_id/name
    std::vector<common_chat_msg> messages;
    for (const auto &raw : splitTopLevelObjects(msgsJson)) {
        common_chat_msg m;
        m.role = extractFlatString(raw, "role");
        m.content = extractFlatString(raw, "content");
        m.tool_call_id = extractFlatString(raw, "tool_call_id");
        m.tool_name = extractFlatString(raw, "name");
        if (m.role.empty()) continue;
        messages.push_back(m);
    }
    if (messages.empty()) return fail("没有可用的消息");

    // ---- 2. 解析工具定义 ----
    std::vector<common_chat_tool> tools;
    for (const auto &raw : splitTopLevelObjects(toolsJson)) {
        common_chat_tool t;
        t.name = extractFlatString(raw, "name");
        t.description = extractFlatString(raw, "description");
        t.parameters = extractRawObject(raw, "parameters");
        if (t.parameters.empty()) t.parameters = R"({"type":"object","properties":{}})";
        if (!t.name.empty()) tools.push_back(t);
    }
    LOGi("generateWithTools: %d 条消息, %d 个工具", (int) messages.size(), (int) tools.size());

    // ---- 3. 拼 prompt ----
    common_chat_params params;
    try {
        common_chat_templates_inputs in;
        in.messages = messages;
        in.add_generation_prompt = true;
        in.use_jinja = true;
        in.enable_thinking = false;   // 同 ai_chat.cpp：不关思维会被 <think> 吃光额度
        if (!tools.empty()) {
            in.tools = tools;
            in.tool_choice = COMMON_CHAT_TOOL_CHOICE_AUTO;
        }
        params = common_chat_templates_apply(g_chat_templates.get(), in);
    } catch (const std::exception &e) {
        return fail(std::string("模板渲染失败: ") + e.what());
    }

    const std::string prompt = params.prompt.str();

    // ---- 4. 生成 ----
    std::string generated;
    try {
        common_params_sampling sp;
        auto *smpl = common_sampler_init(g_model, sp);
        if (smpl == nullptr) return fail("采样器初始化失败");

        // 每轮清空 KV 再整段喂，避免脏状态（同 llama.cpp server 的做法）
        llama_memory_clear(llama_get_memory(g_context), false);

        auto toks = common_tokenize(g_context, prompt, true, true);
        const int nCtx = (int) llama_n_ctx(g_context);
        if ((int) toks.size() >= nCtx - 8) {
            int keep = nCtx - 8 - (int) toks.size();
            if (keep < 0) toks.resize(nCtx - 8);  // 超长直接截断（简单策略）
        }

        common_batch batch(g_context);
        const int BATCH = 512;
        for (int i = 0; i < (int) toks.size(); i += BATCH) {
            const int n = std::min(BATCH, (int) toks.size() - i);
            batch.clear();
            for (int k = 0; k < n; k++) {
                // 只给最后一个 token 要 logits，其它不要
                batch.add(toks[i + k], i + k, 0, k == n - 1);
            }
            if (llama_process(g_context, LLAMA_PROCESS_TYPE_DECODE, batch.get()) != 0) {
                common_sampler_free(smpl);
                return fail("prompt 解码失败");
            }
        }

        int pos = (int) toks.size();
        std::string cached;
        for (int step = 0; step < nPredict; step++) {
            const auto id = common_sampler_sample(smpl, g_context, -1);
            if (llama_vocab_is_eog(llama_model_get_vocab(g_model), id)) break;
            common_sampler_accept(smpl, id, true);

            batch.clear();
            batch.add(id, pos, 0, true);
            if (llama_process(g_context, LLAMA_PROCESS_TYPE_DECODE, batch.get()) != 0) break;
            pos++;

            cached += common_token_to_piece(g_context, id);
            // 只收完整 UTF-8
            if (cached.size() > 4) {
                generated += cached;
                cached.clear();
            }
        }
        if (!cached.empty()) generated += cached;
        common_sampler_free(smpl);
    } catch (const std::exception &e) {
        return fail(std::string("推理失败: ") + e.what());
    }

    LOGi("generateWithTools: 生成 %d 字节", (int) generated.size());

    // ---- 5. 解析成 content / tool_calls ----
    common_chat_msg result;
    try {
        common_chat_input input(generated);
        common_chat_parser_params pp(params);
        result = common_chat_parse(input, /* is_partial */ false, pp);
    } catch (const std::exception &e) {
        LOGw("解析失败，按纯文本返回: %s", e.what());
        result.content = generated;
    }

    // ---- 6. 输出 OpenAI 兼容 JSON ----
    std::string out = "{\"content\":\"" + jsonEscape(result.content) + "\",\"tool_calls\":[";
    for (size_t i = 0; i < result.tool_calls.size(); i++) {
        const auto &tc = result.tool_calls[i];
        if (i) out += ",";
        out += "{\"id\":\"" + jsonEscape(tc.id.empty() ? ("call_" + std::to_string(i)) : tc.id) + "\",";
        out += "\"type\":\"function\",\"function\":{";
        out += "\"name\":\"" + jsonEscape(tc.name) + "\",";
        out += "\"arguments\":\"" + jsonEscape(tc.arguments) + "\"}}";
    }
    out += "]}";

    return env->NewStringUTF(out.c_str());
}

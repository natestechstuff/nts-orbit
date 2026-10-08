// Jarvis on-device brain: a thin, dependency-free layer over llama.cpp.
// Used by the Android JNI bridge (jarvis_jni.cpp) and by the desktop smoke test
// (android/native-test/smoke.cpp), so the exact same prompt + generation code is
// tested on a PC before it ever runs on the phone.
#pragma once

#include <atomic>
#include <functional>
#include <string>
#include <vector>

struct llama_model;
struct llama_context;
struct llama_sampler;
struct llama_vocab;

namespace jarvis {

struct Message {
    std::string role;     // "system" | "user" | "assistant"
    std::string content;
};

struct GenStats {
    int    prompt_tokens  = 0;  // tokens in the full prompt
    int    reused_tokens  = 0;  // prompt tokens served from the warm KV cache
    int    gen_tokens     = 0;  // tokens generated
    double prompt_ms      = 0;  // time to read the (new part of the) prompt
    double gen_ms         = 0;  // time spent generating
    bool   stopped        = false; // stop button / abort
    bool   hit_limit      = false; // max_tokens reached
    int    dropped_turns  = 0;  // old turns trimmed to fit the context
    std::string error;          // empty on success
    double gen_tps() const { return gen_ms > 0 ? gen_tokens * 1000.0 / gen_ms : 0; }
    double prompt_tps() const { int n = prompt_tokens - reused_tokens; return prompt_ms > 0 ? n * 1000.0 / prompt_ms : 0; }
};

struct SampleParams {
    float temperature    = 0.7f;
    int   top_k          = 40;
    float top_p          = 0.9f;
    float min_p          = 0.05f;
    float repeat_penalty = 1.1f;
    int   repeat_last_n  = 64;
    unsigned seed        = 0xFFFFFFFFu;  // LLAMA_DEFAULT_SEED (random)
};

// Qwen2.5 chat template (ChatML):
//   <|im_start|>system\n...<|im_end|>\n<|im_start|>user\n...<|im_end|>\n<|im_start|>assistant\n
std::string chatml(const std::vector<Message> & msgs, bool add_generation_prompt = true);

// Holds incomplete UTF-8 sequences so callers only ever see whole characters.
class Utf8Buffer {
public:
    // Append raw bytes; returns the longest prefix that is complete UTF-8.
    std::string push(const std::string & bytes);
    std::string flush();   // whatever is left (may be partial)
private:
    std::string pending_;
};

class Engine {
public:
    using TokenCallback = std::function<bool(const std::string & piece)>;  // return false to stop

    Engine();
    ~Engine();
    Engine(const Engine &) = delete;
    Engine & operator=(const Engine &) = delete;

    // Loads a GGUF file. n_threads <= 0 picks a sensible default. Returns "" or an error.
    std::string load(const std::string & path, int n_ctx, int n_threads);
    void unload();
    bool loaded() const { return ctx_ != nullptr; }

    // Generates the assistant reply for `msgs` (system first). Streams whole-UTF-8 pieces.
    // Reuses the KV cache for the shared prefix with the previous call (keeps voice replies fast).
    GenStats generate(const std::vector<Message> & msgs, int max_tokens,
                      const SampleParams & sp, const TokenCallback & on_piece,
                      std::string * full_reply = nullptr);

    void request_stop() { stop_.store(true); }
    std::string describe() const;   // model name, params, context, threads
    int n_ctx() const;
    int n_threads() const { return n_threads_; }

private:
    std::vector<int> tokenize(const std::string & text) const;
    std::string piece(int token) const;
    void reset_cache();

    llama_model *   model_ = nullptr;
    llama_context * ctx_   = nullptr;
    const llama_vocab * vocab_ = nullptr;
    std::vector<int> cached_;          // tokens currently in the KV cache (seq 0)
    std::atomic<bool> stop_{false};
    int n_threads_ = 4;
    std::string path_;
};

}  // namespace jarvis

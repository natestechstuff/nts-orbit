#include "jarvis_llm.h"

#include "llama.h"

#include <algorithm>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <thread>

namespace jarvis {

static double now_ms() {
    using namespace std::chrono;
    return duration<double, std::milli>(steady_clock::now().time_since_epoch()).count();
}

std::string chatml(const std::vector<Message> & msgs, bool add_generation_prompt) {
    std::string out;
    for (const auto & m : msgs) {
        out += "<|im_start|>";
        out += m.role;
        out += "\n";
        out += m.content;
        out += "<|im_end|>\n";
    }
    if (add_generation_prompt) out += "<|im_start|>assistant\n";
    return out;
}

// ---------------------------------------------------------------- UTF-8

static size_t complete_prefix_len(const std::string & s) {
    // Walk back over at most 3 trailing bytes looking for an unfinished lead byte.
    size_t n = s.size();
    size_t i = n;
    int back = 0;
    while (i > 0 && back < 4) {
        unsigned char c = (unsigned char) s[i - 1];
        if ((c & 0xC0) == 0x80) { i--; back++; continue; }   // continuation byte
        int need = 1;
        if ((c & 0x80) == 0x00) need = 1;
        else if ((c & 0xE0) == 0xC0) need = 2;
        else if ((c & 0xF0) == 0xE0) need = 3;
        else if ((c & 0xF8) == 0xF0) need = 4;
        size_t have = n - (i - 1);
        return have >= (size_t) need ? n : i - 1;
    }
    return n;
}

std::string Utf8Buffer::push(const std::string & bytes) {
    pending_ += bytes;
    size_t k = complete_prefix_len(pending_);
    std::string out = pending_.substr(0, k);
    pending_.erase(0, k);
    return out;
}

std::string Utf8Buffer::flush() {
    std::string out;
    out.swap(pending_);
    return out;
}

// ---------------------------------------------------------------- engine

static bool abort_cb(void * data) {
    return ((std::atomic<bool> *) data)->load();
}

static void quiet_log(enum ggml_log_level level, const char * text, void *) {
    if (level >= GGML_LOG_LEVEL_ERROR) fputs(text, stderr);
}

Engine::Engine() {
    static bool inited = false;
    if (!inited) {
        llama_log_set(quiet_log, nullptr);
        llama_backend_init();
        inited = true;
    }
}

Engine::~Engine() { unload(); }

void Engine::unload() {
    if (ctx_)   { llama_free(ctx_); ctx_ = nullptr; }
    if (model_) { llama_model_free(model_); model_ = nullptr; }
    vocab_ = nullptr;
    cached_.clear();
}

std::string Engine::load(const std::string & path, int n_ctx, int n_threads, int n_batch) {
    unload();
    path_ = path;
    if (n_threads <= 0) {
        unsigned hc = std::thread::hardware_concurrency();
        n_threads = (int) std::max(1u, std::min(4u, hc ? hc : 4u));
    }
    n_threads_ = n_threads;

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;     // CPU only on the phone
    // default load mode memory-maps the file, so model pages come straight from storage
    model_ = llama_model_load_from_file(path.c_str(), mp);
    if (!model_) return "couldn't load that model file (is it a valid .gguf?)";
    vocab_ = llama_model_get_vocab(model_);

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = (uint32_t) std::max(512, n_ctx);
    if (n_batch <= 0) n_batch = 512;
    cp.n_batch = (uint32_t) n_batch;
    cp.n_ubatch = (uint32_t) n_batch;
    cp.n_threads = n_threads;
    cp.n_threads_batch = n_threads;
    cp.abort_callback = abort_cb;
    cp.abort_callback_data = &stop_;
    cp.no_perf = true;
    ctx_ = llama_init_from_model(model_, cp);
    if (!ctx_) { unload(); return "model loaded but the context didn't fit in memory (try a smaller context)"; }
    return "";
}

std::string Engine::metadata() const {
    if (!model_) return "";
    std::string out;
    int n = llama_model_meta_count(model_);
    char key[256], val[512];
    for (int i = 0; i < n; i++) {
        if (llama_model_meta_key_by_index(model_, i, key, sizeof(key)) < 0) continue;
        int vl = llama_model_meta_val_str_by_index(model_, i, val, sizeof(val));
        if (vl < 0) continue;
        std::string v(val);
        if (vl >= (int) sizeof(val)) v += "…";
        out += key; out += " = "; out += v; out += "\n";
    }
    char buf[256];
    snprintf(buf, sizeof(buf), "n_params = %llu\nsize_bytes = %llu\nn_ctx_train = %d\nn_embd = %d\nn_layer = %d\nn_vocab = %d\n",
             (unsigned long long) llama_model_n_params(model_), (unsigned long long) llama_model_size(model_),
             llama_model_n_ctx_train(model_), llama_model_n_embd(model_), llama_model_n_layer(model_),
             llama_vocab_n_tokens(vocab_));
    out += buf;
    return out;
}

int Engine::n_ctx() const { return ctx_ ? (int) llama_n_ctx(ctx_) : 0; }

std::string Engine::describe() const {
    if (!model_) return "no model";
    char desc[256] = {0};
    llama_model_desc(model_, desc, sizeof(desc));
    char buf[512];
    snprintf(buf, sizeof(buf), "%s · %.0f MB · ctx %d · %d threads",
             desc, llama_model_size(model_) / 1048576.0, n_ctx(), n_threads_);
    return buf;
}

std::vector<int> Engine::tokenize(const std::string & text) const {
    // ChatML markers are special tokens; BOS is not used by Qwen2.5.
    int n = -llama_tokenize(vocab_, text.c_str(), (int) text.size(), nullptr, 0, false, true);
    std::vector<llama_token> toks(std::max(0, n));
    if (n > 0) llama_tokenize(vocab_, text.c_str(), (int) text.size(), toks.data(), n, false, true);
    return std::vector<int>(toks.begin(), toks.end());
}

std::string Engine::piece(int token) const {
    char buf[256];
    int n = llama_token_to_piece(vocab_, token, buf, sizeof(buf), 0, /*special*/ false);
    if (n < 0) {
        std::string big((size_t) -n, '\0');
        n = llama_token_to_piece(vocab_, token, &big[0], (int) big.size(), 0, false);
        return n > 0 ? big.substr(0, n) : std::string();
    }
    return std::string(buf, n);
}

void Engine::reset_cache() {
    llama_memory_clear(llama_get_memory(ctx_), true);
    cached_.clear();
}

GenStats Engine::generate(const std::vector<Message> & msgs_in, int max_tokens,
                          const SampleParams & sp, const TokenCallback & on_piece,
                          std::string * full_reply) {
    GenStats st;
    stop_.store(false);
    if (!ctx_) { st.error = "no model loaded"; return st; }
    const int n_ctx = (int) llama_n_ctx(ctx_);
    max_tokens = std::max(16, std::min(max_tokens, n_ctx / 2));

    // Build the prompt; drop the oldest turns (never the system prompt or the newest
    // user message) until prompt + reply fit in the context.
    std::vector<Message> msgs = msgs_in;
    std::vector<int> toks;
    while (true) {
        toks = tokenize(chatml(msgs, true));
        if ((int) toks.size() + max_tokens <= n_ctx) break;
        size_t first = (!msgs.empty() && msgs[0].role == "system") ? 1 : 0;
        if (msgs.size() <= first + 1) {
            // Only the newest message left and it is still too long: keep its tail.
            int keep = n_ctx - max_tokens - 8;
            if (keep < 32) { st.error = "message too long for the context"; return st; }
            std::vector<int> tail(toks.end() - keep, toks.end());
            toks = tail;
            break;
        }
        msgs.erase(msgs.begin() + first);
        st.dropped_turns++;
    }
    st.prompt_tokens = (int) toks.size();

    // Reuse the KV cache for the common prefix (system prompt + earlier turns).
    size_t common = 0;
    while (common < cached_.size() && common < toks.size() && cached_[common] == toks[common]) common++;
    if (common == toks.size() && common > 0) common--;   // must decode at least one token for logits
    llama_memory_t mem = llama_get_memory(ctx_);
    if (common < cached_.size()) {
        if (!llama_memory_seq_rm(mem, 0, (llama_pos) common, -1)) { reset_cache(); common = 0; }
        else cached_.resize(common);
    }
    st.reused_tokens = (int) common;

    // Prompt.
    double t0 = now_ms();
    for (size_t i = common; i < toks.size(); i += 512) {
        int n = (int) std::min<size_t>(512, toks.size() - i);
        llama_batch b = llama_batch_get_one((llama_token *) &toks[i], n);
        int rc = llama_decode(ctx_, b);
        if (rc != 0) {
            st.stopped = stop_.load();
            if (!st.stopped) st.error = "decode failed (" + std::to_string(rc) + ")";
            reset_cache();
            return st;
        }
        cached_.insert(cached_.end(), toks.begin() + i, toks.begin() + i + n);
    }
    st.prompt_ms = now_ms() - t0;

    // Sampler chain.
    llama_sampler * smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (sp.repeat_penalty != 1.0f && sp.repeat_last_n > 0)
        llama_sampler_chain_add(smpl, llama_sampler_init_penalties(llama_vocab_n_tokens(vocab_),
                                    sp.repeat_last_n, sp.repeat_penalty, 0.0f, 0.0f));
    if (sp.temperature <= 0.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    } else {
        if (sp.top_k > 0)    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(sp.top_k));
        if (sp.top_p < 1.0f) llama_sampler_chain_add(smpl, llama_sampler_init_top_p(sp.top_p, 1));
        if (sp.min_p > 0.0f) llama_sampler_chain_add(smpl, llama_sampler_init_min_p(sp.min_p, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(sp.temperature));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(sp.seed));
    }

    Utf8Buffer utf8;
    std::string reply;
    double t1 = now_ms();
    for (int i = 0; i < max_tokens; i++) {
        if (stop_.load()) { st.stopped = true; break; }
        llama_token tok = llama_sampler_sample(smpl, ctx_, -1);
        if (llama_vocab_is_eog(vocab_, tok)) break;
        st.gen_tokens++;
        std::string p = utf8.push(piece(tok));
        // Belt and braces: if a fine-tune forgets to emit <|im_end|> as a token.
        reply += p;
        size_t marker = reply.find("<|im_");
        if (marker != std::string::npos) { reply.erase(marker); break; }
        if (!p.empty() && on_piece && !on_piece(p)) { st.stopped = true; break; }
        if ((int) cached_.size() + 1 >= n_ctx) { st.hit_limit = true; break; }
        llama_batch b = llama_batch_get_one(&tok, 1);
        int rc = llama_decode(ctx_, b);
        if (rc != 0) {
            st.stopped = stop_.load();
            if (!st.stopped) st.error = "decode failed (" + std::to_string(rc) + ")";
            reset_cache();
            break;
        }
        cached_.push_back(tok);
        if (i == max_tokens - 1) st.hit_limit = true;
    }
    std::string rest = utf8.flush();
    if (!rest.empty()) { reply += rest; if (on_piece) on_piece(rest); }
    st.gen_ms = now_ms() - t1;
    llama_sampler_free(smpl);
    // The generated reply sits in the cache without its closing <|im_end|>; the next turn's
    // prompt re-adds it, so the prefix match simply stops there and decodes from that point.
    if (full_reply) *full_reply = reply;
    return st;
}

}  // namespace jarvis

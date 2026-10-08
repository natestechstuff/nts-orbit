package com.natestechstuff.jarvis;

import static org.junit.Assert.*;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Ollama + OpenAI-compatible client: parsing, streaming against a real local mock server, errors. */
public class ServerClientTest {

    private static List<ServerClient.Msg> hi() {
        List<ServerClient.Msg> m = new ArrayList<>();
        m.add(new ServerClient.Msg("system", "be brief"));
        m.add(new ServerClient.Msg("user", "hi"));
        return m;
    }

    // ------------------------------------------------------------ urls

    @Test public void normalizeBase() {
        assertEquals("http://192.168.1.20:11434", ServerClient.normalizeBase("192.168.1.20:11434"));
        assertEquals("http://192.168.1.20:11434", ServerClient.normalizeBase(" http://192.168.1.20:11434/ "));
        assertEquals("http://localhost:1234", ServerClient.normalizeBase("http://localhost:1234/v1"));
        assertEquals("http://localhost:1234", ServerClient.normalizeBase("http://localhost:1234/v1/chat/completions"));
        assertEquals("http://box:11434", ServerClient.normalizeBase("box:11434/api/chat"));
        assertEquals("https://ai.example.ts.net", ServerClient.normalizeBase("https://ai.example.ts.net/"));
        assertEquals("http://h:5000/proxy", ServerClient.normalizeBase("h:5000/proxy/v1/"));
        assertEquals("", ServerClient.normalizeBase("  "));
        assertEquals("192.168.1.20:11434", ServerClient.hostPort("http://192.168.1.20:11434/v1"));
    }

    @Test public void presetPorts() {
        assertEquals("192.168.1.20:11434", ServerSettingsActivity.withPort("192.168.1.20", "11434"));
        assertEquals("192.168.1.20:1234", ServerSettingsActivity.withPort("192.168.1.20:11434", "1234"));
        assertEquals("http://pc.local:8080", ServerSettingsActivity.withPort("http://pc.local:5000/v1", "8080"));
        assertEquals(":11434", ServerSettingsActivity.withPort("", "11434"));
        assertEquals("[fd7a::1]:1234", ServerSettingsActivity.withPort("[fd7a::1]:11434", "1234"));
    }

    // ------------------------------------------------------------ model lists

    @Test public void ollamaTags() throws Exception {
        String json = "{\"models\":[{\"name\":\"qwen2.5:0.5b\",\"model\":\"qwen2.5:0.5b\",\"size\":397821319,\"details\":{\"family\":\"qwen2\"}},"
                + "{\"name\":\"llama3.2:latest\",\"model\":\"llama3.2:latest\"},{\"model\":\"Gemma3:1b\"}]}";
        assertEquals(Arrays.asList("Gemma3:1b", "llama3.2:latest", "qwen2.5:0.5b"), ServerClient.parseOllamaTags(json));
        assertTrue(ServerClient.parseOllamaTags("{\"models\":[]}").isEmpty());
        try { ServerClient.parseOllamaTags("<html>nope</html>"); fail(); } catch (ServerClient.WrongApi expected) {}
        try { ServerClient.parseOllamaTags("{\"data\":[]}"); fail(); } catch (ServerClient.WrongApi expected) {}
    }

    @Test public void openAiModels() throws Exception {
        // LM Studio / vLLM / LocalAI
        assertEquals(Arrays.asList("qwen2.5-7b-instruct", "text-embedding-nomic-embed-text-v1.5"), ServerClient.parseOpenAiModels(
                "{\"object\":\"list\",\"data\":[{\"id\":\"qwen2.5-7b-instruct\",\"object\":\"model\",\"owned_by\":\"organization_owner\"},"
                        + "{\"id\":\"text-embedding-nomic-embed-text-v1.5\",\"object\":\"model\"}]}"));
        // llama.cpp server (also sends a "models" array)
        assertEquals(Collections.singletonList("Qwen2.5-1.5B-Instruct-Q4_K_M.gguf"), ServerClient.parseOpenAiModels(
                "{\"models\":[{\"name\":\"Qwen2.5-1.5B-Instruct-Q4_K_M.gguf\",\"model\":\"Qwen2.5-1.5B-Instruct-Q4_K_M.gguf\"}],"
                        + "\"object\":\"list\",\"data\":[{\"id\":\"Qwen2.5-1.5B-Instruct-Q4_K_M.gguf\",\"object\":\"model\"}]}"));
        try { ServerClient.parseOpenAiModels("not json"); fail(); } catch (ServerClient.WrongApi expected) {}
    }

    // ------------------------------------------------------------ stream parsing

    @Test public void ollamaLines() throws Exception {
        ServerClient.OllamaChunk a = ServerClient.parseOllamaLine("{\"model\":\"qwen2.5:0.5b\",\"created_at\":\"2026-10-07T21:00:00Z\",\"message\":{\"role\":\"assistant\",\"content\":\"Hel\"},\"done\":false}");
        assertEquals("Hel", a.content);
        assertFalse(a.done);
        ServerClient.OllamaChunk d = ServerClient.parseOllamaLine("{\"model\":\"m\",\"message\":{\"role\":\"assistant\",\"content\":\"\"},\"done\":true,\"done_reason\":\"stop\",\"eval_count\":12,\"eval_duration\":400000000}");
        assertTrue(d.done);
        assertEquals(12, d.evalCount);
        assertEquals(400_000_000L, d.evalDurationNs);
        ServerClient.OllamaChunk t = ServerClient.parseOllamaLine("{\"message\":{\"role\":\"assistant\",\"content\":\"\",\"thinking\":\"hmm\"},\"done\":false}");
        assertEquals("", t.content);
        assertEquals("hmm", t.thinking);
        assertEquals("model 'x' not found", ServerClient.parseOllamaLine("{\"error\":\"model 'x' not found\"}").error);
        try { ServerClient.parseOllamaLine("garbage"); fail(); } catch (IOException expected) {}
    }

    @Test public void sseLines() throws Exception {
        assertNull(ServerClient.parseSseLine(""));
        assertNull(ServerClient.parseSseLine(": keep-alive"));
        assertNull(ServerClient.parseSseLine("event: message"));
        assertTrue(ServerClient.parseSseLine("data: [DONE]").done);
        ServerClient.SseChunk role = ServerClient.parseSseLine("data: {\"id\":\"c1\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":null},\"finish_reason\":null}]}");
        assertEquals("", role.content);
        assertEquals("Hi", ServerClient.parseSseLine("data:{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Hi\"}}]}").content);
        assertEquals(" there", ServerClient.parseSseLine("data: {\"choices\":[{\"delta\":{\"content\":\" there\"},\"finish_reason\":null}]}").content);
        ServerClient.SseChunk usage = ServerClient.parseSseLine("data: {\"choices\":[],\"usage\":{\"prompt_tokens\":9,\"completion_tokens\":7,\"total_tokens\":16}}");
        assertEquals(7, usage.completionTokens);
        assertEquals("Model not loaded", ServerClient.parseSseLine("data: {\"error\":{\"message\":\"Model not loaded\",\"type\":\"invalid_request_error\"}}").error);
        assertEquals("boom", ServerClient.parseSseLine("{\"error\":\"boom\"}").error);
        // whole (non-streamed) completion
        assertEquals("Hello!", ServerClient.parseOpenAiJson("{\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"Hello!\"},\"finish_reason\":\"stop\"}]}").content);
        // legacy text completion shape (KoboldCpp / older servers)
        assertEquals("yo", ServerClient.parseOpenAiJson("{\"choices\":[{\"text\":\"yo\"}]}").content);
    }

    @Test public void thinkFilterAcrossChunks() {
        ServerClient.ThinkFilter f = new ServerClient.ThinkFilter();
        StringBuilder out = new StringBuilder();
        for (String p : new String[]{"<thi", "nk>let me ", "think</th", "ink>\n\nThe answer", " is 4. <", "b>ok</b>"}) out.append(f.push(p));
        out.append(f.finish());
        assertEquals("The answer is 4. <b>ok</b>", out.toString());
        ServerClient.ThinkFilter g = new ServerClient.ThinkFilter();
        assertEquals("plain", g.push("plain") + g.finish());
        ServerClient.ThinkFilter u = new ServerClient.ThinkFilter();
        assertEquals("", u.push("<think>never closed") + u.finish());
    }

    // ------------------------------------------------------------ real HTTP: Ollama

    private static MockHttp ollama(long gapMs) throws IOException {
        return new MockHttp(r -> {
            if (r.path.equals("/api/tags")) return MockHttp.Resp.json(200, "{\"models\":[{\"name\":\"qwen2.5:0.5b\"},{\"name\":\"llama3.2:1b\"}]}");
            if (r.path.equals("/api/chat")) {
                String model = new JSONObject(r.body).getString("model");
                if (!model.startsWith("qwen")) return MockHttp.Resp.json(404, "{\"error\":\"model '" + model + "' not found\"}");
                return MockHttp.Resp.stream("application/x-ndjson", gapMs,
                        "{\"model\":\"qwen2.5:0.5b\",\"message\":{\"role\":\"assistant\",\"content\":\"Hey\"},\"done\":false}\n",
                        "{\"model\":\"qwen2.5:0.5b\",\"message\":{\"role\":\"assistant\",\"content\":\" there\"},\"done\":false}\n",
                        "{\"model\":\"qwen2.5:0.5b\",\"message\":{\"role\":\"assistant\",\"content\":\"! 👋\"},\"done\":false}\n",
                        "{\"model\":\"qwen2.5:0.5b\",\"message\":{\"role\":\"assistant\",\"content\":\"\"},\"done\":true,\"eval_count\":4,\"eval_duration\":200000000}\n");
            }
            return MockHttp.Resp.json(404, "404 page not found");
        });
    }

    @Test public void ollamaProbeAndStream() throws Exception {
        try (MockHttp m = ollama(30)) {
            ServerClient c = new ServerClient(m.base.replace("http://", ""), ServerClient.AUTO, "");
            ServerClient.Probe p = c.probe();
            assertEquals(ServerClient.OLLAMA, p.backend);
            assertEquals(Arrays.asList("llama3.2:1b", "qwen2.5:0.5b"), p.models);

            List<String> pieces = new ArrayList<>();
            ServerClient.Result r = new ServerClient(m.base, ServerClient.OLLAMA, "").chat("qwen2.5:0.5b", hi(), 0.5f, 64, pieces::add, null);
            assertEquals(Arrays.asList("Hey", " there", "! 👋"), pieces);
            assertEquals("Hey there! 👋", r.text);
            assertEquals(4, r.tokens);
            assertEquals(20.0, r.tokPerSec, 0.01);
            assertEquals(ServerClient.OLLAMA, r.backend);
            assertTrue(r.statsLine().startsWith("qwen2.5:0.5b · 20.0 tok/s"));

            JSONObject sent = new JSONObject(m.requests.get(m.requests.size() - 1).body);
            assertEquals("qwen2.5:0.5b", sent.getString("model"));
            assertTrue(sent.getBoolean("stream"));
            assertEquals(64, sent.getJSONObject("options").getInt("num_predict"));
            assertEquals(0.5, sent.getJSONObject("options").getDouble("temperature"), 1e-6);
            JSONArray msgs = sent.getJSONArray("messages");
            assertEquals("system", msgs.getJSONObject(0).getString("role"));
            assertEquals("hi", msgs.getJSONObject(1).getString("content"));
            assertNull("no API key, no Authorization header", m.requests.get(m.requests.size() - 1).headers.get("authorization"));
        }
    }

    @Test public void ollamaMissingModel() throws Exception {
        try (MockHttp m = ollama(0)) {
            try {
                new ServerClient(m.base, ServerClient.OLLAMA, "").chat("mistral", hi(), 0.7f, 32, null, null);
                fail();
            } catch (IOException e) {
                assertTrue(e.getMessage(), e.getMessage().contains("ollama pull mistral"));
            }
        }
    }

    @Test public void ollamaErrorMidStream() throws Exception {
        try (MockHttp m = new MockHttp(r -> MockHttp.Resp.stream("application/x-ndjson", 0,
                "{\"message\":{\"role\":\"assistant\",\"content\":\"Hi\"},\"done\":false}\n",
                "{\"error\":\"llama runner process has terminated: signal: killed\"}\n"))) {
            try {
                new ServerClient(m.base, ServerClient.OLLAMA, "").chat("qwen2.5:0.5b", hi(), 0.7f, 32, null, null);
                fail();
            } catch (IOException e) {
                assertTrue(e.getMessage(), e.getMessage().contains("llama runner process has terminated"));
            }
        }
    }

    // ------------------------------------------------------------ real HTTP: OpenAI-compatible

    private static MockHttp openAi(String key) throws IOException {
        return new MockHttp(r -> {
            if (key != null && !("Bearer " + key).equals(r.headers.get("authorization")))
                return MockHttp.Resp.json(401, "{\"error\":{\"message\":\"Invalid API key\",\"type\":\"invalid_request_error\"}}");
            if (r.path.equals("/v1/models")) return MockHttp.Resp.json(200, "{\"object\":\"list\",\"data\":[{\"id\":\"qwen2.5-7b-instruct\",\"object\":\"model\"}]}");
            if (r.path.equals("/v1/chat/completions")) {
                return MockHttp.Resp.stream("text/event-stream", 20,
                        ": ping\n\n",
                        "data: {\"id\":\"c\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"\"}}]}\n\n",
                        "data: {\"id\":\"c\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"<think>user says hi\"}}]}\n\n",
                        "data: {\"id\":\"c\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"</think>\\n\\nHello\"}}]}\n\n",
                        "data: {\"id\":\"c\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\" from LM Studio\"}}]}\n\n",
                        "data: {\"id\":\"c\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n",
                        "data: [DONE]\n\n");
            }
            return MockHttp.Resp.json(404, "{\"error\":\"Unexpected endpoint or method. (GET " + r.path + ")\"}");
        });
    }

    @Test public void openAiAutoDetectAndStream() throws Exception {
        try (MockHttp m = openAi(null)) {
            ServerClient c = new ServerClient(m.base + "/v1", ServerClient.AUTO, "");
            ServerClient.Probe p = c.probe();
            assertEquals(ServerClient.OPENAI, p.backend);
            assertEquals(Collections.singletonList("qwen2.5-7b-instruct"), p.models);
            List<String> pieces = new ArrayList<>();
            ServerClient.Result r = c.chat("qwen2.5-7b-instruct", hi(), 0.7f, 128, pieces::add, null);
            assertEquals("Hello from LM Studio", r.text);
            assertEquals("Hello from LM Studio", String.join("", pieces));
            assertEquals(ServerClient.OPENAI, r.backend);
            JSONObject sent = new JSONObject(m.requests.get(m.requests.size() - 1).body);
            assertEquals("/v1/chat/completions", m.requests.get(m.requests.size() - 1).path);
            assertTrue(sent.getBoolean("stream"));
            assertEquals(128, sent.getInt("max_tokens"));
            assertEquals("qwen2.5-7b-instruct", sent.getString("model"));
        }
    }

    @Test public void openAiApiKey() throws Exception {
        try (MockHttp m = openAi("sk-local-123")) {
            try {
                new ServerClient(m.base, ServerClient.OPENAI, "").probe();
                fail();
            } catch (IOException e) {
                assertTrue(e.getMessage(), e.getMessage().contains("wants an API key"));
            }
            try {
                new ServerClient(m.base, ServerClient.AUTO, "wrong").probe();
                fail();
            } catch (IOException e) {
                assertTrue(e.getMessage(), e.getMessage().contains("rejected the API key"));
            }
            ServerClient ok = new ServerClient(m.base, ServerClient.OPENAI, "sk-local-123");
            assertEquals(Collections.singletonList("qwen2.5-7b-instruct"), ok.listModels());
            assertEquals("Hello from LM Studio", ok.chat("qwen2.5-7b-instruct", hi(), 0.7f, 32, null, null).text);
        }
    }

    @Test public void openAiNonStreamingFallbackWithUsage() throws Exception {
        try (MockHttp m = new MockHttp(r -> MockHttp.Resp.json(200,
                "{\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"One shot answer.\"},\"finish_reason\":\"stop\"}],"
                        + "\"usage\":{\"completion_tokens\":5}}"))) {
            List<String> pieces = new ArrayList<>();
            ServerClient.Result r = new ServerClient(m.base, ServerClient.OPENAI, "").chat("m", hi(), 0.7f, 32, pieces::add, null);
            assertEquals("One shot answer.", r.text);
            assertEquals(Collections.singletonList("One shot answer."), pieces);
            assertEquals(5, r.tokens);
        }
    }

    @Test public void openAiModelError() throws Exception {
        try (MockHttp m = new MockHttp(r -> MockHttp.Resp.json(400,
                "{\"error\":{\"message\":\"Model 'gpt-oss' not found. Load it first.\",\"type\":\"invalid_request_error\"}}"))) {
            try {
                new ServerClient(m.base, ServerClient.OPENAI, "").chat("gpt-oss", hi(), 0.7f, 32, null, null);
                fail();
            } catch (IOException e) {
                assertTrue(e.getMessage(), e.getMessage().contains("doesn't have the model \"gpt-oss\""));
            }
        }
    }

    @Test public void notAnAiServer() throws Exception {
        try (MockHttp m = new MockHttp(r -> MockHttp.Resp.json(404, "<html>Not Found</html>"))) {
            try {
                new ServerClient(m.base, ServerClient.AUTO, "").probe();
                fail();
            } catch (IOException e) {
                assertTrue(e.getMessage(), e.getMessage().contains("isn't an Ollama or OpenAI-compatible API"));
            }
        }
    }

    // ------------------------------------------------------------ network errors

    @Test public void connectionRefused() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0)) { port = s.getLocalPort(); }   // free port, nothing listening
        try {
            new ServerClient("127.0.0.1:" + port, ServerClient.OLLAMA, "").timeouts(2000, 2000).probe();
            fail();
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().startsWith("Couldn't connect to 127.0.0.1:" + port));
            assertTrue(e.getMessage(), e.getMessage().contains("OLLAMA_HOST=0.0.0.0"));
        }
    }

    @Test public void unknownHost() {
        try {
            new ServerClient("http://no-such-host.invalid:11434", ServerClient.AUTO, "").timeouts(3000, 3000).probe();
            fail();
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().startsWith("Can't find \"no-such-host.invalid:11434\""));
        }
    }

    @Test public void readTimeout() throws Exception {
        try (MockHttp m = new MockHttp(r -> { Thread.sleep(1500); return MockHttp.Resp.json(200, "{\"models\":[]}"); })) {
            try {
                new ServerClient(m.base, ServerClient.OLLAMA, "").timeouts(1000, 300).probe();
                fail();
            } catch (IOException e) {
                assertTrue(e.getMessage(), e.getMessage().contains("stopped answering"));
            }
        }
    }

    @Test public void emptyAddressAndModel() {
        try { new ServerClient("", ServerClient.AUTO, "").probe(); fail(); }
        catch (IOException e) { assertTrue(e.getMessage().contains("address")); }
        try { new ServerClient("h:1", ServerClient.OLLAMA, "").chat("", hi(), 0.7f, 8, null, null); fail(); }
        catch (IOException e) { assertTrue(e.getMessage().contains("Pick a model")); }
    }

    @Test public void cancelStopsTheStream() throws Exception {
        String[] parts = new String[40];
        for (int i = 0; i < parts.length; i++) parts[i] = "{\"message\":{\"role\":\"assistant\",\"content\":\"w" + i + " \"},\"done\":false}\n";
        try (MockHttp m = new MockHttp(r -> MockHttp.Resp.stream("application/x-ndjson", 50, parts))) {
            ServerClient.Cancel cancel = new ServerClient.Cancel();
            List<String> pieces = new ArrayList<>();
            long t0 = System.currentTimeMillis();
            ServerClient.Result r = new ServerClient(m.base, ServerClient.OLLAMA, "").chat("m", hi(), 0.7f, 32, p -> {
                pieces.add(p);
                if (pieces.size() == 3) cancel.cancel();
            }, cancel);
            assertTrue("stopped early, not after all 40 pieces", System.currentTimeMillis() - t0 < 1500);
            assertTrue(pieces.size() >= 3 && pieces.size() < 40);
            assertTrue(r.text.startsWith("w0 w1 w2"));
        }
    }

    // ------------------------------------------------------------ saved servers

    @Test public void serverJsonRoundTrip() throws Exception {
        LlmServer s = new LlmServer("id1", "Gaming PC", "192.168.1.20:11434", ServerClient.AUTO, ServerClient.OLLAMA, "", "qwen2.5:7b");
        LlmServer back = LlmServer.fromJson(new JSONObject(s.toJson().toString()));
        assertEquals("Gaming PC", back.name);
        assertEquals(ServerClient.OLLAMA, back.backend());
        assertEquals("Ollama @ 192.168.1.20:11434", back.label());
        assertEquals("qwen2.5:7b", back.model);
        LlmServer weird = new LlmServer("x", "n", "u", "bogus", "bogus", null, null);
        assertEquals(ServerClient.AUTO, weird.backend());
        List<LlmServer> list = ServerStore.decode(ServerStore.encode(Arrays.asList(s, weird.withModel("m"))));
        assertEquals(2, list.size());
        assertEquals("m", list.get(1).model);
        assertTrue(ServerStore.decode("not json").isEmpty());
    }
}

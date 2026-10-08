package com.natestechstuff.jarvis;

import static org.junit.Assert.*;

import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Streams real replies from a real local AI server through the app's own client code.
 * Only runs when asked:  ./gradlew testDebugUnitTest -PrealServer=http://127.0.0.1:11434 -PrealModel=qwen2.5:0.5b
 * Uses both of Ollama's APIs: native (/api/chat, NDJSON) and its OpenAI-compatible /v1 (SSE).
 */
public class RealServerTest {
    private String url, model;

    @Before public void setUp() {
        url = System.getProperty("realServer");
        Assume.assumeTrue("no real server given", url != null && !url.isEmpty());
        model = System.getProperty("realModel", "qwen2.5:0.5b");
    }

    private List<ServerClient.Msg> ask(String q) {
        List<ServerClient.Msg> m = new ArrayList<>();
        m.add(new ServerClient.Msg("system", "You are Orbit, a friendly assistant. Keep replies to one short sentence."));
        m.add(new ServerClient.Msg("user", q));
        return m;
    }

    private void log(String line) {
        try (FileWriter w = new FileWriter(new File(System.getProperty("java.io.tmpdir"), "orbit-real-server.log"), true)) {
            w.write(line + "\n");
        } catch (Exception ignored) {}
        System.out.println(line);
    }

    @Test public void autoDetectFindsOllamaAndModel() throws Exception {
        ServerClient.Probe p = new ServerClient(url, ServerClient.AUTO, "").probe();
        log("probe: " + p.backend + " " + p.models);
        assertEquals(ServerClient.OLLAMA, p.backend);
        assertTrue(p.models.contains(model));
    }

    @Test public void nativeOllamaStreams() throws Exception {
        List<String> pieces = new ArrayList<>();
        ServerClient.Result r = new ServerClient(url, ServerClient.OLLAMA, "").chat(model, ask("Say hi to a new user."), 0.2f, 60, pieces::add, null);
        log("ollama native: " + pieces.size() + " pieces · " + r.statsLine() + " · " + r.millis + " ms · \"" + r.text + "\"");
        assertTrue("streamed in several pieces", pieces.size() > 2);
        assertEquals(r.text, String.join("", pieces).trim());
        assertFalse(r.text.isEmpty());
        assertTrue(r.tokPerSec > 0);
    }

    @Test public void openAiCompatibleStreamsFromSameServer() throws Exception {
        // Ollama also speaks the OpenAI API at /v1: the path LM Studio, llama.cpp server, vLLM … use
        ServerClient c = new ServerClient(url + "/v1", ServerClient.OPENAI, "");
        List<String> models = c.listModels();
        log("openai /v1/models: " + models);
        assertTrue(models.contains(model));
        List<String> pieces = new ArrayList<>();
        ServerClient.Result r = c.chat(model, ask("What is 2 + 2? Answer with the number only."), 0.0f, 20, pieces::add, null);
        log("openai-compatible: " + pieces.size() + " pieces · " + r.statsLine() + " · " + r.millis + " ms · \"" + r.text + "\"");
        assertTrue(pieces.size() >= 1);
        assertTrue(r.text, r.text.contains("4"));
    }

    @Test public void missingModelIsExplained() {
        try {
            new ServerClient(url, ServerClient.OLLAMA, "").chat("no-such-model:1b", ask("hi"), 0.2f, 10, null, null);
            fail();
        } catch (Exception e) {
            log("missing model: " + e.getMessage());
            assertTrue(e.getMessage(), e.getMessage().contains("ollama pull no-such-model:1b"));
        }
    }

    @Test public void stopMidReply() throws Exception {
        ServerClient.Cancel cancel = new ServerClient.Cancel();
        List<String> pieces = new ArrayList<>();
        ServerClient.Result r = new ServerClient(url, ServerClient.OLLAMA, "").chat(model, ask("Count from 1 to 200, one number per line."), 0.2f, 400,
                p -> { pieces.add(p); if (pieces.size() == 5) cancel.cancel(); }, cancel);
        log("stopped after " + pieces.size() + " pieces: \"" + r.text.replace('\n', ' ') + "\"");
        assertTrue(pieces.size() < 60);
    }
}

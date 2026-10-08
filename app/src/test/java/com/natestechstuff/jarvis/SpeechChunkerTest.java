package com.natestechstuff.jarvis;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class SpeechChunkerTest {
    private static List<String> run(String... pieces) {
        List<String> out = new ArrayList<>();
        SpeechChunker c = new SpeechChunker();
        for (String p : pieces) c.push(p, out::add);
        c.finish(out::add);
        return out;
    }

    @Test public void speaksEachSentenceAsItCompletes() {
        List<String> said = new ArrayList<>();
        SpeechChunker c = new SpeechChunker();
        c.push("Yo Sam", said::add);
        assertTrue(said.isEmpty());
        c.push("! What's ", said::add);
        assertEquals(1, said.size());
        assertEquals("Yo Sam!", said.get(0));
        c.push("good?", said::add);
        c.finish(said::add);
        assertEquals("What's good?", said.get(1));
    }

    @Test public void skipsCodeBlocksButSaysSoOnce() {
        List<String> said = run("Here you go: ", "```python\nprint('hi'[::-1])\n``", "`\nThat reverses it.");
        assertEquals("Here you go:", said.get(0));
        assertEquals("Code's on screen.", said.get(1));
        assertEquals("That reverses it.", said.get(2));
        for (String s : said) assertFalse(s.contains("print"));
    }

    @Test public void dropsMarkdownAndListMarkers() {
        List<String> said = run("Tips:\n- Use a **long** passphrase.\n- Turn on 2FA.");
        assertEquals("Tips:", said.get(0));
        assertEquals("Use a long passphrase.", said.get(1));
        assertEquals("Turn on 2FA.", said.get(2));
    }

    @Test public void doesNotSplitDecimals() {
        List<String> said = run("Python 3.12 is out. Nice.");
        assertEquals("Python 3.12 is out.", said.get(0));
    }

    @Test public void ggufMagic() {
        assertTrue(ModelStore.isGguf("GGUF\u0003".getBytes(), 5));
        assertFalse(ModelStore.isGguf("PK\u0003\u0004".getBytes(), 4));
        assertEquals("qwen2.5-0.5b-instruct-q4_k_m.gguf", ModelStore.safeName("qwen2.5-0.5b-instruct-q4_k_m.gguf"));
        assertEquals("my_model.gguf", ModelStore.safeName("my model"));
    }
}

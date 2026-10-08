package com.natestechstuff.jarvis;

import static org.junit.Assert.*;

import org.junit.Test;

/** v2.3 chat-bubble Markdown. */
public class MarkdownTest {
    private static String sub(Markdown.Result r, Markdown.Span s) { return r.text.substring(s.start, s.end); }

    @Test public void boldItalicInlineCode() {
        Markdown.Result r = Markdown.parse("Use **sudo** with *care* and run `ls -la`.");
        assertEquals("Use sudo with care and run ls -la.", r.text);
        assertEquals(3, r.spans.size());
        assertEquals("sudo", sub(r, r.spans.get(0)));
        assertEquals(Markdown.BOLD, r.spans.get(0).type);
        assertEquals("care", sub(r, r.spans.get(1)));
        assertEquals(Markdown.ITALIC, r.spans.get(1).type);
        assertEquals("ls -la", sub(r, r.spans.get(2)));
        assertEquals(Markdown.CODE, r.spans.get(2).type);
    }

    @Test public void codeBlock() {
        Markdown.Result r = Markdown.parse("Here:\n```python\ndef f(x):\n    return x * 2\n```\nDone.");
        assertEquals("Here:\ndef f(x):\n    return x * 2\nDone.", r.text);
        assertEquals(1, r.spans.size());
        assertEquals(Markdown.CODE_BLOCK, r.spans.get(0).type);
        assertEquals("def f(x):\n    return x * 2", sub(r, r.spans.get(0)));
    }

    @Test public void codeBlockContentIsNotFormatted() {
        Markdown.Result r = Markdown.parse("```\nx = a**b * c*d\n```");
        assertEquals("x = a**b * c*d", r.text);
        assertEquals(1, r.spans.size());
    }

    @Test public void unclosedCodeBlock() {
        Markdown.Result r = Markdown.parse("```bash\nls");
        assertEquals("ls", r.text);
        assertEquals(Markdown.CODE_BLOCK, r.spans.get(0).type);
    }

    @Test public void listsAndHeadings() {
        Markdown.Result r = Markdown.parse("## Steps\n- one\n* two\n1. **three**");
        assertEquals("Steps\n• one\n• two\n1. three", r.text);
        assertEquals(Markdown.HEADING, r.spans.get(0).type);
        assertEquals("Steps", sub(r, r.spans.get(0)));
        assertEquals(Markdown.BOLD, r.spans.get(1).type);
        assertEquals("three", sub(r, r.spans.get(1)));
    }

    @Test public void plainTextAndMathUntouched() {
        assertEquals("2 * 3 * 4 = 24", Markdown.parse("2 * 3 * 4 = 24").text);
        assertTrue(Markdown.parse("2 * 3 * 4 = 24").spans.isEmpty());
        assertEquals("snake_case_name", Markdown.parse("snake_case_name").text);
        assertEquals("", Markdown.parse(null).text);
    }
}

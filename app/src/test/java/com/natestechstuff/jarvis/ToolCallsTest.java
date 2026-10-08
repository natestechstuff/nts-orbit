package com.natestechstuff.jarvis;

import static org.junit.Assert.*;

import org.junit.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;

/** v2.3 tool-call parsing, streaming filter, prompt. */
public class ToolCallsTest {

    // ------------------------------------------------------------ parse

    @Test public void completeCall() {
        // real Qwen2.5-1.5B-Instruct output (x86 eval)
        ToolCalls.Parsed p = ToolCalls.parse("<tool_call>\n{\"name\": \"set_timer\", \"arguments\": {\"seconds\": 300}}\n</tool_call>");
        assertTrue(p.ok());
        assertEquals(1, p.calls.size());
        assertEquals("set_timer", p.calls.get(0).name);
        assertEquals(300, p.calls.get(0).args.optInt("seconds"));
        assertEquals("", p.text);
    }

    @Test public void partialCallMissingCloseTag() {
        ToolCalls.Parsed p = ToolCalls.parse("<tool_call>\n{\"name\": \"flashlight\", \"arguments\": {\"on\": true}}");
        assertTrue(p.ok());
        assertEquals("flashlight", p.calls.get(0).name);
        assertTrue(p.calls.get(0).args.optBoolean("on"));
    }

    @Test public void partialCallCutOffMidJson() {
        // generation hit the token limit inside the JSON: braces get closed
        ToolCalls.Parsed p = ToolCalls.parse("<tool_call>\n{\"name\": \"ask_tab\", \"arguments\": {\"request\": \"order me a pizza");
        assertEquals(1, p.calls.size());
        assertEquals("ask_tab", p.calls.get(0).name);
        assertEquals("order me a pizza", p.calls.get(0).args.optString("request"));
    }

    @Test public void malformedJson() {
        ToolCalls.Parsed p = ToolCalls.parse("<tool_call>\n{\"name\": \"set_timer\" \"arguments\" {{{\n</tool_call>");
        assertTrue(p.sawCall);
        assertTrue(p.calls.isEmpty());
        assertTrue(p.bad());
        assertEquals(1, p.malformed);
    }

    @Test public void malformedNoJsonAtAll() {
        assertTrue(ToolCalls.parse("<tool_call>\nset a timer please\n</tool_call>").bad());
    }

    @Test public void malformedNoName() {
        ToolCalls.Parsed p = ToolCalls.parse("<tool_call>\n{\"arguments\": {\"seconds\": 5}}\n</tool_call>");
        assertTrue(p.bad());
    }

    @Test public void emptyTag() {
        ToolCalls.Parsed p = ToolCalls.parse("<tool_call>");
        assertTrue(p.bad());
    }

    @Test public void multipleCalls() {
        String r = "<tool_call>\n{\"name\": \"flashlight\", \"arguments\": {\"on\": true}}\n</tool_call>\n"
                + "<tool_call>\n{\"name\": \"set_timer\", \"arguments\": {\"seconds\": 60, \"label\": \"eggs\"}}\n</tool_call>";
        ToolCalls.Parsed p = ToolCalls.parse(r);
        assertEquals(2, p.calls.size());
        assertEquals("flashlight", p.calls.get(0).name);
        assertEquals("set_timer", p.calls.get(1).name);
        assertEquals("eggs", p.calls.get(1).args.optString("label"));
    }

    @Test public void multipleCallsSecondUnclosed() {
        String r = "<tool_call>\n{\"name\": \"get_battery\", \"arguments\": {}}\n<tool_call>\n{\"name\": \"get_time_date\", \"arguments\": {}}";
        ToolCalls.Parsed p = ToolCalls.parse(r);
        assertEquals(2, p.calls.size());
        assertEquals("get_battery", p.calls.get(0).name);
        assertEquals("get_time_date", p.calls.get(1).name);
    }

    @Test public void oneGoodOneBad() {
        String r = "<tool_call>\n{\"name\": \"get_battery\", \"arguments\": {}}\n</tool_call>\n<tool_call>\n{oops\n</tool_call>";
        ToolCalls.Parsed p = ToolCalls.parse(r);
        assertEquals(1, p.calls.size());
        assertEquals(1, p.malformed);
        assertFalse(p.bad());
    }

    @Test public void noCall() {
        ToolCalls.Parsed p = ToolCalls.parse("Recursion is when a function calls itself on a smaller piece of the problem.");
        assertFalse(p.sawCall);
        assertTrue(p.calls.isEmpty());
        assertFalse(p.refusal);
        assertEquals("Recursion is when a function calls itself on a smaller piece of the problem.", p.text);
    }

    @Test public void codeAnswerIsNotACall() {
        ToolCalls.Parsed p = ToolCalls.parse("```python\ndef rev(xs):\n    return xs[::-1]\n```");
        assertFalse(p.sawCall);
    }

    @Test public void textBeforeCall() {
        ToolCalls.Parsed p = ToolCalls.parse("Sure thing.\n<tool_call>\n{\"name\": \"open_app\", \"arguments\": {\"name\": \"YouTube\"}}\n</tool_call>");
        assertEquals("Sure thing.", p.text);
        assertEquals("YouTube", p.calls.get(0).args.optString("name"));
    }

    @Test public void bareJsonCallWithoutTags() {
        ToolCalls.Parsed p = ToolCalls.parse("{\"name\": \"get_time_date\", \"arguments\": {}}");
        assertTrue(p.ok());
        assertEquals("get_time_date", p.calls.get(0).name);
    }

    @Test public void fencedCallAndStringArguments() {
        ToolCalls.Parsed p = ToolCalls.parse("<tool_call>\n```json\n{\"name\": \"set_alarm\", \"arguments\": \"{\\\"hour\\\": 6, \\\"minute\\\": 30}\"}\n```\n</tool_call>");
        assertTrue(p.ok());
        assertEquals(6, p.calls.get(0).args.optInt("hour"));
        assertEquals(30, p.calls.get(0).args.optInt("minute"));
    }

    @Test public void openAiStyleFunctionWrapperAndParameters() {
        ToolCalls.Parsed p = ToolCalls.parse("<tool_call>{\"type\": \"function\", \"function\": {\"name\": \"flashlight\", \"parameters\": {\"on\": false}}}</tool_call>");
        assertTrue(p.ok());
        assertEquals("flashlight", p.calls.get(0).name);
        assertFalse(p.calls.get(0).args.optBoolean("on", true));
    }

    @Test public void refusalDetected() {
        // real model outputs
        assertTrue(ToolCalls.parse("I'm sorry, but I can't assist with that.").refusal);
        assertTrue(ToolCalls.parse("I'm sorry, I don't have access to live sports scores.").refusal);
        assertTrue(ToolCalls.looksLikeRefusal("I don't have real-time access to sports scores."));
        assertTrue(ToolCalls.looksLikeRefusal("Unfortunately, I can't check the weather."));
        assertTrue(ToolCalls.looksLikeRefusal("I can\u2019t text people."));
        assertTrue(ToolCalls.looksLikeRefusal("I'm currently offline, but I can help you find the app."));
        assertFalse(ToolCalls.looksLikeRefusal("I can help with that! Recursion is..."));
        assertFalse(ToolCalls.looksLikeRefusal("15 times 12 is 180."));
        assertFalse(ToolCalls.looksLikeRefusal("Sure, here's a Python function:"));
    }

    // ------------------------------------------------------------ call formatting

    @Test public void callJsonMatchesQwenFormat() {
        ToolCalls.Call c = ToolCalls.parse("<tool_call>{\"name\":\"set_alarm\",\"arguments\":{\"hour\":7,\"minute\":0,\"label\":\"gym\"}}</tool_call>").calls.get(0);
        String j = c.json();
        assertTrue(j, j.startsWith("{\"name\": \"set_alarm\", \"arguments\": {"));
        assertTrue(j.contains("\"hour\": 7"));
        assertTrue(j.contains("\"label\": \"gym\""));
        assertEquals("<tool_call>\n" + j + "\n</tool_call>", ToolCalls.toolCallText(Arrays.asList(c)));
        assertEquals("<tool_response>\n{\"a\": 1}\n</tool_response>\n<tool_response>\n{\"b\": 2}\n</tool_response>",
                ToolCalls.toolResponseText(Arrays.asList("{\"a\": 1}", "{\"b\": 2}")));
    }

    @Test public void chips() {
        assertEquals("⚙ set_timer 5 min", ToolCalls.chip(call("set_timer", "{\"seconds\": 300}")));
        assertEquals("⚙ set_timer 1 h 30 min", ToolCalls.chip(call("set_timer", "{\"seconds\": 5400}")));
        assertEquals("⚙ set_timer 45 s", ToolCalls.chip(call("set_timer", "{\"seconds\": 45}")));
        assertEquals("⚙ set_alarm 6:30 AM", ToolCalls.chip(call("set_alarm", "{\"hour\": 6, \"minute\": 30}")));
        assertEquals("⚙ set_alarm 7:00 PM", ToolCalls.chip(call("set_alarm", "{\"hour\": 19, \"minute\": 0}")));
        assertEquals("⚙ flashlight off", ToolCalls.chip(call("flashlight", "{\"on\": false}")));
        assertEquals("⚙ open_app YouTube", ToolCalls.chip(call("open_app", "{\"name\": \"YouTube\"}")));
        assertEquals("⚙ ask_tab: order me a pizza", ToolCalls.chip(call("ask_tab", "{\"request\": \"order me a pizza\"}")));
    }

    static ToolCalls.Call call(String name, String args) {
        try { return new ToolCalls.Call(name, new org.json.JSONObject(args)); } catch (Exception e) { throw new RuntimeException(e); }
    }

    // ------------------------------------------------------------ streaming filter

    private static String stream(ToolCalls.StreamFilter f, String... pieces) {
        StringBuilder b = new StringBuilder();
        for (String p : pieces) b.append(f.push(p));
        b.append(f.finish());
        return b.toString();
    }

    @Test public void filterHidesToolCallSplitAcrossTokens() {
        ToolCalls.StreamFilter f = new ToolCalls.StreamFilter(false);
        String shown = stream(f, "Sure.", " <", "tool", "_call", ">\n{\"name\": \"flashlight\", ", "\"arguments\": {\"on\": true}}\n</tool_call>");
        assertEquals("Sure. ", shown);
        assertTrue(f.inToolCall());
    }

    @Test public void filterHoldsPartialTagThenReleasesIfNotATag() {
        ToolCalls.StreamFilter f = new ToolCalls.StreamFilter(false);
        assertEquals("x ", f.push("x <"));
        assertEquals("<b> ok", f.push("b> ok"));
        assertFalse(f.inToolCall());
    }

    @Test public void filterPlainReplyPassesThrough() {
        ToolCalls.StreamFilter f = new ToolCalls.StreamFilter(true);
        String shown = stream(f, "Recursion", " is a function", " calling itself.", " Like a loop.");
        assertEquals("Recursion is a function calling itself. Like a loop.", shown);
        assertFalse(f.refused());
    }

    @Test public void filterRefusalNeverShown() {
        ToolCalls.StreamFilter f = new ToolCalls.StreamFilter(true);
        String shown = stream(f, "I'm", " sorry", ",", " but I can't", " check the weather.");
        assertEquals("", shown);
        assertTrue(f.refused());
    }

    @Test public void filterBareJsonCallHidden() {
        ToolCalls.StreamFilter f = new ToolCalls.StreamFilter(true);
        String shown = stream(f, "{\"name\": ", "\"get_battery\", \"arguments\": {}}");
        assertEquals("", shown);
        assertTrue(f.inToolCall());
    }

    @Test public void filterJsonAnswerThatIsNotACallShownAtEnd() {
        ToolCalls.StreamFilter f = new ToolCalls.StreamFilter(false);
        assertEquals("", f.push("{\"a\": 1}"));
        assertEquals("{\"a\": 1}", f.finish());
    }

    @Test public void filterCodeBlockStreams() {
        ToolCalls.StreamFilter f = new ToolCalls.StreamFilter(false);
        String a = f.push("```python\n");
        String b = f.push("print(1)\n```");
        assertEquals("```python\nprint(1)\n```", a + b + f.finish());
        assertFalse(a.isEmpty());
    }

    @Test public void shouldStop() {
        assertFalse(ToolCalls.shouldStop("<tool_call>\n{\"name\": \"x\"", true));
        assertFalse(ToolCalls.shouldStop("<tool_call>\n{\"name\": \"x\"}\n</tool_call>", true));
        assertFalse(ToolCalls.shouldStop("<tool_call>\n{}\n</tool_call>\n<tool", true));        // maybe a 2nd call
        assertTrue(ToolCalls.shouldStop("<tool_call>\n{}\n</tool_call>\nDone! I set", true));     // chatter after the call
        assertTrue(ToolCalls.shouldStop("I'm sorry, but", true));
        assertFalse(ToolCalls.shouldStop("I'm sorry, but", false));
        assertFalse(ToolCalls.shouldStop("Sure, here you go", true));
    }
}

package com.natestechstuff.jarvis;

import static org.junit.Assert.*;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** v2.3 tool dispatch with a fake phone. */
public class ToolDispatcherTest {

    static final class FakePhone implements ToolDispatcher.Actions {
        final List<String> log = new ArrayList<>();
        String failTimer;
        @Override public String timeDate() { log.add("time"); return "12:41 PM, Wednesday, October 7, 2026"; }
        @Override public int[] battery() { log.add("battery"); return new int[]{83, 1}; }
        @Override public String setTimer(int seconds, String label) { log.add("timer " + seconds + " " + label); return failTimer; }
        @Override public String setAlarm(int hour, int minute, String label) { log.add("alarm " + hour + ":" + minute + " " + label); return null; }
        @Override public String openApp(String name) {
            log.add("open " + name);
            int i = ToolDispatcher.bestAppMatch(name, Arrays.asList("YouTube", "YouTube Music", "Spotify", "Messages", "Camera"));
            return i < 0 ? null : Arrays.asList("YouTube", "YouTube Music", "Spotify", "Messages", "Camera").get(i);
        }
        @Override public String flashlight(boolean on) { log.add("torch " + on); return null; }
    }

    static ToolCalls.Call call(String name, String args) { return ToolCallsTest.call(name, args); }

    @Test public void timer() throws Exception {
        FakePhone ph = new FakePhone();
        ToolDispatcher.Outcome o = ToolDispatcher.dispatch(call("set_timer", "{\"seconds\": 300, \"label\": \"pizza\"}"), "set a 5 min timer", true, ph);
        assertEquals(ToolDispatcher.Kind.LOCAL, o.kind);
        assertEquals(Arrays.asList("timer 300 pizza"), ph.log);
        assertEquals("{\"result\": \"timer set for 5 min\"}", o.response);
        assertEquals("Timer set for 5 minutes.", o.speech);
        assertEquals("⚙ set_timer 5 min", o.chip);
    }

    @Test public void timerArgumentForms() {
        FakePhone ph = new FakePhone();
        ToolDispatcher.dispatch(call("set_timer", "{\"seconds\": \"90\"}"), "", true, ph);
        ToolDispatcher.dispatch(call("set_timer", "{\"seconds\": \"5 min\"}"), "", true, ph);
        ToolDispatcher.dispatch(call("set_timer", "{\"minutes\": 2}"), "", true, ph);
        ToolDispatcher.dispatch(call("set_timer", "{\"seconds\": 300.0}"), "", true, ph);
        assertEquals(Arrays.asList("timer 90 ", "timer 300 ", "timer 120 ", "timer 300 "), ph.log);
    }

    @Test public void timerBadLengthIsAnErrorForTheModel() {
        FakePhone ph = new FakePhone();
        ToolDispatcher.Outcome o = ToolDispatcher.dispatch(call("set_timer", "{}"), "set a timer", true, ph);
        assertEquals(ToolDispatcher.Kind.ERROR, o.kind);
        assertTrue(o.response.startsWith("{\"error\": "));
        assertTrue(ph.log.isEmpty());
    }

    @Test public void timerPhoneFailure() {
        FakePhone ph = new FakePhone();
        ph.failTimer = "No clock app here takes timers.";
        ToolDispatcher.Outcome o = ToolDispatcher.dispatch(call("set_timer", "{\"seconds\": 60}"), "", true, ph);
        assertEquals(ToolDispatcher.Kind.ERROR, o.kind);
        assertEquals("No clock app here takes timers.", o.speech);
    }

    @Test public void alarm() {
        FakePhone ph = new FakePhone();
        ToolDispatcher.Outcome o = ToolDispatcher.dispatch(call("set_alarm", "{\"hour\": 19, \"minute\": 0, \"label\": \"gym\"}"), "", true, ph);
        assertEquals(ToolDispatcher.Kind.LOCAL, o.kind);
        assertEquals("alarm 19:0 gym", ph.log.get(0));
        assertEquals("Alarm set for 7:00 PM.", o.speech);
        assertEquals(ToolDispatcher.Kind.ERROR, ToolDispatcher.dispatch(call("set_alarm", "{\"hour\": 25, \"minute\": 0}"), "", true, ph).kind);
        // missing minute → :00
        ToolDispatcher.dispatch(call("set_alarm", "{\"hour\": 6}"), "", true, ph);
        assertEquals("alarm 6:0 ", ph.log.get(ph.log.size() - 1));
    }

    @Test public void openApp() {
        FakePhone ph = new FakePhone();
        ToolDispatcher.Outcome o = ToolDispatcher.dispatch(call("open_app", "{\"name\": \"youtube\"}"), "", true, ph);
        assertEquals("{\"result\": \"opened YouTube\"}", o.response);
        assertEquals(ToolDispatcher.Kind.ERROR, ToolDispatcher.dispatch(call("open_app", "{\"name\": \"Pizza Hut\"}"), "", true, ph).kind);
        assertEquals(ToolDispatcher.Kind.ERROR, ToolDispatcher.dispatch(call("open_app", "{}"), "", true, ph).kind);
    }

    @Test public void appMatching() {
        List<String> labels = Arrays.asList("YouTube Music", "YouTube", "Spotify", "Google Messages", "Camera", "Settings");
        assertEquals(1, ToolDispatcher.bestAppMatch("YouTube", labels));
        assertEquals(1, ToolDispatcher.bestAppMatch("the youtube app", labels));
        assertEquals(0, ToolDispatcher.bestAppMatch("youtube music", labels));
        assertEquals(3, ToolDispatcher.bestAppMatch("messages", labels));
        assertEquals(2, ToolDispatcher.bestAppMatch("Spotify", labels));
        assertEquals(-1, ToolDispatcher.bestAppMatch("discord", labels));
        assertEquals(-1, ToolDispatcher.bestAppMatch("", labels));
    }

    @Test public void flashlightAndBoolForms() {
        FakePhone ph = new FakePhone();
        ToolDispatcher.dispatch(call("flashlight", "{\"on\": true}"), "", true, ph);
        ToolDispatcher.dispatch(call("flashlight", "{\"on\": \"off\"}"), "", true, ph);
        ToolDispatcher.dispatch(call("flashlight", "{\"on\": 0}"), "", true, ph);
        assertEquals(Arrays.asList("torch true", "torch false", "torch false"), ph.log);
    }

    @Test public void timeAndBattery() throws Exception {
        FakePhone ph = new FakePhone();
        ToolDispatcher.Outcome t = ToolDispatcher.dispatch(call("get_time_date", "{}"), "", true, ph);
        assertEquals("12:41 PM, Wednesday, October 7, 2026", new JSONObject(t.response).getString("time_date"));
        ToolDispatcher.Outcome b = ToolDispatcher.dispatch(call("get_battery", "{}"), "", true, ph);
        JSONObject r = new JSONObject(b.response);
        assertEquals(83, r.getInt("level_percent"));
        assertTrue(r.getBoolean("charging"));
        assertEquals("Battery's at 83 percent and charging.", b.speech);
    }

    @Test public void askTab() {
        FakePhone ph = new FakePhone();
        ToolDispatcher.Outcome o = ToolDispatcher.dispatch(call("ask_tab", "{\"request\": \"order me a pizza\"}"), "order me a pizza", true, ph);
        assertEquals(ToolDispatcher.Kind.ASK_TAB, o.kind);
        assertEquals("order me a pizza", o.askText);
        assertEquals("Asking Tab.", o.speech);
        assertTrue(ph.log.isEmpty());
    }

    @Test public void askTabEmptyRequestUsesUserWords() {
        ToolDispatcher.Outcome o = ToolDispatcher.dispatch(call("ask_tab", "{}"), "text my mom I'm late", true, new FakePhone());
        assertEquals("text my mom I'm late", o.askText);
    }

    @Test public void askTabTurnedOff() {
        ToolDispatcher.Outcome o = ToolDispatcher.dispatch(call("ask_tab", "{\"request\": \"x\"}"), "x", false, new FakePhone());
        assertEquals(ToolDispatcher.Kind.ERROR, o.kind);
    }

    @Test public void unknownToolGoesToTab() {
        // real 1.5B output for "what's the weather": a tool that doesn't exist
        ToolDispatcher.Outcome o = ToolDispatcher.dispatch(call("get_weather", "{\"location\": \"New York\"}"), "what's the weather", true, new FakePhone());
        assertEquals(ToolDispatcher.Kind.ASK_TAB, o.kind);
        assertEquals("what's the weather", o.askText);
        assertEquals("ask_tab", o.call.name);
        ToolDispatcher.Outcome off = ToolDispatcher.dispatch(call("get_weather", "{}"), "what's the weather", false, new FakePhone());
        assertEquals(ToolDispatcher.Kind.ERROR, off.kind);
    }

    @Test public void spokenDurations() {
        assertEquals("5 minutes", ToolDispatcher.spoken(300));
        assertEquals("1 minute 30 seconds", ToolDispatcher.spoken(90));
        assertEquals("1 hour", ToolDispatcher.spoken(3600));
        assertEquals("2 hours 21 minutes", ToolDispatcher.spoken(2 * 3600 + 21 * 60));
    }
}

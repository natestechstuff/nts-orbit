package com.natestechstuff.jarvis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** v2.4 fake-claim guard + the stream filter that keeps a fake claim off the screen and out of the voice. */
public class FakeClaimTest {

    @Test public void catchesFakeClaims() {
        String[][] fake = {
                {"turn on the flashlight", "I will turn on the flashlight."},
                {"turn on the flashlight", "I will turn on the flashlight for you."},
                {"turn on the flashlight", "Sure! I've turned on the flashlight."},
                {"turn on the flashlight", "The flashlight is on."},
                {"turn on the flashlight", "Flashlight is now on!"},
                {"turn on the flashlight", "Turning on the flashlight now."},
                {"turn on the flashlight", "Okay, turning on your flashlight."},
                {"set a timer", "Timer set for 5 minutes."},
                {"set a timer for my eggs", "Sure, I've set a timer for 5 minutes."},
                {"set a timer", "Your timer has been set."},
                {"set a timer", "OK, setting a 10 minute timer."},
                {"wake me at 7", "Alarm set for 7:00 AM."},
                {"wake me at 7", "I have set an alarm for 7 AM."},
                {"wake me at 7", "Done! Your alarm is set for 7:00 AM tomorrow."},
                {"open youtube", "Opening YouTube."},
                {"open youtube", "Sure, I'll open YouTube for you."},
                {"open youtube", "I've opened YouTube."},
                {"put on some light", "Let me turn on the flashlight for you."},
                {"do it", "I have set it up for you."},
                {"lights", "**Flashlight on!**"},
        };
        for (String[] f : fake) assertTrue(f[1], FakeClaim.isFake(f[0], f[1]));
    }

    @Test public void leavesRealAnswersAlone() {
        String[][] ok = {
                {"explain how a flashlight works", "A flashlight uses a battery to power an LED. When you turn it on, current flows and the LED lights up."},
                {"what's a timer in javascript", "A timer in JavaScript is set with setTimeout, which runs a function after a delay."},
                {"how do I turn on the flashlight", "Swipe down from the top of the screen and tap the Flashlight tile."},
                {"explain recursion", "Recursion is when a function calls itself to solve a smaller version of the same problem."},
                {"what can you do", "I can set timers and alarms, open apps, turn the flashlight on or off, and ask Tab for anything online."},
                {"set a timer", "How long should the timer be?"},
                {"set an alarm for 7", "Is that 7 AM or 7 PM?"},
                {"tell me a joke", "Why did the scarecrow win an award? Because he was outstanding in his field."},
                {"hi", "Hey Sam! What's up?"},
                {"what is the capital of france", "The capital of France is Paris."},
                {"what's open source", "Open source means the code is public, so anyone can read it, change it and share it."},
                {"turn on the flashlight", "<tool_call>\n{\"name\": \"flashlight\", \"arguments\": {\"on\": true}}\n</tool_call>"},
                {"how do alarms work on android", "Android alarms use AlarmManager. When an alarm is set, the system wakes the app at that time."},
        };
        for (String[] f : ok) assertFalse(f[1], FakeClaim.isFake(f[0], f[1]));
    }

    @Test public void longAnswerOnlyChecksTheOpening() {
        String longer = "Great question about circuits. A simple circuit has a battery, a switch and a bulb. "
                + "Engineers use them in all kinds of devices, from toys to cars to houses and much more than that. "
                + "In a story, the hero says the flashlight is on and walks into the cave.";
        assertFalse(FakeClaim.isFake("tell me about circuits", longer));
    }

    static String stream(ToolCalls.StreamFilter f, String reply) {
        StringBuilder shown = new StringBuilder();
        for (int i = 0; i < reply.length(); i += 3) shown.append(f.push(reply.substring(i, Math.min(reply.length(), i + 3))));
        shown.append(f.finish());
        return shown.toString();
    }

    @Test public void streamNeverShowsTheFakeClaim() {
        ToolCalls.StreamFilter f = new ToolCalls.StreamFilter(true, "turn on the flashlight", false);
        assertEquals("", stream(f, "I will turn on the flashlight for you. It should be bright now!"));
        assertTrue(f.claimed());
        ToolCalls.StreamFilter g = new ToolCalls.StreamFilter(true, "set a timer", false);
        assertEquals("", stream(g, "Timer set for 5 minutes."));
        assertTrue(g.claimed());
    }

    @Test public void streamStillShowsNormalAnswers() {
        String a = "Recursion is when a function calls itself. Each call works on a smaller piece. It stops at a base case.";
        ToolCalls.StreamFilter f = new ToolCalls.StreamFilter(true, "explain recursion", false);
        assertEquals(a, stream(f, a));
        assertFalse(f.claimed());
        ToolCalls.StreamFilter g = new ToolCalls.StreamFilter(true, "hi", false);
        assertEquals("Hey Sam!", stream(g, "Hey Sam!"));
    }

    @Test public void streamToolCallUnaffected() {
        ToolCalls.StreamFilter f = new ToolCalls.StreamFilter(true, "turn on the flashlight", false);
        assertEquals("", stream(f, "<tool_call>\n{\"name\": \"flashlight\", \"arguments\": {\"on\": true}}\n</tool_call>"));
        assertTrue(f.inToolCall());
        assertFalse(f.claimed());
    }

    @Test public void retryRoundHoldsEverythingUntilTheEnd() {
        ToolCalls.StreamFilter f = new ToolCalls.StreamFilter(true, "turn on the flashlight", true);
        assertEquals("", f.push("Sure thing. "));
        assertEquals("", f.push("Here you go, it is very bright and nice and all that."));
        String rest = f.finish();
        assertEquals("Sure thing. Here you go, it is very bright and nice and all that.", rest);
        ToolCalls.StreamFilter g = new ToolCalls.StreamFilter(true, "turn on the flashlight", true);
        g.push("The flashlight is on.");
        assertEquals("", g.finish());
        assertTrue(g.claimed());
    }

    @Test public void oldConstructorUnchanged() {
        ToolCalls.StreamFilter f = new ToolCalls.StreamFilter(false);
        assertEquals("Timer set for 5 minutes.", stream(f, "Timer set for 5 minutes."));
    }
}

package com.natestechstuff.jarvis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

/** v2.4 action-intent router: confident matches only. */
public class ActionRouterTest {
    static final List<String> APPS = Arrays.asList("YouTube", "YouTube Music", "Spotify", "Messages", "Camera", "Chrome", "DoorDash", "Google Maps", "Clock");
    static final ActionRouter.AppLookup LOOKUP = name -> ActionRouter.strictAppMatch(name, APPS);

    static String m(String text) {
        ToolCalls.Call c = ActionRouter.match(text, LOOKUP);
        return c == null ? null : c.json();
    }

    @Test public void flashlight() {
        String on = "{\"name\": \"flashlight\", \"arguments\": {\"on\": true}}";
        String off = "{\"name\": \"flashlight\", \"arguments\": {\"on\": false}}";
        assertEquals(on, m("turn on the flashlight"));
        assertEquals(on, m("Turn on the flashlight."));
        assertEquals(on, m("Jarvis, turn on flashlight"));
        assertEquals(on, m("can you turn the flashlight on please"));
        assertEquals(on, m("flashlight on"));
        assertEquals(on, m("turn on the torch"));
        assertEquals(on, m("hey jarvis switch on the flash light"));
        assertEquals(off, m("turn off the flashlight"));
        assertEquals(off, m("Turn the flashlight off!"));
        assertEquals(off, m("flashlight off"));
        assertEquals(off, m("disable the flashlight"));
    }

    @Test public void timer() {
        assertEquals("{\"name\": \"set_timer\", \"arguments\": {\"seconds\": 300}}", m("set a timer for 5 minutes"));
        assertEquals("{\"name\": \"set_timer\", \"arguments\": {\"seconds\": 300}}", m("Set a 5-minute timer."));
        assertEquals("{\"name\": \"set_timer\", \"arguments\": {\"seconds\": 300}}", m("set a 5 min timer"));
        assertEquals("{\"name\": \"set_timer\", \"arguments\": {\"seconds\": 30}}", m("start a 30 second timer"));
        assertEquals("{\"name\": \"set_timer\", \"arguments\": {\"seconds\": 7200}}", m("set a timer for 2 hours"));
        assertEquals("{\"name\": \"set_timer\", \"arguments\": {\"seconds\": 600}}", m("set a timer for ten minutes"));
        assertEquals("{\"name\": \"set_timer\", \"arguments\": {\"seconds\": 1800}}", m("set a timer for half an hour"));
        assertEquals("{\"name\": \"set_timer\", \"arguments\": {\"seconds\": 5400}}", m("set a timer for 1 hour and 30 minutes"));
        assertEquals("{\"name\": \"set_timer\", \"arguments\": {\"seconds\": 60}}", m("set a timer for a minute"));
        assertEquals("{\"name\": \"set_timer\", \"arguments\": {\"seconds\": 900, \"label\": \"pizza\"}}", m("set a 15 minute timer for pizza"));
        assertEquals("{\"name\": \"set_timer\", \"arguments\": {\"seconds\": 180}}", m("timer for 3 minutes"));
        assertEquals("{\"name\": \"set_timer\", \"arguments\": {\"seconds\": 300}}", m("5 minute timer"));
        assertEquals("{\"name\": \"set_timer\", \"arguments\": {\"seconds\": 300}}", m("could you set a timer for 5 minutes please"));
    }

    @Test public void alarm() {
        assertEquals("{\"name\": \"set_alarm\", \"arguments\": {\"hour\": 7, \"minute\": 30}}", m("set an alarm for 7:30 am"));
        assertEquals("{\"name\": \"set_alarm\", \"arguments\": {\"hour\": 7, \"minute\": 30}}", m("Set an alarm for 7:30 a.m."));
        assertEquals("{\"name\": \"set_alarm\", \"arguments\": {\"hour\": 18, \"minute\": 15}}", m("set alarm at 6:15 pm"));
        assertEquals("{\"name\": \"set_alarm\", \"arguments\": {\"hour\": 6, \"minute\": 0}}", m("wake me up at 6 am"));
        assertEquals("{\"name\": \"set_alarm\", \"arguments\": {\"hour\": 0, \"minute\": 5}}", m("set an alarm for 12:05 AM"));
        assertEquals("{\"name\": \"set_alarm\", \"arguments\": {\"hour\": 12, \"minute\": 0}}", m("set an alarm for noon"));
        assertEquals("{\"name\": \"set_alarm\", \"arguments\": {\"hour\": 21, \"minute\": 45}}", m("set an alarm for 21:45"));
        assertEquals("{\"name\": \"set_alarm\", \"arguments\": {\"hour\": 7, \"minute\": 0}}", m("set an alarm for 7 tomorrow morning"));
        assertEquals("{\"name\": \"set_alarm\", \"arguments\": {\"hour\": 6, \"minute\": 30}}", m("set a 6:30 am alarm"));
    }

    @Test public void openApp() {
        assertEquals("{\"name\": \"open_app\", \"arguments\": {\"name\": \"youtube\"}}", m("open youtube"));
        assertEquals("{\"name\": \"open_app\", \"arguments\": {\"name\": \"spotify\"}}", m("Open Spotify."));
        assertEquals("{\"name\": \"open_app\", \"arguments\": {\"name\": \"camera\"}}", m("open the camera app"));
        assertEquals("{\"name\": \"open_app\", \"arguments\": {\"name\": \"youtube music\"}}", m("launch youtube music"));
        assertEquals("{\"name\": \"open_app\", \"arguments\": {\"name\": \"google maps\"}}", m("pull up google maps"));
    }

    @Test public void timeDateBattery() {
        String time = "{\"name\": \"get_time_date\", \"arguments\": {}}", batt = "{\"name\": \"get_battery\", \"arguments\": {}}";
        assertEquals(time, m("what time is it"));
        assertEquals(time, m("What time is it?"));
        assertEquals(time, m("what's the time"));
        assertEquals(time, m("what's the date today"));
        assertEquals(time, m("what day is it"));
        assertEquals(time, m("what's today's date"));
        assertEquals(batt, m("battery level"));
        assertEquals(batt, m("what's my battery"));
        assertEquals(batt, m("what's my battery at"));
        assertEquals(batt, m("how much battery do I have left"));
        assertEquals(batt, m("how's my battery"));
        assertEquals(batt, m("what's the battery percentage"));
    }

    @Test public void negatives() {
        String[] no = {
                "explain how a flashlight works",
                "how do I turn on the flashlight",
                "what's a timer in javascript",
                "how do timers work in javascript",
                "write a timer in python",
                "set a timer",                          // no length: the model asks
                "set an alarm",
                "set an alarm for 7",                   // am or pm? the model asks
                "set an alarm for 7:30",
                "what time is it in Tokyo",
                "what time is the game tonight",
                "what's the date of the next full moon",
                "how do lithium batteries work",
                "is a car battery the same as a phone battery",
                "open the door",
                "open a bank account",
                "open netflix",                         // not installed
                "open tab",
                "open up about your feelings",
                "the flashlight on my phone is broken",
                "my flashlight is off",
                "turn on the lights",
                "turn off the tv",
                "I love the flashlight app",
                "set a timer for 25 hours",
                "remind me in 5 minutes to check the oven",
                "explain recursion",
                "what's the weather",
                "text my mom I'm late",
                "start a fire",
                "",
        };
        for (String t : no) assertNull(t, m(t));
    }

    @Test public void appLookupIsStrict() {
        assertNull(m("open door"));                      // DoorDash is installed, but "door" isn't its name
        assertNull(m("open you"));
        assertNotNull(m("open doordash"));
        assertNotNull(m("open door dash"));
    }

    @Test public void looseOnlyForRescue() {
        String said = "bro it's dark in here, turn on the flashlight";
        assertNull(ActionRouter.match(said, LOOKUP));
        assertEquals("{\"name\": \"flashlight\", \"arguments\": {\"on\": true}}", ActionRouter.match(said, LOOKUP, false).json());
        assertNull(ActionRouter.match("tell me a joke about flashlights", LOOKUP, false));
    }

    @Test public void seconds() {
        assertEquals(90, ActionRouter.seconds("1 and a half minutes"));
        assertEquals(5400, ActionRouter.seconds("an hour and a half"));
        assertEquals(45, ActionRouter.seconds("forty five seconds"));
    }
}

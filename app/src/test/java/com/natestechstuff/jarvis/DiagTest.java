package com.natestechstuff.jarvis;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class DiagTest {
    @Test public void keepsLast20AndCollapsesRepeats() {
        List<Diag.Entry> l = new ArrayList<>();
        for (int i = 0; i < 30; i++) l = Diag.append(l, new Diag.Entry(i, "Tab", "Tab", "msg " + i, "spoken (app open)"));
        assertEquals(Diag.MAX, l.size());
        assertEquals("msg 29", l.get(l.size() - 1).text);
        l = Diag.append(l, new Diag.Entry(99, "Tab", "Tab", "msg 29", "spoken (app open)"));
        assertEquals(Diag.MAX, l.size());
        assertEquals(2, l.get(l.size() - 1).count);
    }

    @Test public void snippetIs40CharsOneLine() {
        String s = Diag.snippet("line one\nline two that is quite a bit longer than forty chars");
        assertFalse(s.contains("\n"));
        assertEquals(41, s.length());   // 40 + ellipsis
    }

    @Test public void encodeDecodeRoundTrip() {
        List<Diag.Entry> l = new ArrayList<>();
        l = Diag.append(l, new Diag.Entry(5, "Tab [bubble]", "me", "testing Jarvis", "skipped: your own message"));
        l = Diag.append(l, new Diag.Entry(6, "Tab", "Tab", "Got it", "spoken (app closed)"));
        List<Diag.Entry> back = Diag.decode(Diag.encode(l));
        assertEquals(2, back.size());
        assertEquals("Tab [bubble]", back.get(0).title);
        assertEquals("spoken (app closed)", back.get(1).decision);
    }
}

package com.natestechstuff.jarvis;

import static org.junit.Assert.*;

import android.app.Application;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/** The app: no texting, no personal endpoints, on-device + server modes only. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class PublicBuildTest {

    @Test public void noTextingInThisBuild() {
        assertEquals("com.natestechstuff.orbit", BuildConfig.APPLICATION_ID);
        assertEquals("", ToolCalls.TOOL_ASK_TAB);
    }

    @Test public void toolPromptNeverOffersAskTab() {
        String p = ToolCalls.systemPrompt("base", true, true);
        assertFalse(p.contains("ask_tab"));
        assertFalse(p.contains("Tab"));
        assertTrue(p.contains("\"name\": \"set_timer\""));
        assertTrue(p.contains("You are offline"));
    }

    @Test public void defaultsAreOnDeviceAndNoAskTab() {
        Application app = RuntimeEnvironment.getApplication();
        Prefs p = new Prefs(app);
        assertEquals(Prefs.MODE_LOCAL, p.mode());
        p.setMode("tab");          // e.g. restored prefs: never lands on a mode this app lacks
        assertEquals(Prefs.MODE_LOCAL, p.mode());
        p.setMode(Prefs.MODE_SERVER);
        assertTrue(p.isServer());
    }

    @Test public void manifestHasNoTextingPermissionsOrComponents() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        PackageInfo pi = app.getPackageManager().getPackageInfo(app.getPackageName(),
                PackageManager.GET_PERMISSIONS | PackageManager.GET_SERVICES | PackageManager.GET_RECEIVERS);
        if (pi.requestedPermissions != null) {
            for (String perm : pi.requestedPermissions) {
                assertFalse(perm, perm.contains("SMS") || perm.contains("CONTACTS") || perm.contains("NOTIFICATION_LISTENER") || perm.contains("READ_PHONE"));
            }
        }
        if (pi.services != null) for (android.content.pm.ServiceInfo s : pi.services) assertFalse(s.name, s.name.contains("TabNotificationListener"));
        if (pi.receivers != null) for (android.content.pm.ActivityInfo r : pi.receivers) assertFalse(r.name, r.name.contains("SmsReceiver"));
    }

    @Test public void defaultPersonalityHasNoPersonalDetails() throws Exception {
        String s = new String(Files.readAllBytes(Paths.get("src/main/res/raw/jarvis_system_prompt.txt")), StandardCharsets.UTF_8);
        assertTrue(s.startsWith("You are Orbit"));
        assertFalse(s.contains("Jarvis"));
        assertFalse(s.contains("Nate's AI sidekick"));
    }

    @Test public void serverStoreSavesAndSwitches() {
        Application app = RuntimeEnvironment.getApplication();
        ServerStore st = ServerStore.get(app);
        for (LlmServer s : st.all()) st.delete(s.id);
        assertNull(st.active());
        LlmServer a = st.save(new LlmServer("", "PC", "192.168.1.20:11434", ServerClient.OLLAMA, "", "", "qwen2.5:0.5b"));
        LlmServer b = st.save(new LlmServer("", "Mac", "192.168.1.30:1234", ServerClient.OPENAI, "", "", "qwen2.5-7b-instruct"));
        assertFalse(a.id.isEmpty());
        assertEquals(a.id, st.active().id);        // the first one saved is the active one
        st.setActive(b.id);
        assertEquals("Mac", st.active().name);
        st.save(b.withModel("llama-3.2-3b"));       // edit keeps the id
        assertEquals(2, st.all().size());
        assertEquals("llama-3.2-3b", st.active().model);
        st.delete(b.id);
        assertEquals(a.id, st.active().id);
    }
}

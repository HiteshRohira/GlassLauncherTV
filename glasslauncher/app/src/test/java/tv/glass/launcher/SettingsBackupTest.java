package tv.glass.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

public class SettingsBackupTest {

    @Test public void serializeAndParse_roundTripsCustomNames() {
        Map<String, String> names = new LinkedHashMap<>();
        names.put("com.example.console", "Nintendo Switch");
        names.put("tvinput://oem/HW5", "Switch HDMI");
        String customNames = CustomNameStore.serialize(names);

        SettingsBackup.Parsed parsed = SettingsBackup.parse(SettingsBackup.serialize(
                "com.example", 4, "183=com.example", "com.hidden", 1,
                6, 20, true, 0xFFFFFFFF, true, customNames));

        assertNotNull(parsed);
        assertEquals(names,
                CustomNameStore.parse(parsed.str(SettingsBackup.K_CUSTOM_NAMES)));
    }

    @Test public void parse_legacyBackupWithoutCustomNames_stillWorks() {
        String legacy = "GLBK\t1\napp_order\tcom.example\nhome_count\t4\n"
                + "key_map\t\nhidden_apps\t\nclock_mode\t0\n";

        SettingsBackup.Parsed parsed = SettingsBackup.parse(legacy);

        assertNotNull(parsed);
        assertFalse(parsed.has(SettingsBackup.K_CUSTOM_NAMES));
        assertFalse(parsed.has(SettingsBackup.K_FAVORITES_BACKGROUND));
        assertEquals("com.example", parsed.str(SettingsBackup.K_APP_ORDER));
    }

    @Test public void serialize_nullCustomNames_writesPresentEmptyField() {
        SettingsBackup.Parsed parsed = SettingsBackup.parse(SettingsBackup.serialize(
                "", 1, "", "", 0, 6, 20, true, 0xFFFFFFFF, true, null));

        assertNotNull(parsed);
        assertTrue(parsed.has(SettingsBackup.K_CUSTOM_NAMES));
        assertEquals("", parsed.str(SettingsBackup.K_CUSTOM_NAMES));
    }

    @Test public void serializeAndParse_roundTripsLayoutOptions() {
        SettingsBackup.Parsed parsed = SettingsBackup.parse(SettingsBackup.serialize(
                "", 4, "", "", 0, 4, 28, false, 0xFF00E5FF, false, ""));

        assertNotNull(parsed);
        assertEquals(4, parsed.intVal(SettingsBackup.K_LAYOUT_COLUMNS, -1));
        assertEquals(28, parsed.intVal(SettingsBackup.K_CARD_CORNER_PERCENT, -1));
        assertEquals(0, parsed.intVal(SettingsBackup.K_FOCUS_BORDER_ENABLED, -1));
        assertEquals(0xFF00E5FF,
                parsed.intVal(SettingsBackup.K_FOCUS_BORDER_COLOR, 0));
        assertEquals(0, parsed.intVal(SettingsBackup.K_FAVORITES_BACKGROUND, -1));
    }

    @Test public void parse_futureVersion_isRejected() {
        assertNull(SettingsBackup.parse("GLBK\t3\ncustom_names\tvalue\n"));
    }
}

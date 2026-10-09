package tv.glass.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

public class CustomNameStoreTest {

    @Test public void serializeAndParse_roundTripsAppsInputsAndPunctuation() {
        Map<String, String> names = new LinkedHashMap<>();
        names.put("com.nintendo.switch", "Switch, OLED: Living Room");
        names.put("tvinput://com.oem/.HdmiInputService/HW5", "Nintendo Switch 🎮");

        assertEquals(names, CustomNameStore.parse(CustomNameStore.serialize(names)));
    }

    @Test public void serialize_ordersEntriesDeterministically() {
        Map<String, String> reverse = new LinkedHashMap<>();
        reverse.put("z.package", "Zulu");
        reverse.put("a.package", "Alpha");
        Map<String, String> forward = new LinkedHashMap<>();
        forward.put("a.package", "Alpha");
        forward.put("z.package", "Zulu");

        assertEquals(CustomNameStore.serialize(forward), CustomNameStore.serialize(reverse));
    }

    @Test public void parse_skipsMalformedEntryWithoutDiscardingValidNames() {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("com.example.one", "One");
        expected.put("com.example.two", "Two");
        String valid = CustomNameStore.serialize(expected);
        int comma = valid.indexOf(',');
        String mixed = valid.substring(0, comma) + ",not-base64!:still-bad," + valid.substring(comma + 1);

        assertEquals(expected, CustomNameStore.parse(mixed));
    }

    @Test public void sanitize_trimsAndCollapsesWhitespaceAndControls() {
        assertEquals("Living Room Switch", CustomNameStore.sanitize(" \nLiving\tRoom\u0000  Switch \r"));
    }

    @Test public void sanitize_capsUnicodeWithoutSplittingSurrogatePair() {
        StringBuilder raw = new StringBuilder();
        for (int i = 0; i < CustomNameStore.MAX_NAME_CODE_POINTS + 5; i++) raw.append("🎮");
        String result = CustomNameStore.sanitize(raw.toString());

        assertEquals(CustomNameStore.MAX_NAME_CODE_POINTS,
                result.codePointCount(0, result.length()));
        assertTrue(Character.isSurrogatePair(result.charAt(result.length() - 2),
                result.charAt(result.length() - 1)));
    }

    @Test public void sanitize_lengthBoundary_neverAddsTrailingSpace() {
        StringBuilder raw = new StringBuilder();
        for (int i = 0; i < CustomNameStore.MAX_NAME_CODE_POINTS - 1; i++) raw.append('a');
        raw.append(" b");

        String result = CustomNameStore.sanitize(raw.toString());
        assertEquals(CustomNameStore.MAX_NAME_CODE_POINTS - 1, result.length());
        assertTrue(!result.endsWith(" "));
    }

    @Test public void sanitize_blankName_returnsNull() {
        assertNull(CustomNameStore.sanitize(" \n\t\r"));
    }

    @Test public void appInfo_setCustomLabel_canResetToSourceLabel() {
        AppInfo input = AppInfo.tvInput("oem/HW5", "HDMI 1");

        input.setCustomLabel("Nintendo Switch");
        assertEquals("Nintendo Switch", input.label);
        input.setCustomLabel(null);
        assertEquals("HDMI 1", input.label);
    }

    @Test public void serialize_invalidIdentityDoesNotSuppressValidEntry() {
        Map<String, String> names = new LinkedHashMap<>();
        names.put("", "Invalid");
        names.put("com.example.valid", "Valid");

        Map<String, String> parsed = CustomNameStore.parse(CustomNameStore.serialize(names));
        assertEquals(1, parsed.size());
        assertEquals("Valid", parsed.get("com.example.valid"));
    }
}

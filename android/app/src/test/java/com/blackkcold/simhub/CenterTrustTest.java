package com.blackkcold.simhub;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.List;

/** Rejection rules for a trusted center description. Pure JVM. */
public class CenterTrustTest {
    private static final String PIN_A = "sha256/" + java.util.Base64.getEncoder().encodeToString(new byte[32]);
    private static final String PIN_B = "sha256/" + java.util.Base64.getEncoder().encodeToString(fill((byte) 7));
    private static final String PIN_C = "sha256/" + java.util.Base64.getEncoder().encodeToString(fill((byte) 9));

    private static byte[] fill(byte value) {
        byte[] out = new byte[32];
        java.util.Arrays.fill(out, value);
        return out;
    }

    private static JSONArray pinsOf(String... values) throws Exception {
        JSONArray array = new JSONArray();
        for (String value : values) array.put(value);
        return array;
    }

    private static JSONObject validConfig() throws Exception {
        JSONObject json = new JSONObject();
        json.put("schemaVersion", 1);
        json.put("configId", "abcdefghijklmnop0123456789");
        json.put("node", new JSONObject().put("origin", "https://node.relay.invalid")
                .put("pins", pinsOf(PIN_A, PIN_B)));
        json.put("controller", new JSONObject().put("origin", "https://admin.relay.invalid")
                .put("pins", pinsOf(PIN_B, PIN_C)));
        return json;
    }

    @Test
    public void acceptsACompleteTwoRoleDescription() throws Exception {
        CenterTrust trust = CenterTrust.parse(validConfig());
        assertEquals(1, trust.schemaVersion());
        assertEquals("https://node.relay.invalid", trust.origin(CenterTrust.ROLE_NODE));
        assertEquals(2, trust.pins(CenterTrust.ROLE_NODE).size());
        assertTrue(trust.covers(CenterTrust.ROLE_NODE, "https://node.relay.invalid"));
        assertFalse(trust.covers(CenterTrust.ROLE_NODE, "https://admin.relay.invalid"));
    }

    @Test
    public void resolvesControllerOnlyThroughMatchingPinnedNodeOrigin() throws Exception {
        CenterTrust trust = CenterTrust.parse(validConfig());
        assertEquals("https://admin.relay.invalid",
                trust.controllerOriginForNode("https://node.relay.invalid"));
        assertEquals("center_pair_origin_mismatch", assertThrows(CenterTrust.Invalid.class,
                () -> trust.controllerOriginForNode("https://admin.relay.invalid")).code);
        assertEquals("center_pair_origin_mismatch", assertThrows(CenterTrust.Invalid.class,
                () -> trust.controllerOriginForNode("https://attacker.invalid")).code);

        JSONObject nodeOnly = validConfig();
        nodeOnly.remove("controller");
        CenterTrust partial = CenterTrust.fromJson(nodeOnly);
        assertEquals("center_pair_origin_mismatch", assertThrows(CenterTrust.Invalid.class,
                () -> partial.controllerOriginForNode("https://node.relay.invalid")).code);

        JSONObject shared = validConfig();
        shared.put("controller", new JSONObject().put("origin", "https://node.relay.invalid")
                .put("pins", pinsOf(PIN_A, PIN_B)));
        assertEquals("https://node.relay.invalid", CenterTrust.parse(shared)
                .controllerOriginForNode("https://node.relay.invalid"));
    }

    @Test
    public void rejectsUnknownSchemaAndMissingRole() throws Exception {
        JSONObject schema = validConfig();
        schema.put("schemaVersion", 2);
        assertEquals("center_config_schema", assertThrows(CenterTrust.Invalid.class,
                () -> CenterTrust.parse(schema)).code);
        JSONObject missing = validConfig();
        missing.remove("controller");
        assertEquals("center_config_role_missing", assertThrows(CenterTrust.Invalid.class,
                () -> CenterTrust.parse(missing)).code);
    }

    @Test
    public void rejectsOriginsThatAreNotExactHttpsOrigins() throws Exception {
        for (String bad : List.of("http://node.relay.invalid", "https://node.relay.invalid/relay",
                "https://node.relay.invalid?x=1", "https://node.relay.invalid#f",
                "https://user@node.relay.invalid", "https://localhost", "https://x.local", "")) {
            assertNull(bad, CenterTrust.normalizeOrigin(bad));
        }
        assertEquals("https://node.relay.invalid", CenterTrust.normalizeOrigin("https://node.relay.invalid"));
        assertEquals("https://node.relay.invalid:8443", CenterTrust.normalizeOrigin("https://Node.Relay.Invalid:8443"));
        JSONObject json = validConfig();
        json.put("node", new JSONObject().put("origin", "https://node.relay.invalid:8443/relay")
                .put("pins", pinsOf(PIN_A, PIN_B)));
        assertEquals("center_origin_invalid", assertThrows(CenterTrust.Invalid.class,
                () -> CenterTrust.parse(json)).code);
    }

    @Test
    public void requiresTwoDistinctValidPins() throws Exception {
        JSONObject single = validConfig();
        single.put("node", new JSONObject().put("origin", "https://node.relay.invalid")
                .put("pins", pinsOf(PIN_A)));
        assertEquals("center_pin_count_invalid", assertThrows(CenterTrust.Invalid.class,
                () -> CenterTrust.parse(single)).code);
        JSONObject duplicate = validConfig();
        duplicate.put("node", new JSONObject().put("origin", "https://node.relay.invalid")
                .put("pins", pinsOf(PIN_A, PIN_A)));
        assertEquals("center_pin_duplicate", assertThrows(CenterTrust.Invalid.class,
                () -> CenterTrust.parse(duplicate)).code);
        assertEquals("center_pin_invalid", assertThrows(CenterTrust.Invalid.class,
                () -> CenterTrust.normalizePin("c2hvcnQ=")).code);
        assertEquals(PIN_A, CenterTrust.normalizePin(PIN_A));
        assertEquals(PIN_A, CenterTrust.normalizePin(PIN_A.substring("sha256/".length())));
    }

    @Test
    public void sharedOriginMustShareItsPinSet() throws Exception {
        JSONObject shared = validConfig();
        shared.put("controller", new JSONObject().put("origin", "https://node.relay.invalid")
                .put("pins", pinsOf(PIN_C, PIN_A)));
        assertEquals("center_config_shared_origin_pins", assertThrows(CenterTrust.Invalid.class,
                () -> CenterTrust.parse(shared)).code);
        JSONObject consistent = validConfig();
        consistent.put("controller", new JSONObject().put("origin", "https://node.relay.invalid")
                .put("pins", pinsOf(PIN_A, PIN_B)));
        assertEquals("https://node.relay.invalid", CenterTrust.parse(consistent).origin(CenterTrust.ROLE_CONTROLLER));
    }

    @Test
    public void qrRoundTripKeepsOneRoleFromBecomingTheOther() throws Exception {
        CenterTrust trust = CenterTrust.parse(validConfig());
        CenterTrust decoded = CenterTrust.fromQr(trust.toQr());
        assertEquals(trust.origin(CenterTrust.ROLE_NODE), decoded.origin(CenterTrust.ROLE_NODE));
        assertEquals(trust.pins(CenterTrust.ROLE_CONTROLLER), decoded.pins(CenterTrust.ROLE_CONTROLLER));

        JSONObject nodeOnly = new JSONObject();
        nodeOnly.put("schemaVersion", 1).put("configId", "abcdefghijklmnop0123456789");
        nodeOnly.put("node", new JSONObject().put("origin", "https://node.relay.invalid")
                .put("pins", pinsOf(PIN_A, PIN_B)));
        CenterTrust partial = CenterTrust.fromJson(nodeOnly);
        assertTrue(partial.hasRole(CenterTrust.ROLE_NODE));
        assertFalse(partial.hasRole(CenterTrust.ROLE_CONTROLLER));
        assertFalse(partial.covers(CenterTrust.ROLE_CONTROLLER, "https://node.relay.invalid"));
    }

    @Test
    public void rejectsOversizedOrForeignQrPayloads() throws Exception {
        assertThrows(CenterTrust.Invalid.class, () -> CenterTrust.fromQr(null));
        assertThrows(CenterTrust.Invalid.class, () -> CenterTrust.fromQr("https://example.com"));
        assertThrows(CenterTrust.Invalid.class,
                () -> CenterTrust.fromQr(CenterTrust.QR_PREFIX + "!!!not-base64!!!"));
        StringBuilder huge = new StringBuilder(CenterTrust.QR_PREFIX);
        huge.append("A".repeat(5000));
        assertThrows(CenterTrust.Invalid.class, () -> CenterTrust.fromQr(huge.toString()));
    }

    @Test
    public void doesNotExposeOriginsOrPinsWhenPrinted() throws Exception {
        String text = CenterTrust.parse(validConfig()).toString();
        assertNotNull(text);
        assertFalse(text.contains("relay.invalid"));
        assertFalse(text.contains(PIN_A));
        assertFalse(text.contains(PIN_B));
    }
}

package com.patcher.migrate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

/**
 * The sidecar records the two facts the uninstall destroys -- WhatsApp's version
 * and its signing certificate -- so a bad parse must mean "I do not know",
 * never a half-populated record that the state machine would act on.
 */
public class ParkManifestTest {

    private static ParkManifest sample() {
        return new ParkManifest(ParkManifest.State.PARKED, 1759000000000L,
                "2.26.38.74", 263807433L, "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc", "crypt14", 1402, 21_474_836_480L,
                Arrays.asList(
                        new ParkManifest.Db("msgstore.db.crypt14", 12956872L, 1759000000000L, "deadbeef"),
                        new ParkManifest.Db("msgstore-2026-09-30.1.db.crypt14", 12000000L, 1758900000000L, "feedface")));
    }

    @Test
    public void a_rendered_manifest_parses_back_to_the_same_values() {
        ParkManifest back = ParkManifest.parse(sample().render());
        assertNotNull(back);
        assertEquals(ParkManifest.State.PARKED, back.state);
        assertEquals(1759000000000L, back.parkedAtMs);
        assertEquals("2.26.38.74", back.waVersionName);
        assertEquals(263807433L, back.waVersionCode);
        assertEquals("cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc", back.waSignatureSha256);
        assertEquals("crypt14", back.crypt);
        assertEquals(1402, back.treeFiles);
        assertEquals(21_474_836_480L, back.treeBytes);
        assertEquals(2, back.databases.size());
        assertEquals("msgstore.db.crypt14", back.databases.get(0).name);
        assertEquals("feedface", back.databases.get(1).sha256);
    }

    @Test
    public void withState_changes_only_the_state() {
        ParkManifest restored = sample().withState(ParkManifest.State.RESTORED);
        assertEquals(ParkManifest.State.RESTORED, restored.state);
        assertEquals("cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc", restored.waSignatureSha256);
        assertEquals(2, restored.databases.size());
    }

    @Test
    public void a_truncated_manifest_parses_to_null() {
        String half = sample().render();
        assertNull(ParkManifest.parse(half.substring(0, half.length() / 2)));
    }

    @Test
    public void a_future_manifest_version_parses_to_null() {
        // Refusing to read a newer layout is the point: a half-understood
        // manifest would send the state machine moving data the wrong way.
        assertNull(ParkManifest.parse(sample().render().replace("manifest_version=1", "manifest_version=2")));
    }

    @Test
    public void garbage_and_empty_input_parse_to_null() {
        assertNull(ParkManifest.parse(""));
        assertNull(ParkManifest.parse("hello"));
        assertNull(ParkManifest.parse(null));
    }

    @Test
    public void a_manifest_with_no_databases_still_round_trips() {
        ParkManifest empty = new ParkManifest(ParkManifest.State.PARKING, 1L, "x", 2L, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "none", 0, 0L, Collections.<ParkManifest.Db>emptyList());
        ParkManifest back = ParkManifest.parse(empty.render());
        assertNotNull(back);
        assertEquals(0, back.databases.size());
        assertEquals("none", back.crypt);
    }

    @Test
    public void a_manifest_truncated_at_the_end_parses_to_null() {
        // Losing the sentinel `end=1` line means the sidecar was cut off.
        String text = sample().render();
        assertNull(ParkManifest.parse(text.substring(0, text.length() - 4)));
    }

    @Test
    public void negative_db_count_parses_to_null() {
        String text = sample().render().replace("db_count=2", "db_count=-1");
        assertNull(ParkManifest.parse(text));
    }

    @Test
    public void negative_tree_files_parses_to_null() {
        String text = sample().render().replace("tree_files=1402", "tree_files=-1");
        assertNull(ParkManifest.parse(text));
    }

    @Test
    public void negative_tree_bytes_parses_to_null() {
        String text = sample().render().replace("tree_bytes=21474836480", "tree_bytes=-1");
        assertNull(ParkManifest.parse(text));
    }

    @Test
    public void empty_wa_version_name_parses_to_null() {
        String text = sample().render().replace("wa_version_name=2.26.38.74", "wa_version_name=");
        assertNull(ParkManifest.parse(text));
    }

    @Test
    public void empty_wa_signature_sha256_parses_to_null() {
        String text = sample().render().replace("wa_signature_sha256=cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc", "wa_signature_sha256=");
        assertNull(ParkManifest.parse(text));
    }

    @Test
    public void empty_crypt_parses_to_null() {
        String text = sample().render().replace("crypt=crypt14", "crypt=");
        assertNull(ParkManifest.parse(text));
    }

    @Test
    public void db_name_with_newline_raises_in_render() {
        ParkManifest bad = new ParkManifest(ParkManifest.State.PARKED, 1L, "x", 2L, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "z", 0, 0L, Arrays.asList(
                        new ParkManifest.Db("bad\nname", 1L, 2L, "sha")));
        try {
            bad.render();
            fail("render should reject db name containing newline");
        } catch (IllegalArgumentException e) {
            // Expected.
        }
    }

    private static String withSignature(String value) {
        return sample().render().replace("wa_signature_sha256=" + sample().waSignatureSha256,
                "wa_signature_sha256=" + value);
    }

    @Test
    public void a_null_signature_parses_to_null() {
        assertNull(ParkManifest.parse(withSignature("null")));
    }

    @Test
    public void a_63_character_signature_parses_to_null() {
        assertNull(ParkManifest.parse(withSignature(sample().waSignatureSha256.substring(1))));
    }

    @Test
    public void a_non_hex_signature_parses_to_null() {
        assertNull(ParkManifest.parse(withSignature(
                "g" + sample().waSignatureSha256.substring(1))));
    }

    @Test
    public void lowercase_and_uppercase_signatures_both_parse() {
        String lower = sample().waSignatureSha256.toLowerCase();
        assertNotNull(ParkManifest.parse(withSignature(lower)));
        assertNotNull(ParkManifest.parse(withSignature(lower.toUpperCase())));
    }
}

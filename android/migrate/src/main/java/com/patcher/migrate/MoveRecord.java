package com.patcher.migrate;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The record of a move, beside the park's manifest.
 *
 * Written the first time the store sees the backup the guide accepts for step 2,
 * long before step 4 parks anything. Removing WhatsApp from Android's own Settings
 * deletes its folder and the backup in it, and tells nobody. On 2026-10-10 a move
 * lost its backup exactly that way, and with nothing written down the engine then
 * read the phone as "nothing to move". With this record, and the copy in Saved/
 * that it describes, the move is still known and the chats can still go back.
 *
 * key=value with an end sentinel, for the same reasons as ParkManifest. It holds
 * facts, never the step: the step is still derived from the disk on every read.
 *
 * Keys this version does not know are kept when the file is rewritten, so a store
 * older than the one that wrote it never drops the newer one's facts.
 */
public final class MoveRecord {

    public static final String FILE_NAME = "move.txt";
    private static final int VERSION = 1;

    /** Where the last put-back came from. */
    public enum Source { NONE, PARKED, SAVED, KEPT }

    public final String waVersionName;
    public final long waVersionCode;
    /** The original's certificate, hashed as the manifest hashes it. */
    public final String waSignatureSha256;
    /**
     * The original's PackageInfo.firstInstallTime when the backup was saved; 0 when
     * unknown (a record made from a manifest). A different value later means it was
     * removed and installed again, and a reinstall writes an empty backup.
     */
    public final long waFirstInstallMs;
    public final String crypt;
    public final long savedAtMs;
    /** The files in Saved/, by path relative to it. Saved/ mirrors WhatsApp/'s layout. */
    public final List<ParkManifest.Db> saved;
    public final long restoredAtMs;
    public final Source restoredFrom;
    /** "My chats are back". */
    public final long chatsConfirmedAtMs;
    /** The new WhatsApp's own backup, once one qualifies; see Finishing.findEvidence. */
    public final long evidenceAtMs;
    public final long evidenceSize;
    public final String evidenceSha256;
    private final Map<String, String> extras;

    public MoveRecord(String waVersionName, long waVersionCode, String waSignatureSha256,
                      long waFirstInstallMs, String crypt, long savedAtMs, List<ParkManifest.Db> saved,
                      long restoredAtMs, Source restoredFrom, long chatsConfirmedAtMs,
                      long evidenceAtMs, long evidenceSize, String evidenceSha256) {
        this(waVersionName, waVersionCode, waSignatureSha256, waFirstInstallMs, crypt, savedAtMs, saved,
                restoredAtMs, restoredFrom, chatsConfirmedAtMs, evidenceAtMs, evidenceSize, evidenceSha256,
                Collections.<String, String>emptyMap());
    }

    private MoveRecord(String waVersionName, long waVersionCode, String waSignatureSha256,
                       long waFirstInstallMs, String crypt, long savedAtMs, List<ParkManifest.Db> saved,
                       long restoredAtMs, Source restoredFrom, long chatsConfirmedAtMs,
                       long evidenceAtMs, long evidenceSize, String evidenceSha256,
                       Map<String, String> extras) {
        this.waVersionName = waVersionName;
        this.waVersionCode = waVersionCode;
        this.waSignatureSha256 = waSignatureSha256;
        this.waFirstInstallMs = waFirstInstallMs;
        this.crypt = crypt;
        this.savedAtMs = savedAtMs;
        this.saved = Collections.unmodifiableList(new ArrayList<>(saved));
        this.restoredAtMs = restoredAtMs;
        this.restoredFrom = restoredFrom;
        this.chatsConfirmedAtMs = chatsConfirmedAtMs;
        this.evidenceAtMs = evidenceAtMs;
        this.evidenceSize = evidenceSize;
        this.evidenceSha256 = evidenceSha256;
        this.extras = Collections.unmodifiableMap(new LinkedHashMap<>(extras));
    }

    /**
     * A record for a move that has a manifest and none of its own: one begun before
     * records existed, or one where there was no room to save a copy.
     */
    public static MoveRecord fromManifest(ParkManifest m, long firstInstallMs) {
        return new MoveRecord(m.waVersionName, m.waVersionCode, m.waSignatureSha256, firstInstallMs,
                m.crypt, 0L, Collections.<ParkManifest.Db>emptyList(), 0L, Source.NONE, 0L, 0L, 0L, "");
    }

    public MoveRecord withSaved(String versionName, long versionCode, String crypt, long savedAtMs,
                                List<ParkManifest.Db> saved) {
        return new MoveRecord(versionName, versionCode, waSignatureSha256, waFirstInstallMs, crypt,
                savedAtMs, saved, restoredAtMs, restoredFrom, chatsConfirmedAtMs, evidenceAtMs,
                evidenceSize, evidenceSha256, extras);
    }

    /**
     * A put-back. The confirmation and the proof were about a WhatsApp that has since
     * gone (that is why the backup had to go back again), so they go too.
     */
    public MoveRecord withRestored(long atMs, Source from) {
        return new MoveRecord(waVersionName, waVersionCode, waSignatureSha256, waFirstInstallMs, crypt,
                savedAtMs, saved, atMs, from, 0L, 0L, 0L, "", extras);
    }

    public MoveRecord withChatsConfirmed(long atMs) {
        return new MoveRecord(waVersionName, waVersionCode, waSignatureSha256, waFirstInstallMs, crypt,
                savedAtMs, saved, restoredAtMs, restoredFrom, atMs, evidenceAtMs, evidenceSize,
                evidenceSha256, extras);
    }

    public MoveRecord withEvidence(long atMs, long size, String sha256) {
        return new MoveRecord(waVersionName, waVersionCode, waSignatureSha256, waFirstInstallMs, crypt,
                savedAtMs, saved, restoredAtMs, restoredFrom, chatsConfirmedAtMs, atMs, size, sha256,
                extras);
    }

    public boolean hasSaved() {
        return !saved.isEmpty();
    }

    /**
     * The saved chats: the newest .crypt under Databases/, or null when nothing was
     * saved. Backups/ holds .crypt files too (settings, stickers), written a moment
     * after the chats by the same backup run, so "the newest .crypt" alone would pick
     * one of those.
     */
    public ParkManifest.Db savedBackup() {
        List<ParkManifest.Db> databases = new ArrayList<>();
        for (ParkManifest.Db d : saved) {
            if (d.name.startsWith(Parking.DATABASES + "/")) {
                databases.add(d);
            }
        }
        return Verify.newestCrypt(databases);
    }

    public String render() {
        StringBuilder b = new StringBuilder();
        line(b, "record_version", VERSION);
        line(b, "wa_version_name", waVersionName);
        line(b, "wa_version_code", waVersionCode);
        line(b, "wa_signature_sha256", waSignatureSha256);
        line(b, "wa_first_install_ms", waFirstInstallMs);
        line(b, "crypt", crypt);
        line(b, "saved_at_ms", savedAtMs);
        line(b, "saved_count", saved.size());
        for (int i = 0; i < saved.size(); i++) {
            ParkManifest.Db d = saved.get(i);
            line(b, "saved." + i + ".path", d.name);
            line(b, "saved." + i + ".size", d.size);
            line(b, "saved." + i + ".mtime_ms", d.mtimeMs);
            line(b, "saved." + i + ".sha256", d.sha256);
        }
        line(b, "restored_at_ms", restoredAtMs);
        line(b, "restored_from", restoredFrom.name());
        line(b, "chats_confirmed_at_ms", chatsConfirmedAtMs);
        line(b, "evidence_at_ms", evidenceAtMs);
        line(b, "evidence_size", evidenceSize);
        line(b, "evidence_sha256", evidenceSha256);
        for (Map.Entry<String, String> e : extras.entrySet()) {
            line(b, e.getKey(), e.getValue());
        }
        b.append("end=1\n");
        return b.toString();
    }

    private static void line(StringBuilder b, String key, Object value) {
        String text = String.valueOf(value);
        // A newline would end the line early and shift every key after it.
        if (text.contains("\n")) {
            throw new IllegalArgumentException(key + " contains a newline");
        }
        b.append(key).append('=').append(text).append('\n');
    }

    private static final List<String> KNOWN = Arrays.asList("record_version", "wa_version_name",
            "wa_version_code", "wa_signature_sha256", "wa_first_install_ms", "crypt", "saved_at_ms",
            "saved_count", "restored_at_ms", "restored_from", "chats_confirmed_at_ms", "evidence_at_ms",
            "evidence_size", "evidence_sha256", "end");

    /** Null for anything it cannot fully trust, as ParkManifest.parse: this decides where chats go. */
    public static MoveRecord parse(String text) {
        if (text == null) {
            return null;
        }
        Map<String, String> kv = new LinkedHashMap<>();
        for (String line : text.split("\n")) {
            int eq = line.indexOf('=');
            if (eq > 0) {
                kv.put(line.substring(0, eq), line.substring(eq + 1));
            }
        }
        try {
            // The sentinel is last, so its absence means the file was cut short.
            if (!"1".equals(kv.get("end"))) {
                return null;
            }
            if (Integer.parseInt(require(kv, "record_version")) != VERSION) {
                return null;
            }
            int count = Integer.parseInt(require(kv, "saved_count"));
            if (count < 0) {
                return null;
            }
            Set<String> known = new HashSet<>(KNOWN);
            List<ParkManifest.Db> saved = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                String p = "saved." + i + ".";
                String path = require(kv, p + "path");
                // The put-back copies Saved/<path> into WhatsApp/<path>; a record on shared
                // storage must not steer a copy outside those folders.
                if (path.isEmpty() || path.startsWith("/") || path.contains("\\")
                        || Arrays.asList(path.split("/", -1)).contains("..")) {
                    throw new IllegalArgumentException("unsafe saved path");
                }
                saved.add(new ParkManifest.Db(path,
                        Long.parseLong(require(kv, p + "size")),
                        Long.parseLong(require(kv, p + "mtime_ms")),
                        require(kv, p + "sha256")));
                known.addAll(Arrays.asList(p + "path", p + "size", p + "mtime_ms", p + "sha256"));
            }
            String versionName = require(kv, "wa_version_name");
            String signature = require(kv, "wa_signature_sha256");
            String crypt = require(kv, "crypt");
            // Held to its format, as in ParkManifest: a "null" that slipped through would
            // fail every comparison with an installed WhatsApp and read as "not the original".
            if (versionName.isEmpty() || !signature.matches("[0-9a-fA-F]{64}") || crypt.isEmpty()) {
                return null;
            }
            Map<String, String> extras = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : kv.entrySet()) {
                if (!known.contains(e.getKey())) {
                    extras.put(e.getKey(), e.getValue());
                }
            }
            return new MoveRecord(versionName,
                    Long.parseLong(require(kv, "wa_version_code")),
                    signature,
                    Long.parseLong(require(kv, "wa_first_install_ms")),
                    crypt,
                    Long.parseLong(require(kv, "saved_at_ms")),
                    saved,
                    Long.parseLong(require(kv, "restored_at_ms")),
                    Source.valueOf(require(kv, "restored_from")),
                    Long.parseLong(require(kv, "chats_confirmed_at_ms")),
                    Long.parseLong(require(kv, "evidence_at_ms")),
                    Long.parseLong(require(kv, "evidence_size")),
                    require(kv, "evidence_sha256"),
                    extras);
        } catch (RuntimeException ignored) {
            // A missing key, a bad number or an unknown source all mean the same: untrusted.
            return null;
        }
    }

    private static String require(Map<String, String> kv, String key) {
        String v = kv.get(key);
        if (v == null) {
            throw new IllegalArgumentException(key);
        }
        return v;
    }

    public static File fileIn(File parkRoot) {
        return new File(parkRoot, FILE_NAME);
    }

    /**
     * The record in [parkRoot], or null when there is none or it cannot be trusted.
     * Callers that must tell those apart check fileIn(parkRoot).isFile().
     */
    public static MoveRecord readFrom(File parkRoot) {
        File f = fileIn(parkRoot);
        if (!f.isFile()) {
            return null;
        }
        try {
            return parse(Parking.readText(f));
        } catch (IOException e) {
            return null;
        }
    }

    /** Atomically, as the manifest is written: always the old record or the new one, never half. */
    static void writeTo(File parkRoot, MoveRecord record) throws IOException {
        if (!parkRoot.isDirectory() && !parkRoot.mkdirs()) {
            throw new IOException("Could not create " + parkRoot.getAbsolutePath());
        }
        Parking.atomicWriteText(fileIn(parkRoot), record.render());
    }
}

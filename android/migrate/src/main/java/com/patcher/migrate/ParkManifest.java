package com.patcher.migrate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The record that rides beside a parked folder.
 *
 * key=value rather than JSON for the same reason BackupManifest in the patch
 * uses it: these tests run on a bare JVM with no android.* and therefore no
 * bundled org.json, and a file the user can read in any text editor is worth
 * more here than nesting.
 *
 * It carries the two facts the uninstall destroys -- WhatsApp's version, so a
 * backup is never offered to an older build, and its signing certificate, so
 * the companion can tell the patched build from the one it started with without
 * being told which keystore to expect.
 *
 * parse() returns null for anything it cannot fully trust. The alternative is a
 * half-populated record, and this record decides which direction gigabytes of
 * somebody's chat history move.
 */
public final class ParkManifest {

    public static final String FILE_NAME = "manifest.txt";
    private static final int VERSION = 1;

    public enum State { PARKING, PARKED, RESTORED }

    public static final class Db {
        public final String name;
        public final long size;
        public final long mtimeMs;
        public final String sha256;

        public Db(String name, long size, long mtimeMs, String sha256) {
            this.name = name;
            this.size = size;
            this.mtimeMs = mtimeMs;
            this.sha256 = sha256;
        }
    }

    public final State state;
    public final long parkedAtMs;
    public final String waVersionName;
    public final long waVersionCode;
    public final String waSignatureSha256;
    /** crypt14, crypt15, or none -- crypt15 means the user needs their own 64-digit key. */
    public final String crypt;
    public final int treeFiles;
    public final long treeBytes;
    public final List<Db> databases;

    public ParkManifest(State state, long parkedAtMs, String waVersionName, long waVersionCode,
                        String waSignatureSha256, String crypt, int treeFiles, long treeBytes,
                        List<Db> databases) {
        this.state = state;
        this.parkedAtMs = parkedAtMs;
        this.waVersionName = waVersionName;
        this.waVersionCode = waVersionCode;
        this.waSignatureSha256 = waSignatureSha256;
        this.crypt = crypt;
        this.treeFiles = treeFiles;
        this.treeBytes = treeBytes;
        this.databases = Collections.unmodifiableList(new ArrayList<>(databases));
    }

    public ParkManifest withState(State next) {
        return new ParkManifest(next, parkedAtMs, waVersionName, waVersionCode,
                waSignatureSha256, crypt, treeFiles, treeBytes, databases);
    }

    public String render() {
        // Reject db names containing newlines, which would corrupt the sidecar.
        for (Db d : databases) {
            if (d.name.contains("\n")) {
                throw new IllegalArgumentException("db name contains newline");
            }
        }
        StringBuilder b = new StringBuilder();
        b.append("manifest_version=").append(VERSION).append('\n');
        b.append("state=").append(state.name()).append('\n');
        b.append("parked_at_ms=").append(parkedAtMs).append('\n');
        b.append("wa_version_name=").append(waVersionName).append('\n');
        b.append("wa_version_code=").append(waVersionCode).append('\n');
        b.append("wa_signature_sha256=").append(waSignatureSha256).append('\n');
        b.append("crypt=").append(crypt).append('\n');
        b.append("tree_files=").append(treeFiles).append('\n');
        b.append("tree_bytes=").append(treeBytes).append('\n');
        b.append("db_count=").append(databases.size()).append('\n');
        for (int i = 0; i < databases.size(); i++) {
            Db d = databases.get(i);
            b.append("db.").append(i).append(".name=").append(d.name).append('\n');
            b.append("db.").append(i).append(".size=").append(d.size).append('\n');
            b.append("db.").append(i).append(".mtime_ms=").append(d.mtimeMs).append('\n');
            b.append("db.").append(i).append(".sha256=").append(d.sha256).append('\n');
        }
        b.append("end=1\n");
        return b.toString();
    }

    public static ParkManifest parse(String text) {
        if (text == null) {
            return null;
        }
        Map<String, String> kv = new HashMap<>();
        for (String line : text.split("\n")) {
            int eq = line.indexOf('=');
            if (eq > 0) {
                kv.put(line.substring(0, eq), line.substring(eq + 1));
            }
        }
        try {
            // Require sentinel line to detect truncation.
            if (!require(kv, "end").equals("1")) {
                return null;
            }
            if (Integer.parseInt(require(kv, "manifest_version")) != VERSION) {
                return null;
            }
            int count = Integer.parseInt(require(kv, "db_count"));
            if (count < 0) {
                return null;
            }
            List<Db> dbs = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                dbs.add(new Db(
                        require(kv, "db." + i + ".name"),
                        Long.parseLong(require(kv, "db." + i + ".size")),
                        Long.parseLong(require(kv, "db." + i + ".mtime_ms")),
                        require(kv, "db." + i + ".sha256")));
            }
            String waVersionName = require(kv, "wa_version_name");
            String waSignatureSha256 = require(kv, "wa_signature_sha256");
            String crypt = require(kv, "crypt");
            int treeFiles = Integer.parseInt(require(kv, "tree_files"));
            long treeBytes = Long.parseLong(require(kv, "tree_bytes"));
            // Reject negative counts and empty critical strings. The signature is held to its
            // format, not just non-emptiness: a "null" that slipped through would fail the
            // comparison against an installed original and wrongly permit a put-back.
            if (treeFiles < 0 || treeBytes < 0 || waVersionName.isEmpty() ||
                    !waSignatureSha256.matches("[0-9a-fA-F]{64}") || crypt.isEmpty()) {
                return null;
            }
            return new ParkManifest(
                    State.valueOf(require(kv, "state")),
                    Long.parseLong(require(kv, "parked_at_ms")),
                    waVersionName,
                    Long.parseLong(require(kv, "wa_version_code")),
                    waSignatureSha256,
                    crypt,
                    treeFiles,
                    treeBytes,
                    dbs);
        } catch (RuntimeException ignored) {
            // Any missing key, bad number or unknown enum lands here, and all of
            // them mean the same thing to the caller: this file cannot be trusted.
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
}

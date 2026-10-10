package com.patcher.migrate;

/** One folder in WAMigrate that finishing the move removes, for the guide to list first. */
public final class SpareCopy {

    public final String name;
    public final long modifiedMs;
    public final int files;
    public final long bytes;

    SpareCopy(String name, long modifiedMs, int files, long bytes) {
        this.name = name;
        this.modifiedMs = modifiedMs;
        this.files = files;
        this.bytes = bytes;
    }

    /** An earlier move's leftovers, set aside rather than deleted (Leftovers). */
    public boolean isEarlier() {
        return name.startsWith(Leftovers.PREFIX);
    }
}

package com.patcher.migrate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** What an action did, in words the host can print without interpreting. */
public final class Result {

    public final boolean ok;
    public final String summary;
    public final List<String> details;
    /**
     * What a copy needed, when it was refused because the phone was too full; 0 for
     * every other result. The store words that refusal itself ("needs X MB").
     */
    public final long neededBytes;

    public Result(boolean ok, String summary, List<String> details) {
        this(ok, summary, details, 0L);
    }

    private Result(boolean ok, String summary, List<String> details, long neededBytes) {
        this.ok = ok;
        this.summary = summary;
        this.details = Collections.unmodifiableList(new ArrayList<>(details));
        this.neededBytes = neededBytes;
    }

    public static Result ok(String summary) {
        return new Result(true, summary, Collections.<String>emptyList());
    }

    public static Result failed(String summary, List<String> details) {
        return new Result(false, summary, details);
    }

    public static Result failed(String summary) {
        return new Result(false, summary, Collections.<String>emptyList());
    }

    /** A copy refused for want of room. Nothing was changed. */
    public static Result noRoom(long neededBytes) {
        return new Result(false, "There isn't room to save a copy: it needs "
                + Parking.megabytes(neededBytes) + " MB.", Collections.<String>emptyList(), neededBytes);
    }
}

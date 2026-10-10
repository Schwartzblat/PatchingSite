package com.patcher.migrate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Everything a screen needs and no behaviour, so the host -- the throwaway app
 * now, the user's app store app later -- can render it without reimplementing
 * any of the decision.
 */
public final class Status {

    public final Step step;
    public final boolean needsGrant;
    public final boolean canPark;
    public final boolean canPutBack;
    /**
     * The one delete there is (Migration.finish), and only in DONE: the user said
     * their chats are back and the new WhatsApp wrote a backup of its own. Until
     * 2026-10-10 the engine allowed a delete mid-move whenever WhatsApp's folder was
     * not empty; that permission is gone.
     */
    public final boolean canFinish;
    /**
     * At the start, with a saved copy, while WhatsApp's own folder still holds the
     * same backup or a newer one. Cancel deletes the copy, never the only one.
     */
    public final boolean canCancel;
    /** Plain sentences, ready to print. Never null, empty when nothing is wrong. */
    public final List<String> blockers;
    /** Things the user must know, that block nothing. Never null, empty when nothing to warn. */
    public final List<String> warnings;
    public final Facts facts;

    public Status(Step step, boolean needsGrant, boolean canPark, boolean canPutBack,
                  boolean canFinish, boolean canCancel, List<String> blockers, List<String> warnings,
                  Facts facts) {
        this.step = step;
        this.needsGrant = needsGrant;
        this.canPark = canPark;
        this.canPutBack = canPutBack;
        this.canFinish = canFinish;
        this.canCancel = canCancel;
        this.blockers = Collections.unmodifiableList(new ArrayList<>(blockers));
        this.warnings = Collections.unmodifiableList(new ArrayList<>(warnings));
        this.facts = facts;
    }
}

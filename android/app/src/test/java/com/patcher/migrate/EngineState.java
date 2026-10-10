package com.patcher.migrate;

/**
 * Test-only door to MigrationState, which is package-private in :migrate. The
 * guide model's tests feed it facts and read the engine's real decision, rather
 * than hand-building a Status that might disagree with what the engine would say.
 */
public final class EngineState {

    private EngineState() {
    }

    public static Status of(Facts facts) {
        return MigrationState.of(facts);
    }
}

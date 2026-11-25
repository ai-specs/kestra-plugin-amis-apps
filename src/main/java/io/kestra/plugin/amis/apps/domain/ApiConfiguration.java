package io.kestra.plugin.amis.apps.domain;

import java.time.Duration;

public record ApiConfiguration(Mode mode, Duration syncTimeout) {
    public static final Duration MAX_SYNC_TIMEOUT = Duration.ofSeconds(30);
    public enum Mode { ASYNC, SYNC }

    public ApiConfiguration {
        mode = mode == null ? Mode.ASYNC : mode;
        if (mode == Mode.ASYNC && syncTimeout != null) {
            throw new IllegalArgumentException("ASYNC does not accept SYNC settings");
        }
        if (mode == Mode.SYNC && (syncTimeout == null || syncTimeout.isNegative() || syncTimeout.isZero()
            || syncTimeout.compareTo(MAX_SYNC_TIMEOUT) > 0)) {
            throw new IllegalArgumentException("SYNC requires bounded timeout");
        }
    }

    public static ApiConfiguration defaults() {
        return new ApiConfiguration(null, null);
    }
}

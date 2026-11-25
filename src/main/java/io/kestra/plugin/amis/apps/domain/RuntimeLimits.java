package io.kestra.plugin.amis.apps.domain;

public record RuntimeLimits(int maxSyncConcurrency) {
    public static final int MAX_SYNC_CONCURRENCY = 32;

    public RuntimeLimits {
        if (maxSyncConcurrency < 1 || maxSyncConcurrency > MAX_SYNC_CONCURRENCY) {
            throw new IllegalArgumentException("Deployment SYNC concurrency must be between 1 and 32");
        }
    }
}

package io.kestra.plugin.amis.apps.runtime;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PollTimeoutTest {
    @Test void defaultAndBoundariesPreserveFiveMinuteMaximum() {
        assertEquals(300000L, AmisHttpRouteProvider.checkedPollTimeout(Duration.ofMinutes(5)));
        assertEquals(1L, AmisHttpRouteProvider.checkedPollTimeout(Duration.ofMillis(1)));
        assertEquals(5000L, AmisHttpRouteProvider.checkedPollTimeout(Duration.ofSeconds(5)));
    }
    @Test void invalidLimitsFailBeforePageBootstrap() {
        for (Duration value : new Duration[]{Duration.ZERO, Duration.ofMillis(-1), Duration.ofNanos(999999), Duration.ofMillis(300001)}) {
            assertThrows(IllegalArgumentException.class, () -> AmisHttpRouteProvider.checkedPollTimeout(value));
        }
        assertThrows(IllegalArgumentException.class, () -> AmisHttpRouteProvider.checkedPollTimeout(null));
    }
}

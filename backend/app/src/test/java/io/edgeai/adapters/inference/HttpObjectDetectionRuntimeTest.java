package io.edgeai.adapters.inference;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpObjectDetectionRuntimeTest {
    private static final String URL = "http://127.0.0.1:8080";

    @Test void timeoutMayReachButNotExceedThePublishedTwentySeconds() {
        new HttpObjectDetectionRuntime(URL, Duration.ofSeconds(20)).close();
        new HttpObjectDetectionRuntime(URL, Duration.ofSeconds(2)).close();
        for (var timeout : new Duration[] {Duration.ofSeconds(20).plusMillis(1), Duration.ofSeconds(24), Duration.ZERO})
            assertThatThrownBy(() -> new HttpObjectDetectionRuntime(URL, timeout))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("20 seconds");
    }
}

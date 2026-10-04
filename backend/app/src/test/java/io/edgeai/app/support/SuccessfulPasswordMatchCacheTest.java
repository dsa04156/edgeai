package io.edgeai.app.support;

import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import static org.junit.jupiter.api.Assertions.*;

class SuccessfulPasswordMatchCacheTest {
    private final AtomicInteger checks = new AtomicInteger();
    private final AtomicLong now = new AtomicLong();
    private final PasswordEncoder real = PasswordEncoderFactories.createDelegatingPasswordEncoder();
    private final PasswordEncoder counted = new PasswordEncoder() {
        public String encode(CharSequence raw) { return real.encode(raw); }
        public boolean matches(CharSequence raw, String encoded) { checks.incrementAndGet(); return real.matches(raw, encoded); }
        public boolean upgradeEncoding(String encoded) { return real.upgradeEncoding(encoded); }
    };
    private final SuccessfulPasswordMatchCache cache = new SuccessfulPasswordMatchCache(counted, 2, 30, now::get);

    @Test void retainsBcryptEncodingAndRevalidatesAfterFixedExpiry() {
        String encoded = cache.encode("correct-password");
        assertTrue(encoded.startsWith("{bcrypt}"));
        assertTrue(cache.matches("correct-password", encoded));
        now.set(29);
        assertTrue(cache.matches("correct-password", encoded));
        assertEquals(1, checks.get());
        now.set(30);
        assertTrue(cache.matches("correct-password", encoded));
        assertEquals(2, checks.get());
        assertTrue(cache.upgradeEncoding("{noop}legacy"));
        assertFalse(cache.upgradeEncoding(encoded));
    }

    @Test void changedPasswordOrStoredHashCannotReuseASuccessAndFailuresAreNotCached() {
        String original = cache.encode("correct-password"), rotated = cache.encode("rotated-password");
        assertTrue(cache.matches("correct-password", original));
        assertFalse(cache.matches("wrong-password", original));
        assertFalse(cache.matches("wrong-password", original));
        assertFalse(cache.matches("correct-password", rotated));
        assertEquals(4, checks.get());
        assertTrue(cache.matches("rotated-password", rotated));
    }

    @Test void evictsAtCapacityAndKeepsExactUnicodeInputsDistinct() {
        assertTrue(cache.matches("one", "{noop}one"));
        assertTrue(cache.matches("two", "{noop}two"));
        assertTrue(cache.matches("three", "{noop}three"));
        assertTrue(cache.matches("one", "{noop}one"));
        assertEquals(4, checks.get());
        assertTrue(cache.matches("\uD800", "{noop}\uD800"));
        assertFalse(cache.matches("?", "{noop}\uD800"));
        assertFalse(cache.matches("\uD801", "{noop}\uD800"));
    }

    @Test void concurrentCacheHitsDoNotShareMutableMacState() throws Exception {
        String encoded = cache.encode("correct-password");
        assertTrue(cache.matches("correct-password", encoded));
        try (var executor = Executors.newFixedThreadPool(8)) {
            var calls = java.util.stream.IntStream.range(0, 100)
                .mapToObj(i -> (java.util.concurrent.Callable<Boolean>) () -> cache.matches("correct-password", encoded)).toList();
            for (var result : executor.invokeAll(calls)) assertTrue(result.get());
        }
        assertEquals(1, checks.get());
    }
}

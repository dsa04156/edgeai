package io.edgeai.app.support;

import io.edgeai.app.config.HawkeyeSafetyProperties;
import io.edgeai.app.dto.HawkeyeSafetyScenarioResponse;
import io.edgeai.domain.scenario.ClassificationRuntime;
import io.edgeai.domain.scenario.HawkeyeRuntime;
import java.math.BigInteger;
import java.util.Map;

/** Reads the adapter health document against the pinned model and sample set. */
public final class HawkeyeSafetyStatus {
    private HawkeyeSafetyStatus() {}

    public record Status(String state, HawkeyeSafetyScenarioResponse.Reported reported, String promptSha256, String error) {
        public boolean ready() { return "READY".equals(state); }
    }

    public static Status of(HawkeyeRuntime runtime, HawkeyeSafetyProperties properties) {
        Map<?, ?> health;
        try { health = runtime.health(); }
        catch (ClassificationRuntime.Failure failure) { return new Status("UNAVAILABLE", null, null, null); }
        if (!(health.get("status") instanceof String state)) return new Status("UNAVAILABLE", null, null, null);
        var placement = health.get("placement") instanceof Map<?, ?> value ? value : Map.of();
        var reported = new HawkeyeSafetyScenarioResponse.Reported(text(health.get("nodeName")), text(health.get("model")),
            text(health.get("modelDigest")), text(health.get("ollamaVersion")), text(placement.get("processor")),
            bytes(placement.get("sizeBytes")), bytes(placement.get("sizeVramBytes")));
        var error = text(health.get("error"));
        if (!"READY".equals(state)) return new Status("NOT_READY", reported, null, error);
        boolean expected = reported.nodeName() != null && properties.model().ollamaName().equals(reported.model())
            && properties.model().manifestDigest().equals(reported.modelDigest())
            && properties.dataset().samplesSha256().equals(health.get("samplesSha256")) && text(health.get("promptSha256")) != null;
        return new Status(expected ? "READY" : "MISMATCH", reported, text(health.get("promptSha256")), error);
    }

    public static String text(Object value) {
        return value instanceof String s && !s.isBlank() && s.length() <= 200 ? s : null;
    }
    public static Long bytes(Object value) {
        return value instanceof BigInteger number && number.signum() >= 0 && number.bitLength() < 63 ? number.longValue() : null;
    }
}

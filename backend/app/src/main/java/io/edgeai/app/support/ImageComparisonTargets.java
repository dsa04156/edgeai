package io.edgeai.app.support;

import io.edgeai.domain.scenario.ClassificationRuntime;
import java.util.List;
import java.util.Map;

/** The two configured runtimes and the dataset/model identities they must report. */
public final class ImageComparisonTargets implements AutoCloseable {
    public record Target(String id, ClassificationRuntime runtime, String modelName, String modelSha256, String provider) {}
    /** What a runtime reported about itself; never configured values. */
    public record Identity(String nodeName, String deviceName, String provider, String precision, String runtime, String modelSha256) {}
    /** state: READY, NOT_READY, MISMATCH (ready but not the configured model/dataset/provider) or UNAVAILABLE. */
    public record Status(String state, Identity identity) {
        public boolean ready() { return "READY".equals(state); }
    }

    private final List<Target> targets;
    private final String manifestSha256, inputsSha256;

    public ImageComparisonTargets(List<Target> targets, String manifestSha256, String inputsSha256) {
        if (!targets.stream().map(Target::id).toList().equals(List.of("GPU", "NPU")))
            throw new IllegalArgumentException("Image comparison requires GPU and NPU targets in that order");
        for (var target : targets)
            if (!sha(target.modelSha256()) || target.modelName().isBlank() || target.provider().isBlank())
                throw new IllegalStateException("Image comparison target " + target.id() + " requires model name, SHA-256 and provider");
        if (!sha(manifestSha256) || !sha(inputsSha256)) throw new IllegalStateException("Image comparison requires dataset SHA-256 values");
        this.targets = List.copyOf(targets);
        this.manifestSha256 = manifestSha256;
        this.inputsSha256 = inputsSha256;
    }

    public List<Target> targets() { return targets; }
    public String manifestSha256() { return manifestSha256; }
    public String inputsSha256() { return inputsSha256; }

    public Status status(Target target) {
        Map<?, ?> health;
        try { health = target.runtime().health(); }
        catch (ClassificationRuntime.Failure failure) { return new Status("UNAVAILABLE", null); }
        var identity = identity(health);
        if (!(health.get("status") instanceof String state)) return new Status("UNAVAILABLE", null);
        // A runtime whose device failed to load reports NOT_READY before it has a device identity.
        if (!"READY".equals(state)) return new Status("NOT_READY", identity);
        if (identity == null) return new Status("UNAVAILABLE", null);
        boolean expected = target.id().equals(health.get("target")) && target.modelSha256().equals(identity.modelSha256())
            && target.provider().equals(identity.provider()) && manifestSha256.equals(health.get("manifestSha256"))
            && inputsSha256.equals(health.get("inputsSha256"));
        return new Status(expected ? "READY" : "MISMATCH", identity);
    }

    private static Identity identity(Map<?, ?> health) {
        var values = new String[6];
        var keys = List.of("nodeName", "deviceName", "provider", "precision", "runtime", "modelSha256");
        for (int i = 0; i < keys.size(); i++) {
            if (!(health.get(keys.get(i)) instanceof String value) || value.isBlank() || value.length() > 160) return null;
            values[i] = value;
        }
        return new Identity(values[0], values[1], values[2], values[3], values[4], values[5]);
    }

    @Override public void close() throws Exception {
        for (var target : targets) if (target.runtime() instanceof AutoCloseable closeable) closeable.close();
    }

    public static boolean sha(Object value) { return value instanceof String s && s.matches("[0-9a-f]{64}"); }
}

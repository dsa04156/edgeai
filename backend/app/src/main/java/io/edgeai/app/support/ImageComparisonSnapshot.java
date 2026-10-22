package io.edgeai.app.support;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/** Immutable run metadata: the verified dataset manifest rows used and what each runtime reported at start. */
public record ImageComparisonSnapshot(Dataset dataset, List<Target> targets, List<Sample> samples) {
    public record Dataset(String kind, String preprocessing, String manifestSha256, String inputsSha256, int imageCount) {}
    public record Target(String id, String modelName, String modelSha256, String nodeName, String deviceName,
            String provider, String precision, String runtime) {}
    public record Sample(int index, String path, int label, int referenceTop1, String tensorSha256) {}

    private static final JsonMapper JSON = JsonMapper.builder().build();

    public String json() { return JSON.writeValueAsString(this); }
    public static ImageComparisonSnapshot parse(String json) { return JSON.readValue(json, ImageComparisonSnapshot.class); }

    /** The first count rows of a manifest whose bytes already matched the configured SHA-256; null if malformed. */
    public static Dataset dataset(Map<?, ?> manifest, String manifestSha256, String inputsSha256, List<Sample> into, int count) {
        if (!(manifest.get("kind") instanceof String kind) || kind.isBlank() || kind.length() > 160
            || !(manifest.get("preprocessing") instanceof String preprocessing) || preprocessing.isBlank() || preprocessing.length() > 160
            || !inputsSha256.equals(manifest.get("inputs_sha256")) || !(manifest.get("samples") instanceof List<?> rows) || rows.size() < count)
            return null;
        var samples = new ArrayList<Sample>();
        for (int index = 0; index < count; index++) {
            if (!(rows.get(index) instanceof Map<?, ?> row) || !(row.get("path") instanceof String path) || path.isBlank() || path.length() > 256
                || !ImageComparisonTargets.sha(row.get("tensor_sha256"))) return null;
            Integer label = classIndex(row.get("label")), reference = classIndex(row.get("reference_top1"));
            if (label == null || reference == null) return null;
            samples.add(new Sample(index, path, label, reference, (String) row.get("tensor_sha256")));
        }
        into.addAll(samples);
        return new Dataset(kind, preprocessing, manifestSha256, inputsSha256, rows.size());
    }

    /** ImageNet-1k class index. */
    public static Integer classIndex(Object value) {
        return value instanceof BigInteger number && number.signum() >= 0 && number.compareTo(BigInteger.valueOf(999)) <= 0 ? number.intValue() : null;
    }
}

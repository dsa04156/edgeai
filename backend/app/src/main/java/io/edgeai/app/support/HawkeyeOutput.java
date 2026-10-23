package io.edgeai.app.support;

import io.edgeai.domain.scenario.HawkeyeSafetyRun;
import java.util.regex.Pattern;

/**
 * Reads the category the Hawkeye model named on the first line of its answer. Observed forms: "**forklift_violation**",
 * "**Category: spill**", "Classification: improper_stacking", "**obstacle**: text". Anything else is unreadable and
 * returns null; it is never mapped to safe.
 */
public final class HawkeyeOutput {
    private static final Pattern FIRST = Pattern.compile("(?i)(?:(?:category|classification)\\s*:\\s*)?([a-z_]+)\\s*(?:[:\\-–—]\\s*(.*))?\\.?");
    private static final int EXPLANATION_CHARS = 500;
    private HawkeyeOutput() {}

    public record Parsed(String category, String explanation) {}

    public static Parsed parse(String content) {
        var lines = content.strip().split("\\R");
        var first = clean(lines[0]);
        var match = FIRST.matcher(first);
        if (first.isEmpty() || !match.matches()) return null;
        var category = match.group(1).toLowerCase();
        if (!HawkeyeSafetyRun.CATEGORIES.contains(category)) return null;
        String explanation = match.group(2) == null || match.group(2).isBlank() ? null : match.group(2).strip();
        for (int i = 1; explanation == null && i < lines.length; i++) {
            var line = clean(lines[i]).replaceFirst("(?i)^description\\s*:\\s*", "");
            if (!line.isEmpty()) explanation = line;
        }
        return new Parsed(category, explanation == null ? null
            : explanation.length() > EXPLANATION_CHARS ? explanation.substring(0, EXPLANATION_CHARS) + "…" : explanation);
    }

    private static String clean(String line) { return line.replaceAll("[*#`]", "").strip(); }
}

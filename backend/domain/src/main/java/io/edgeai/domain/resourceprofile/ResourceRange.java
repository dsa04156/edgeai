package io.edgeai.domain.resourceprofile;

/**
 * User-confirmed minimum, request and permitted maximum of one resource in its integer base unit.
 * A missing value is never represented as zero: every bound is a positive measured or entered quantity.
 */
public record ResourceRange(long min, long request, long max) {
    public static final long MAX_CPU_MILLICORES = 1_000_000L;
    public static final long MAX_MEMORY_BYTES = 16L * 1024 * 1024 * 1024 * 1024;
    public static final long MAX_ACCELERATOR_QUANTITY = 1_048_576L;

    public ResourceRange {
        if (min <= 0 || request <= 0 || max <= 0) throw new IllegalArgumentException("Resource bounds must be positive");
        if (min > request || request > max) throw new IllegalArgumentException("Resource range requires min <= request <= max");
    }
    public static ResourceRange cpu(long min, long request, long max) { return bounded(min, request, max, MAX_CPU_MILLICORES); }
    public static ResourceRange memory(long min, long request, long max) { return bounded(min, request, max, MAX_MEMORY_BYTES); }
    static ResourceRange bounded(long min, long request, long max, long limit) {
        var range = new ResourceRange(min, request, max);
        if (max > limit) throw new IllegalArgumentException("Resource maximum exceeds " + limit);
        return range;
    }
}

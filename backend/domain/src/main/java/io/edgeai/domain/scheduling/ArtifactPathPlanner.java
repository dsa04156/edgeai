package io.edgeai.domain.scheduling;

import java.math.BigDecimal;
import java.util.*;

/**
 * Artifact-store BATCH path: commit(t) = max(commit(parent)) + Σdownload + compute + Σupload on t's node.
 * A Task's cost depends only on its own node and candidates do not reserve shared capacity, so the per-Task
 * minimum yields the minimum predicted critical path of this model without enumerating node combinations.
 */
public final class ArtifactPathPlanner {
    private ArtifactPathPlanner() {}
    /** {@code totals} holds only eligible nodes with their download+compute+upload cost. */
    public record TaskCosts(Set<String> parents, Map<UUID, BigDecimal> totals) {
        public TaskCosts { parents = Set.copyOf(parents); totals = Map.copyOf(totals); }
    }
    public record Plan(Map<String, UUID> placements, Map<String, BigDecimal> commits, BigDecimal criticalPathMs, List<String> criticalPath) {}

    public static Plan plan(Map<String, TaskCosts> tasks) {
        var placements = new TreeMap<String, UUID>();
        for (var entry : tasks.entrySet()) {
            var best = entry.getValue().totals().entrySet().stream()
                .min(Comparator.<Map.Entry<UUID, BigDecimal>, BigDecimal>comparing(Map.Entry::getValue)
                    .thenComparing(e -> e.getKey().toString()))
                .orElseThrow(() -> new IllegalArgumentException("Task has no eligible node: " + entry.getKey()));
            placements.put(entry.getKey(), best.getKey());
        }
        var commits = new TreeMap<String, BigDecimal>();
        for (String key : tasks.keySet()) commit(key, tasks, placements, commits);
        var parents = new HashSet<String>();
        tasks.values().forEach(task -> parents.addAll(task.parents()));
        String sink = commits.entrySet().stream().filter(e -> !parents.contains(e.getKey())).max(Comparator.<Map.Entry<String, BigDecimal>, BigDecimal>comparing(Map.Entry::getValue)
            .thenComparing(Map.Entry::getKey, Comparator.reverseOrder())).orElseThrow().getKey();
        var path = new ArrayDeque<String>();
        for (String key = sink; key != null; ) {
            path.addFirst(key);
            key = tasks.get(key).parents().stream().max(Comparator.<String, BigDecimal>comparing(commits::get)
                .thenComparing(Comparator.<String>reverseOrder())).orElse(null);
        }
        return new Plan(Map.copyOf(placements), Map.copyOf(commits), commits.get(sink), List.copyOf(path));
    }
    private static BigDecimal commit(String key, Map<String, TaskCosts> tasks, Map<String, UUID> placements, Map<String, BigDecimal> commits) {
        var known = commits.get(key);
        if (known != null) return known;
        var task = tasks.get(key);
        BigDecimal release = BigDecimal.ZERO;
        for (String parent : task.parents()) release = release.max(commit(parent, tasks, placements, commits));
        var value = release.add(task.totals().get(placements.get(key)));
        commits.put(key, value);
        return value;
    }
}

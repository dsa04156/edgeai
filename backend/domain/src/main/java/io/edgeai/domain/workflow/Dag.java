package io.edgeai.domain.workflow;

import io.edgeai.domain.profile.ProfileIdentity;
import java.util.*;

/** Structural DAG rules are independent of transport, persistence and execution adapters. */
public record Dag(List<Node> tasks, List<Edge> dependencies) {
    public record Node(String key, UUID serviceProfileVersionId, String parametersJson) {}
    public record Edge(String fromTask, String toTask, String fromPort, String toPort, Mode mode) {}
    public enum Mode { BATCH, STREAM }

    public Dag {
        tasks = List.copyOf(tasks);
        dependencies = List.copyOf(dependencies);
        if (tasks.isEmpty() || tasks.size() > 128 || dependencies.size() > 512)
            throw new IllegalArgumentException("DAG requires 1–128 tasks and at most 512 dependencies");
        var keys = new HashSet<String>();
        for (var task : tasks) {
            ProfileIdentity.validateKey(task.key());
            if (task.serviceProfileVersionId() == null || !keys.add(task.key()))
                throw new IllegalArgumentException("Task keys must be unique");
        }
        var indegree = new HashMap<String, Integer>();
        var children = new HashMap<String, List<String>>();
        keys.forEach(key -> { indegree.put(key, 0); children.put(key, new ArrayList<>()); });
        var ports = new HashSet<List<String>>();
        for (var edge : dependencies) {
            ProfileIdentity.validateKey(edge.fromPort());
            ProfileIdentity.validateKey(edge.toPort());
            if (!keys.contains(edge.fromTask()) || !keys.contains(edge.toTask()) || edge.fromTask().equals(edge.toTask()) || edge.mode() == null)
                throw new IllegalArgumentException("Dependency must reference two distinct tasks");
            if (!ports.add(List.of(edge.toTask(), edge.toPort())))
                throw new IllegalArgumentException("Each input port accepts only one producer");
            indegree.merge(edge.toTask(), 1, Integer::sum);
            children.get(edge.fromTask()).add(edge.toTask());
        }
        var queue = new ArrayDeque<String>();
        indegree.forEach((key, count) -> { if (count == 0) queue.add(key); });
        int visited = 0;
        while (!queue.isEmpty()) {
            String key = queue.remove(); visited++;
            for (String child : children.get(key)) if (indegree.merge(child, -1, Integer::sum) == 0) queue.add(child);
        }
        if (visited != keys.size()) throw new IllegalArgumentException("Workflow must be acyclic");
    }

    public Set<String> roots() {
        var result = new HashSet<String>(); tasks.forEach(node -> result.add(node.key()));
        dependencies.forEach(edge -> result.remove(edge.toTask())); return Set.copyOf(result);
    }
    public Set<String> descendants(String key) {
        var result = new HashSet<String>(); var queue = new ArrayDeque<String>(); queue.add(key);
        while (!queue.isEmpty()) {
            String parent = queue.remove();
            for (var edge : dependencies) if (edge.fromTask().equals(parent) && result.add(edge.toTask())) queue.add(edge.toTask());
        }
        return Set.copyOf(result);
    }
}

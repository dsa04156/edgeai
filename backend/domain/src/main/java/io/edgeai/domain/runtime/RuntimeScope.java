package io.edgeai.domain.runtime;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Workflows whose Runs the runtime may execute. Restricting the scope keeps every other Run, including runtime rows,
 * commands, retries and publications left from earlier executions, out of every worker query and execution request.
 * Keys use the workflow key grammar, so the SQL conditions inline them as literals.
 */
public final class RuntimeScope {
    public static final RuntimeScope UNRESTRICTED = new RuntimeScope(null);
    private static final Pattern KEY = Pattern.compile("[a-z][a-z0-9]*([._-][a-z0-9]+)*");
    private final SortedSet<String> keys;

    private RuntimeScope(SortedSet<String> keys) { this.keys = keys; }

    /** "*" allows every workflow; otherwise a comma-separated list of exact workflow keys. Anything else is rejected. */
    public static RuntimeScope parse(String value) {
        if (value == null || value.isBlank())
            throw new IllegalArgumentException("Runtime workflow scope must be '*' or a list of workflow keys");
        if (value.trim().equals("*")) return UNRESTRICTED;
        var keys = new TreeSet<String>();
        for (var item : value.split(",", -1)) {
            String key = item.trim();
            if (key.length() > 100 || !KEY.matcher(key).matches())
                throw new IllegalArgumentException("Runtime workflow scope contains an invalid workflow key");
            keys.add(key);
        }
        return new RuntimeScope(Collections.unmodifiableSortedSet(keys));
    }

    public boolean restricted() { return keys != null; }
    public boolean allows(String workflowKey) { return keys == null || keys.contains(workflowKey); }
    public SortedSet<String> keys() { return keys == null ? Collections.emptySortedSet() : keys; }

    /** SQL condition that holds when the Run identified by {@code runColumn} belongs to an allowed workflow. */
    public String runCondition(String runColumn) {
        if (keys == null) return "TRUE";
        var literals = new StringJoiner(",");
        for (var key : keys) literals.add("'" + key + "'");
        return runColumn + " IN (SELECT scope_run.id FROM edgeai.workflow_run scope_run"
            + " JOIN edgeai.workflow_version scope_version ON scope_version.id=scope_run.workflow_version_id"
            + " JOIN edgeai.workflow scope_workflow ON scope_workflow.id=scope_version.workflow_id"
            + " WHERE scope_workflow.workflow_key IN (" + literals + "))";
    }

    /** SQL condition that holds when the Task identified by {@code taskColumn} belongs to an allowed workflow. */
    public String taskCondition(String taskColumn) {
        if (keys == null) return "TRUE";
        return taskColumn + " IN (SELECT scope_task.id FROM edgeai.task scope_task WHERE " + runCondition("scope_task.run_id") + ")";
    }

    @Override public boolean equals(Object other) { return other instanceof RuntimeScope s && Objects.equals(keys, s.keys); }
    @Override public int hashCode() { return Objects.hashCode(keys); }
    @Override public String toString() { return keys == null ? "*" : String.join(",", keys); }
}

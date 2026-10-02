package io.edgeai.domain.execution;

import java.util.Set;

/** Task-scoped retry window; maxAttempts includes the original attempt. */
public record RetryPolicy(int maxAttempts,int backoffSeconds,int maxElapsedSeconds,Set<String> retryOn) {
    public static final Set<String> ALLOWED=Set.of("WORKLOAD_FAILED","TIMEOUT","STORAGE_FAILED","RUNNER_FAILED",
        "DISPATCH_TIMEOUT","RUNTIME_TIMEOUT","RUNTIME_LOST","JOB_FAILED");
    public RetryPolicy {
        if(maxAttempts<1 || maxAttempts>8 || backoffSeconds<1 || backoffSeconds>300 || maxElapsedSeconds<1 || maxElapsedSeconds>86400 ||
                retryOn==null || !ALLOWED.containsAll(retryOn) || (maxAttempts>1 && retryOn.isEmpty()))
            throw new IllegalArgumentException("Invalid retry budget or failure codes");
        retryOn=Set.copyOf(retryOn);
    }
    public static RetryPolicy disabled(){return new RetryPolicy(1,1,86400,Set.of());}
}

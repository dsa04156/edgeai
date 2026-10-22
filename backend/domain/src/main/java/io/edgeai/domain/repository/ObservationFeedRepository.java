package io.edgeai.domain.repository;

import java.time.Instant;
import java.util.*;

/**
 * Bounded ordered queue of accepted WORKFLOW_DATA observations for opt-in ALL_OBSERVATIONS consumers.
 * Positions are assigned while the source feed is locked, so every committed head covers a gap-free prefix.
 * Callers own the surrounding transaction.
 */
public interface ObservationFeedRepository {
    record Limits(long maxBacklog,long maxEntries,long retentionSeconds) {
        public Limits {
            if(maxBacklog<1 || maxEntries<maxBacklog || retentionSeconds<1)throw new IllegalArgumentException("Invalid observation feed limits");
        }
    }
    record Subscription(UUID id,UUID runId,String taskKey,UUID feedId,long startPosition,long acknowledged,Long drainPosition,String state,String gapReason) {}
    record Entry(long position,String identity,Instant observedAt,String documentJson) {}
    /**
     * Entries after the requested position, bounded by the drain watermark when one is frozen. Head, floor, watermark
     * and entries come from one snapshot, so no entry beyond a watermark committed before that snapshot is returned.
     */
    record Page(long head,long floor,Long drainPosition,String state,String gapReason,List<Entry> entries) {
        public long bound(){return drainPosition==null?head:Math.min(head,drainPosition);}
    }
    enum Append {NO_SUBSCRIBER,APPENDED,DUPLICATE,GAP}
    /** Locks the source feed and pins the next accepted position as this consumer's start boundary. */
    Subscription subscribe(UUID runId,String taskKey,String sourceKind,String sourceKey,Instant now);
    /** Idempotent by identity while the entry is retained; marks consumers GAP instead of silently dropping. */
    Append append(String sourceKey,String identity,Instant observedAt,String documentJson,Instant now,Limits limits);
    Optional<Subscription> subscription(UUID runId,String taskKey);
    /** Empty once the subscription metadata expired. */
    Optional<Page> read(Subscription subscription,long after,int limit);
    /** Advances retention for this consumer after its Runner durably committed every position up to {@code position}. */
    void acknowledge(Subscription subscription,long position,Instant now,Limits limits);
    /** Freezes each consumer of the run at its feed's committed head. */
    void drain(UUID runId,Instant now);
    void close(Subscription subscription,Instant now);
    /** Feed ids after {@code after} in id order, so a periodic sweep visits every feed in bounded pages. */
    List<UUID> feeds(UUID after,int limit);
    /** Trims one feed without new input; skipped while another transaction holds it. */
    void maintain(UUID feedId,Instant now,Limits limits);
    /** Removes subscription metadata of terminal runs idle longer than retention; never touches live runs. */
    int expireSubscriptions(Instant now,Limits limits,int limit);
}

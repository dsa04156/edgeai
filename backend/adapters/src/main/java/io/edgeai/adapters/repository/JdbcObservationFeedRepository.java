package io.edgeai.adapters.repository;

import io.edgeai.domain.repository.ObservationFeedRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** Every position-changing statement first locks the feed row, so appends commit in position order. */
public final class JdbcObservationFeedRepository implements ObservationFeedRepository {
    /** Only these runs consume or protect feed entries; cancelling/terminal runs never need their backlog. */
    private static final String LIVE="r.state IN ('PENDING','RUNNING')";
    private final JdbcTemplate jdbc;
    public JdbcObservationFeedRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    private static Timestamp time(Instant at){return Timestamp.from(at);}
    private record Feed(UUID id,String kind,long head,long floor,Instant trimmedObservedAt) {}
    private static final RowMapper<Feed> FEED=(r,n)->new Feed(r.getObject("id",UUID.class),r.getString("kind"),r.getLong("head"),r.getLong("floor"),
        r.getTimestamp("trimmed_observed_at")==null?null:r.getTimestamp("trimmed_observed_at").toInstant());
    private static final RowMapper<Subscription> SUBSCRIPTION=(r,n)->new Subscription(r.getObject("id",UUID.class),r.getObject("run_id",UUID.class),r.getString("task_key"),
        r.getObject("feed_id",UUID.class),r.getLong("start_position"),r.getLong("acknowledged"),r.getObject("drain_position",Long.class),r.getString("state"),r.getString("gap_reason"));
    private Optional<Feed> lock(String key){return jdbc.query("SELECT * FROM edgeai.workflow_source_feed WHERE source_key=? FOR UPDATE",FEED,key).stream().findFirst();}
    public Subscription subscribe(UUID runId,String taskKey,String kind,String key,Instant now) {
        jdbc.update("INSERT INTO edgeai.workflow_source_feed(id,source_key,kind,created_at) VALUES(?,?,?,?) ON CONFLICT(source_key) DO NOTHING",UUID.randomUUID(),key,kind,time(now));
        var feed=lock(key).orElseThrow();
        if(!feed.kind().equals(kind))throw new IllegalStateException("Observation feed kind changed");
        jdbc.update("INSERT INTO edgeai.workflow_source_subscription(id,run_id,task_key,feed_id,start_position,acknowledged,state,created_at,updated_at) VALUES(?,?,?,?,?,?,'ACTIVE',?,?) ON CONFLICT(run_id,task_key) DO NOTHING",
            UUID.randomUUID(),runId,taskKey,feed.id(),feed.head(),feed.head(),time(now),time(now));
        var subscription=subscription(runId,taskKey).orElseThrow();
        if(!subscription.feedId().equals(feed.id()))throw new IllegalStateException("Subscription source changed");
        return subscription;
    }
    public Append append(String key,String identity,Instant observedAt,String document,Instant now,Limits limits) {
        var found=lock(key);if(found.isEmpty())return Append.NO_SUBSCRIBER;var feed=found.get();
        int consumers=jdbc.queryForObject("SELECT count(*) FROM edgeai.workflow_source_subscription s JOIN edgeai.workflow_run r ON r.id=s.run_id WHERE s.feed_id=? AND s.state='ACTIVE' AND s.drain_position IS NULL AND "+LIVE,Integer.class,feed.id());
        if(consumers==0){trim(feed,now,limits);return Append.NO_SUBSCRIBER;}
        if(!jdbc.queryForList("SELECT 1 FROM edgeai.workflow_source_feed_entry WHERE feed_id=? AND identity=?",feed.id(),identity).isEmpty())return Append.DUPLICATE;
        // A trimmed entry with this identity had this observation time; only arrivals newer than every trimmed one are provably new.
        if(feed.trimmedObservedAt()!=null && !observedAt.isAfter(feed.trimmedObservedAt())) {
            jdbc.update("UPDATE edgeai.workflow_source_subscription SET state='GAP',gap_reason='DEDUP_WINDOW_EXCEEDED',updated_at=? WHERE feed_id=? AND state='ACTIVE' AND drain_position IS NULL",time(now),feed.id());
            return Append.GAP;
        }
        long position=Math.addExact(feed.head(),1);
        jdbc.update("INSERT INTO edgeai.workflow_source_feed_entry(feed_id,position,identity,observed_at,accepted_at,document) VALUES(?,?,?,?,?,?::jsonb)",feed.id(),position,identity,time(observedAt),time(now),document);
        jdbc.update("UPDATE edgeai.workflow_source_feed SET head=? WHERE id=?",position,feed.id());
        jdbc.update("UPDATE edgeai.workflow_source_subscription SET state='GAP',gap_reason='BACKLOG_EXCEEDED',updated_at=? WHERE feed_id=? AND state='ACTIVE' AND drain_position IS NULL AND ?-acknowledged>?",
            time(now),feed.id(),position,limits.maxBacklog());
        if(position%64==0 || position-feed.floor()>limits.maxEntries())trim(new Feed(feed.id(),feed.kind(),position,feed.floor(),feed.trimmedObservedAt()),now,limits);
        return Append.APPENDED;
    }
    /** Consumers that still needed a trimmed position can no longer receive every observation. */
    private void gap(UUID feed,long through,Instant now) {
        jdbc.update("UPDATE edgeai.workflow_source_subscription SET state='GAP',gap_reason='BACKLOG_EXCEEDED',updated_at=? WHERE feed_id=? AND state='ACTIVE' AND acknowledged<? AND (drain_position IS NULL OR acknowledged<drain_position)",time(now),feed,through);
    }
    private void trim(Feed feed,Instant now,Limits limits) {
        if(feed.head()==feed.floor())return;
        Long protect=jdbc.queryForObject("SELECT min(s.acknowledged) FROM edgeai.workflow_source_subscription s JOIN edgeai.workflow_run r ON r.id=s.run_id WHERE s.feed_id=? AND s.state='ACTIVE' AND "+LIVE,Long.class,feed.id());
        // Writers take their acceptance time before the feed lock, so accepted_at is not monotonic in position: only the
        // prefix before the first unexpired entry has aged out.
        Long aged=jdbc.queryForObject("SELECT COALESCE(min(position)-1,(SELECT max(position) FROM edgeai.workflow_source_feed_entry WHERE feed_id=?)) FROM edgeai.workflow_source_feed_entry WHERE feed_id=? AND accepted_at>=?",
            Long.class,feed.id(),feed.id(),time(now.minusSeconds(limits.retentionSeconds())));
        long through=Math.min(protect==null?Long.MAX_VALUE:protect,aged==null?feed.floor():aged);
        through=Math.max(Math.max(through,feed.head()-limits.maxEntries()),feed.floor());
        if(through<=feed.floor())return;
        var trimmed=jdbc.queryForObject("SELECT max(observed_at) FROM edgeai.workflow_source_feed_entry WHERE feed_id=? AND position<=?",Timestamp.class,feed.id(),through);
        jdbc.update("DELETE FROM edgeai.workflow_source_feed_entry WHERE feed_id=? AND position<=?",feed.id(),through);
        jdbc.update("UPDATE edgeai.workflow_source_feed SET floor=?,trimmed_observed_at=CASE WHEN trimmed_observed_at IS NULL OR trimmed_observed_at<? THEN ? ELSE trimmed_observed_at END WHERE id=?",
            through,trimmed,trimmed,feed.id());
        gap(feed.id(),through,now);
    }
    public Optional<Subscription> subscription(UUID runId,String taskKey) {
        return jdbc.query("SELECT * FROM edgeai.workflow_source_subscription WHERE run_id=? AND task_key=?",SUBSCRIPTION,runId,taskKey).stream().findFirst();
    }
    public Optional<Page> read(Subscription s,long after,int limit) {
        if(limit<1 || limit>256)throw new IllegalArgumentException("Invalid feed page size");
        // One statement is one READ COMMITTED snapshot. A drain reads head under the feed lock, so an entry above a
        // watermark is only committed after that watermark, and is invisible unless the watermark is visible too.
        var rows=jdbc.queryForList("""
            SELECT f.head,f.floor,s.drain_position,s.state,s.gap_reason,e.position,e.identity,e.observed_at,e.document::text AS document
            FROM edgeai.workflow_source_subscription s JOIN edgeai.workflow_source_feed f ON f.id=s.feed_id
            LEFT JOIN LATERAL (SELECT position,identity,observed_at,document FROM edgeai.workflow_source_feed_entry x
                WHERE x.feed_id=f.id AND x.position>? AND x.position<=LEAST(f.head,COALESCE(s.drain_position,f.head)) ORDER BY x.position LIMIT ?) e ON true
            WHERE s.id=? ORDER BY e.position""",after,limit,s.id());
        if(rows.isEmpty())return Optional.empty();
        var first=rows.getFirst();var entries=new ArrayList<Entry>();
        for(var row:rows)if(row.get("position")!=null)entries.add(new Entry(((Number)row.get("position")).longValue(),(String)row.get("identity"),
            ((Timestamp)row.get("observed_at")).toInstant(),row.get("document").toString()));
        return Optional.of(new Page(((Number)first.get("head")).longValue(),((Number)first.get("floor")).longValue(),
            first.get("drain_position")==null?null:((Number)first.get("drain_position")).longValue(),(String)first.get("state"),(String)first.get("gap_reason"),entries));
    }
    public void acknowledge(Subscription s,long position,Instant now,Limits limits) {
        jdbc.update("UPDATE edgeai.workflow_source_subscription SET acknowledged=greatest(acknowledged,?),updated_at=? WHERE id=? AND state='ACTIVE'",position,time(now),s.id());
        // Readers never queue behind an in-flight append; the next append or acknowledgement trims instead.
        jdbc.query("SELECT * FROM edgeai.workflow_source_feed WHERE id=? FOR UPDATE SKIP LOCKED",FEED,s.feedId()).stream().findFirst().ifPresent(feed->trim(feed,now,limits));
    }
    public void drain(UUID runId,Instant now) {
        var feeds=jdbc.query("SELECT f.* FROM edgeai.workflow_source_feed f JOIN edgeai.workflow_source_subscription s ON s.feed_id=f.id WHERE s.run_id=? ORDER BY f.source_key FOR UPDATE OF f",FEED,runId);
        for(var feed:feeds)jdbc.update("UPDATE edgeai.workflow_source_subscription SET drain_position=COALESCE(drain_position,?),updated_at=? WHERE run_id=? AND feed_id=?",feed.head(),time(now),runId,feed.id());
    }
    public List<UUID> feeds(UUID after,int limit) {
        return after==null?jdbc.queryForList("SELECT id FROM edgeai.workflow_source_feed ORDER BY id LIMIT ?",UUID.class,limit)
            :jdbc.queryForList("SELECT id FROM edgeai.workflow_source_feed WHERE id>? ORDER BY id LIMIT ?",UUID.class,after,limit);
    }
    public void maintain(UUID id,Instant now,Limits limits) {
        jdbc.query("SELECT * FROM edgeai.workflow_source_feed WHERE id=? FOR UPDATE SKIP LOCKED",FEED,id).stream().findFirst().ifPresent(feed->trim(feed,now,limits));
    }
    public int expireSubscriptions(Instant now,Limits limits,int limit) {
        return jdbc.update("WITH expired AS (SELECT s.id FROM edgeai.workflow_source_subscription s JOIN edgeai.workflow_run r ON r.id=s.run_id WHERE r.state NOT IN ('PENDING','RUNNING') AND s.updated_at<? LIMIT ?) DELETE FROM edgeai.workflow_source_subscription s USING expired e WHERE s.id=e.id",
            time(now.minusSeconds(limits.retentionSeconds())),limit);
    }
    public void close(Subscription s,Instant now) {
        jdbc.update("UPDATE edgeai.workflow_source_subscription SET state='CLOSED',updated_at=? WHERE id=? AND state='ACTIVE'",time(now),s.id());
    }
}

package io.edgeai.adapters.repository;

import io.edgeai.domain.repository.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Daily partitions and normalized input provenance keep one-second results independent from chart aggregation. */
public final class JdbcProcessingHistoryRepository implements ProcessingHistoryRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final JsonMapper json=new JsonMapper();
    private final Set<LocalDate> partitions=java.util.concurrent.ConcurrentHashMap.newKeySet();
    public JdbcProcessingHistoryRepository(DataSource dataSource){jdbc=new JdbcTemplate(dataSource);jdbc.setQueryTimeout(2);transactions=new TransactionTemplate(new DataSourceTransactionManager(dataSource));transactions.setTimeout(3);}
    public boolean enabled(){return true;}
    private static Timestamp time(Instant value){return Timestamp.from(value);}
    public void append(List<Sample> samples) {
        if(samples.isEmpty())return;
        var days=new TreeSet<LocalDate>();samples.forEach(s->days.add(s.result().evaluationAt().atZone(ZoneOffset.UTC).toLocalDate()));
        try { transactions.executeWithoutResult(status->{
            for(var day:days)if(!partitions.contains(day))partition(day);
            jdbc.batchUpdate("INSERT INTO edgeai_history.evaluation(evaluation_at,vd_id,generation,sequence,sources,expires_at) VALUES(?,?,?,?,?::jsonb,?) ON CONFLICT DO NOTHING",samples,samples.size(),(p,s)->{
                var r=s.result();p.setTimestamp(1,time(r.evaluationAt()));p.setObject(2,r.vdId());p.setObject(3,r.generation());p.setLong(4,r.sequence());p.setString(5,s.sourcesJson());p.setTimestamp(6,time(r.evaluationAt().plusSeconds(86400L*s.retentionDays())));
            });
            jdbc.batchUpdate("INSERT INTO edgeai_history.result(evaluation_at,vd_id,output_id,generation,sequence,value,value_type,unit,expires_at) VALUES(?,?,?,?,?,?::jsonb,?,?,?) ON CONFLICT DO NOTHING",samples,samples.size(),(p,s)->{
                var r=s.result();p.setTimestamp(1,time(r.evaluationAt()));p.setObject(2,r.vdId());p.setString(3,r.outputId());p.setObject(4,r.generation());p.setLong(5,r.sequence());p.setString(6,json.writeValueAsString(s.value()));p.setString(7,s.type());p.setString(8,s.unit());p.setTimestamp(9,time(r.evaluationAt().plusSeconds(86400L*s.retentionDays())));
            });
        }); } catch(RuntimeException error) { partitions.removeAll(days);throw error; }
        partitions.addAll(days);
        partitions.removeIf(day->day.isBefore(Collections.max(days).minusDays(32)));
    }
    private void partition(LocalDate day) {
        String suffix=day.format(DateTimeFormatter.BASIC_ISO_DATE);
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",Object.class,"processing-history-partition:"+suffix);
        for(var parent:List.of("evaluation","result"))jdbc.execute("CREATE TABLE IF NOT EXISTS edgeai_history."+parent+"_"+suffix+" PARTITION OF edgeai_history."+parent+" FOR VALUES FROM ('"+day+" 00:00:00+00') TO ('"+day.plusDays(1)+" 00:00:00+00')");
    }
    public Map<String,Object> read(UUID id,String output,Instant from,Instant to,int limit,String cursor,Integer bucket) {
        if(bucket!=null) {
            if(cursor!=null)throw new IllegalArgumentException("Chart aggregates do not use raw-history cursors");
            var rows=jdbc.query("SELECT floor(extract(epoch FROM evaluation_at)/?)*? AS bucket,generation,count(*) AS count,min(CASE WHEN value_type='boolean' THEN CASE WHEN value='true'::jsonb THEN 1 ELSE 0 END ELSE (value::text)::numeric END) AS min,max(CASE WHEN value_type='boolean' THEN CASE WHEN value='true'::jsonb THEN 1 ELSE 0 END ELSE (value::text)::numeric END) AS max,avg(CASE WHEN value_type='boolean' THEN CASE WHEN value='true'::jsonb THEN 1 ELSE 0 END ELSE (value::text)::numeric END) AS average FROM edgeai_history.result WHERE vd_id=? AND output_id=? AND evaluation_at>=? AND evaluation_at<? AND expires_at>now() GROUP BY bucket,generation ORDER BY bucket,min(evaluation_at),generation LIMIT ?",
                (r,row)->Map.of("evaluationAt",Instant.ofEpochSecond(r.getLong("bucket")).toString(),"generation",r.getObject("generation",UUID.class).toString(),"count",r.getLong("count"),"min",r.getBigDecimal("min"),"max",r.getBigDecimal("max"),"average",r.getBigDecimal("average")),bucket,bucket,id,output,time(from),time(to),limit+1);
            if(rows.size()>limit)throw new IllegalArgumentException("Choose a larger chart bucket or narrower period");
            return Map.of("items",rows,"aggregation",Map.of("bucketSeconds",bucket),"truncated",false);
        }
        Instant after=from.minusMillis(1);UUID generation=new UUID(0,0);long sequence=0;
        if(cursor!=null)try {
            String[] pieces=new String(Base64.getUrlDecoder().decode(cursor),StandardCharsets.UTF_8).split("\\|",-1);if(pieces.length!=3)throw new IllegalArgumentException();
            after=Instant.parse(pieces[0]);generation=UUID.fromString(pieces[1]);sequence=Long.parseLong(pieces[2]);if(after.isBefore(from)||!after.isBefore(to)||sequence<1)throw new IllegalArgumentException();
        }catch(RuntimeException e){throw new IllegalArgumentException("Invalid history cursor");}
        var rows=jdbc.query("SELECT * FROM edgeai_history.result WHERE vd_id=? AND output_id=? AND evaluation_at>=? AND evaluation_at<? AND expires_at>now() AND (evaluation_at,generation,sequence)>(?,?,?) ORDER BY evaluation_at,generation,sequence LIMIT ?",(r,row)->{
            var item=new TreeMap<String,Object>();item.put("evaluationAt",r.getTimestamp("evaluation_at").toInstant().toString());item.put("generation",r.getObject("generation",UUID.class).toString());item.put("sequence",r.getLong("sequence"));item.put("evaluationId",item.get("generation")+":"+item.get("sequence"));item.put("value",json.readValue(r.getString("value"),Object.class));item.put("type",r.getString("value_type"));item.put("unit",r.getString("unit"));return item;
        },id,output,time(from),time(to),time(after),generation,sequence,limit+1);
        boolean more=rows.size()>limit;var result=new TreeMap<String,Object>();result.put("items",rows.stream().limit(limit).toList());result.put("truncated",more);
        if(more){var last=rows.get(limit-1);result.put("nextCursor",Base64.getUrlEncoder().withoutPadding().encodeToString((last.get("evaluationAt")+"|"+last.get("generation")+"|"+last.get("sequence")).getBytes(StandardCharsets.UTF_8)));}
        return result;
    }
    public int maintain(Instant now) {
        return transactions.execute(status->{int removed=0;
            for(var parent:List.of("result","evaluation")) {
                removed+=jdbc.update("WITH expired AS (SELECT tableoid,ctid FROM edgeai_history."+parent+" WHERE expires_at<=? LIMIT 10000) DELETE FROM edgeai_history."+parent+" p USING expired e WHERE p.tableoid=e.tableoid AND p.ctid=e.ctid",time(now));
                var names=jdbc.query("SELECT c.relname FROM pg_inherits i JOIN pg_class c ON i.inhrelid=c.oid JOIN pg_namespace n ON c.relnamespace=n.oid JOIN pg_class p ON i.inhparent=p.oid WHERE n.nspname='edgeai_history' AND p.relname=? ORDER BY c.relname",(r,row)->r.getString(1),parent);
                for(var name:names)if(name.matches(parent+"_[0-9]{8}")) {
                    var day=LocalDate.parse(name.substring(parent.length()+1),DateTimeFormatter.BASIC_ISO_DATE);
                    if(day.plusDays(31).atStartOfDay(ZoneOffset.UTC).toInstant().isBefore(now))jdbc.execute("DROP TABLE edgeai_history."+name);
                }
            }
            return removed;
        });
    }
}

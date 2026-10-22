package io.edgeai.adapters.repository;

import io.edgeai.domain.repository.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import tools.jackson.databind.json.JsonMapper;

/** OSS v2: native typed fields plus immutable provenance documents. No per-event tags. */
public final class InfluxTelemetryRepository implements ProcessingHistoryRepository,SensorTelemetryRepository,AutoCloseable {
    private final URI origin;
    private final String org,tokenFile,rawBucket,resultPrefix;
    private final int rawDays;
    private final boolean inspection;
    private final JsonMapper json=new JsonMapper();
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
    public InfluxTelemetryRepository(String url,String org,String tokenFile,String rawBucket,String resultPrefix,int rawDays,boolean inspection) {
        origin=url.isBlank()?null:URI.create(url);this.org=org;this.tokenFile=tokenFile;this.rawBucket=rawBucket;this.resultPrefix=resultPrefix;this.rawDays=rawDays;this.inspection=inspection;
        if(origin!=null && (!Set.of("http","https").contains(origin.getScheme()) || origin.getHost()==null || origin.getUserInfo()!=null || origin.getQuery()!=null || origin.getFragment()!=null || !Set.of("","/").contains(origin.getPath())))throw new IllegalArgumentException("Invalid InfluxDB origin");
        if(!rawBucket.matches("[a-z][a-z0-9-]{0,62}") || !resultPrefix.matches("[a-z][a-z0-9-]{0,50}") || rawDays<1 || rawDays>30)throw new IllegalArgumentException("Invalid telemetry bucket configuration");
    }
    public boolean enabled(){return origin!=null;}
    public int rawRetentionDays(){return rawDays;}
    private static String encode(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8);}
    private String quote(Object value){return json.writeValueAsString(value);}
    private static String tag(String value){if(value.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Invalid telemetry tag");return value.replace("\\","\\\\").replace(" ","\\ ").replace(",","\\,").replace("=","\\=");}
    private static String field(String value){return "\""+value.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r")+"\"";}
    private String request(String path,String contentType,String body) {
        if(!enabled())throw new IllegalStateException("Telemetry store is disabled");
        try {
            String token=Files.readString(Path.of(tokenFile)).trim();if(token.length()<16 || token.length()>4096)throw new IllegalStateException("Telemetry credential unavailable");
            var request=HttpRequest.newBuilder(origin.resolve(path)).timeout(Duration.ofSeconds(5)).header("Authorization","Token "+token).header("Content-Type",contentType).POST(HttpRequest.BodyPublishers.ofString(body)).build();
            var exchange=http.sendAsync(request,info->new BoundedBody());
            HttpResponse<byte[]> response;
            try{response=exchange.get(5,TimeUnit.SECONDS);}
            catch(TimeoutException e){exchange.cancel(true);throw new IllegalStateException("Telemetry response deadline exceeded",e);}
            catch(InterruptedException e){exchange.cancel(true);throw e;}
            if(response.statusCode()/100!=2)throw new IllegalStateException("Telemetry request failed ("+response.statusCode()+")");
            return new String(response.body(),StandardCharsets.UTF_8);
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("Telemetry request interrupted",e);}
        catch(IOException e){throw new IllegalStateException("Telemetry store unavailable",e);}
        catch(ExecutionException e){throw new IllegalStateException("Telemetry store unavailable",e.getCause());}
    }
    /** Completion represents the entire response, with a fixed memory bound and cancellable exchange. */
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body=new CompletableFuture<>();
        private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody(){return body;}
        public void onSubscribe(Flow.Subscription value){subscription=value;value.request(1);}
        public void onNext(List<ByteBuffer> chunks){
            for(var chunk:chunks){
                if(chunk.remaining()>8388608-bytes.size()){subscription.cancel();body.completeExceptionally(new IllegalStateException("Telemetry response limit exceeded"));return;}
                byte[] buffer=new byte[chunk.remaining()];chunk.get(buffer);bytes.writeBytes(buffer);
            }
            subscription.request(1);
        }
        public void onError(Throwable error){body.completeExceptionally(error);}
        public void onComplete(){body.complete(bytes.toByteArray());}
    }
    private void write(String bucket,String lines) {
        if(inspection)throw new IllegalStateException("Inspection is read-only");
        if(lines.getBytes(StandardCharsets.UTF_8).length>1048576)throw new IllegalArgumentException("Telemetry batch too large");
        request("/api/v2/write?org="+encode(org)+"&bucket="+encode(bucket)+"&precision=ns","text/plain; charset=utf-8",lines);
    }
    private static long nanos(Instant at){return Math.addExact(Math.multiplyExact(at.getEpochSecond(),1000000000L),at.getNano());}
    private static String typedValue(Object value) {
        if(value instanceof Boolean b)return "value_boolean="+b;
        if(value instanceof Number n && Double.isFinite(n.doubleValue()))return "value_numeric="+Double.toString(n.doubleValue());
        if(value instanceof String s)return "value_string="+field(s);
        throw new IllegalArgumentException("Unsupported telemetry value");
    }
    public void append(Observation observation) {
        var document=json.readValue(observation.documentJson(),Map.class);
        boolean qualified="QUALIFIED".equals(document.get("quality"));
        String configuration=Objects.toString(document.get("configurationSha256"),"unknown");
        // Legacy DataModels have no sequence. Reconcile only the exact original timestamp/type/value.
        // Keep both routes subscribed so CRD metadata never implies Mapper publishing capability.
        if(!qualified && matchingOriginal(observation,document,configuration,true))return;
        long sequence=document.get("sequence") instanceof Number n?n.longValue():0;
        String series=Objects.toString(document.get("sourceGeneration"),"legacy")+":"+Objects.toString(document.get("sessionId"),"legacy")+":"+(sequence/1000000);
        String tags=",device_uid="+observation.deviceUid()+",property="+tag(observation.property())+",series="+tag(series)+",configuration="+tag(Objects.toString(document.get("configurationSha256"),"unknown"));
        var metadata=new TreeMap<String,Object>();for(var key:List.of("deviceUid","deviceId","namespace","property","unit","type","observedAt","quality"))metadata.put(key,document.get(key));
        // Preserve distinct sequences at the same source millisecond without one tag per event.
        long storageTime=Math.addExact(nanos(observation.observedAt()),sequence%1000000);
        write(rawBucket,"sensor_original"+tags+" "+typedValue(document.get("value"))+",document="+field(observation.documentJson())+",series_document="+field(quote(metadata))+",archive_visible=true "+storageTime);
    }
    public boolean hideLegacyDuplicate(Observation observation) {
        var document=json.readValue(observation.documentJson(),Map.class);
        if(!"QUALIFIED".equals(document.get("quality")))throw new IllegalArgumentException("Only qualified originals supersede legacy originals");
        String configuration=Objects.toString(document.get("configurationSha256"),"unknown");
        if(!matchingOriginal(observation,document,configuration,false))return false;
        // InfluxDB2.9.1 DELETE can deadlock TSI compaction and block unrelated writes.
        // Hide only this exact legacy point through an idempotent write. Typed fields
        // expire with the bucket; history/catalog exclude the empty document and charts use the boolean visibility field.
        String legacyTags=",device_uid="+observation.deviceUid()+",property="+tag(observation.property())+",series=legacy:legacy:0,configuration="+tag(configuration);
        write(rawBucket,"sensor_original"+legacyTags+" document=\"\",series_document=\"\",archive_visible=false "+nanos(observation.observedAt()));
        return true;
    }
    private boolean matchingOriginal(Observation observation,Map<?,?> document,String configuration,boolean qualified) {
        String filter="r._measurement == \"sensor_original\" and r._field == \"document\" and r._value != \"\" and r.device_uid == "+quote(observation.deviceUid().toString())+" and r.property == "+quote(observation.property())+" and r.configuration == "+quote(configuration)+" and r.series "+(qualified?"!=":"==")+" \"legacy:legacy:0\"";
        String query=source(rawBucket,observation.observedAt(),observation.observedAt().plusMillis(1),filter)+" |> group(columns:[]) |> limit(n:1001)";
        var rows=query(query);if(rows.size()>1000)throw new IllegalStateException("Original reconciliation limit exceeded");
        for(var row:rows){var other=json.readValue(row.get("_value"),Map.class);Object a=document.get("value"),b=other.get("value");boolean same=a instanceof Number x&&b instanceof Number y?Double.compare(x.doubleValue(),y.doubleValue())==0:Objects.equals(a,b);if(same&&Objects.equals(document.get("type"),other.get("type"))&&Objects.equals(document.get("observedAt"),other.get("observedAt")))return true;}
        return false;
    }
    public void append(List<Sample> samples) {
        var batches=new TreeMap<Integer,List<String>>();
        for(var sample:samples) {
            if(sample.retentionDays()<1 || sample.retentionDays()>30)throw new IllegalArgumentException("Invalid result retention");
            var r=sample.result();var document=new TreeMap<String,Object>(json.readValue(r.json(),Map.class));document.put("historyStored",true);document.put("storageStatus","STORED");
            String line="vd_result,vd_id="+r.vdId()+",output_id="+tag(r.outputId())+",series="+r.generation()+" "+typedValue(sample.value())+",document="+field(quote(document))+" "+nanos(r.evaluationAt());
            batches.computeIfAbsent(sample.retentionDays(),day->new ArrayList<>()).add(line);
        }
        for(var entry:batches.entrySet())write(resultPrefix+entry.getKey()+"d",String.join("\n",entry.getValue()));
    }
    private String source(String bucket,Instant from,Instant to,String filter) {
        return "from(bucket:"+quote(bucket)+") |> range(start:time(v:"+quote(from.toString())+"), stop:time(v:"+quote(to.toString())+")) |> filter(fn:(r)=> "+filter+")";
    }
    private String resultsSource(Instant from,Instant to,String filter) {
        var sources=new ArrayList<String>();for(int day=1;day<=30;day++){var minimum=Instant.now().minusSeconds(day*86400L);var start=from.isBefore(minimum)?minimum:from;if(start.isBefore(to))sources.add(source(resultPrefix+day+"d",start,to,filter));}
        if(sources.isEmpty())return source(resultPrefix+"30d",to,to.plusNanos(1),"false");
        return "union(tables:["+String.join(",",sources)+"])";
    }
    private List<Map<String,String>> query(String flux) {
        String body=quote(Map.of("query",flux,"type","flux","dialect",Map.of("header",true,"delimiter",",","annotations",List.of())));
        return csv(request("/api/v2/query?org="+encode(org),"application/json",body));
    }
    /** Annotated CSV can contain quoted JSON, commas and newlines; header rows may repeat. */
    private static List<Map<String,String>> csv(String text) {
        var records=new ArrayList<List<String>>();var row=new ArrayList<String>();var cell=new StringBuilder();boolean quoted=false;
        for(int i=0;i<text.length();i++) {
            char c=text.charAt(i);
            if(c=='"'){if(quoted && i+1<text.length() && text.charAt(i+1)=='"'){cell.append('"');i++;}else quoted=!quoted;}
            else if(c==','&&!quoted){row.add(cell.toString());cell.setLength(0);}
            else if(c=='\n'&&!quoted){row.add(cell.toString());cell.setLength(0);records.add(List.copyOf(row));row.clear();}
            else if(c!='\r'||quoted)cell.append(c);
        }
        if(quoted)throw new IllegalStateException("Invalid telemetry CSV");
        if(!row.isEmpty()||!cell.isEmpty()){row.add(cell.toString());records.add(row);}
        var result=new ArrayList<Map<String,String>>();List<String> headers=List.of();
        for(var record:records) {
            // A query that fails after streaming starts (e.g. one of several yields) still answers 200 with an error table.
            if(record.equals(List.of("","error","reference")))throw new IllegalStateException("Telemetry query failed");
            if(record.contains("_time") && record.contains("_value")){headers=record;continue;}
            if(record.size()!=headers.size() || headers.isEmpty())continue;
            var mapped=new HashMap<String,String>();for(int i=0;i<headers.size();i++)mapped.put(headers.get(i),record.get(i));
            if(mapped.containsKey("_time"))result.add(mapped);
        }
        return result;
    }
    private Map<String,Object> documents(String source,Instant from,Instant to,int limit,String cursor) {
        return documents(source,from,to,limit,cursor,false);
    }
    private Map<String,Object> documents(String source,Instant from,Instant to,int limit,String cursor,boolean descending) {
        String continuation="";
        if(cursor!=null)try {
            String[] parts=new String(Base64.getUrlDecoder().decode(cursor),StandardCharsets.UTF_8).split("\\|",-1);
            int offset=descending?1:0;if(parts.length!=2+offset||descending&&!parts[0].equals("desc"))throw new IllegalArgumentException();
            Instant at=Instant.parse(parts[offset]);if(at.isBefore(from)||!at.isBefore(to)||parts[offset+1].length()>100)throw new IllegalArgumentException();
            String comparator=descending?" < ":" > ";
            continuation=" |> filter(fn:(r)=> r._time"+comparator+"time(v:"+quote(parts[offset])+") or (r._time == time(v:"+quote(parts[offset])+") and r.series"+comparator+quote(parts[offset+1])+"))";
        }catch(RuntimeException e){throw new IllegalArgumentException("Invalid telemetry cursor");}
        var rows=query(source+continuation+" |> group(columns:[]) |> sort(columns:[\"_time\",\"series\"],desc:"+descending+") |> limit(n:"+(limit+1)+")");
        var items=new ArrayList<Map<String,Object>>();
        for(var row:rows.stream().limit(limit).toList())items.add(json.readValue(row.get("_value"),Map.class));
        var result=new TreeMap<String,Object>();result.put("items",items);result.put("truncated",rows.size()>limit);
        if(rows.size()>limit){var last=rows.get(limit-1);result.put("nextCursor",Base64.getUrlEncoder().withoutPadding().encodeToString(((descending?"desc|":"")+last.get("_time")+"|"+last.get("series")).getBytes(StandardCharsets.UTF_8)));}
        return result;
    }
    public Map<String,Object> readSensor(UUID uid,String property,Instant from,Instant to,int limit,String cursor) {
        return readSensor(uid,property,from,to,limit,cursor,false);
    }
    @Override public Map<String,Object> readSensor(UUID uid,String property,Instant from,Instant to,int limit,String cursor,boolean descending) {
        var minimum=Instant.now().minusSeconds(rawDays*86400L);var start=from.isBefore(minimum)?minimum:from;
        if(!start.isBefore(to))return Map.of("items",List.of(),"truncated",false);
        return documents(source(rawBucket,start,to,"r._measurement == \"sensor_original\" and r._field == \"document\" and r._value != \"\" and r.device_uid == "+quote(uid.toString())+" and r.property == "+quote(property)),from,to,limit,cursor,descending);
    }
    public List<Map<String,Object>> sensorSeries() {
        var rows=query(source(rawBucket,Instant.now().minusSeconds(rawDays*86400L),Instant.now(),"r._measurement == \"sensor_original\" and r._field == \"series_document\" and r._value != \"\"")+" |> group(columns:[\"device_uid\",\"property\"]) |> last() |> group(columns:[]) |> limit(n:501)");
        var result=new ArrayList<Map<String,Object>>();for(var row:rows){var document=json.readValue(row.get("_value"),Map.class);var item=new TreeMap<String,Object>();for(var key:List.of("deviceUid","deviceId","namespace","property","unit","type","observedAt","quality"))item.put(key,document.get(key));result.add(item);}return result;
    }
    private static Instant originalBoundary(Instant at) {
        var millis=at.truncatedTo(java.time.temporal.ChronoUnit.MILLIS);return millis.isBefore(at)?millis.plusMillis(1):millis;
    }
    private static long epochNanos(String time){var at=Instant.parse(time);return Math.addExact(Math.multiplyExact(at.getEpochSecond(),1000000000L),at.getNano());}
    private record ChartPoint(long nanos,String identity,double value,boolean flag) {}
    private static ChartPoint chartPoint(Map<String,String> row) {
        double number=Double.parseDouble(row.get("_value"));if(!Double.isFinite(number))throw new IllegalStateException("Invalid original chart value");
        return new ChartPoint(epochNanos(row.get("_time")),row.get("series")+"|"+row.get("configuration"),number,"B".equals(row.get("kind")));
    }
    private static Map<String,Object> chartItem(ChartPoint point) {
        // The archive offsets sequences below one millisecond; the original source time is millisecond precision.
        return Map.of("observedAt",Instant.ofEpochMilli(Math.floorDiv(point.nanos(),1000000L)).toString(),"value",point.flag()?(Object)(point.value()!=0):point.value());
    }
    /**
     * Qualified series are always written with archive_visible=true in the same line, and the reconciliation write that hides a
     * point targets only the legacy series; so non-legacy value fields are read directly (storage pushdown of window selectors)
     * and only the legacy series pays for the pivot that applies archive_visible.
     */
    @Override public Map<String,Object> readSensorChart(UUID uid,String property,Instant from,Instant to,int windowSeconds,int pointBudget,boolean originalsWithinBudget) {
        var minimum=Instant.now().minusSeconds(rawDays*86400L);var start=originalBoundary(from.isBefore(minimum)?minimum:from);var stop=originalBoundary(to);
        long size=windowSeconds*1000000000L,first=Math.floorDiv(epochNanos(start.toString()),size),windows=start.isBefore(stop)?Math.floorDiv(epochNanos(stop.toString())-1,size)-first+1:0;
        var counts=new long[(int)windows];var candidates=new TreeMap<Long,List<ChartPoint>>();
        if(windows>0) {
            String sensor="r._measurement == \"sensor_original\" and r.device_uid == "+quote(uid.toString())+" and r.property == "+quote(property);
            String every=" |> window(every:"+windowSeconds+"s)";
            String point=" |> map(fn:(r)=> ({_time:r._time,_value:float(v:r._value),kind:KIND,series:r.series,configuration:r.configuration}))";
            String counted=" |> count() |> map(fn:(r)=> ({_time:r._start,_value:float(v:r._value),kind:\"C\",series:r.series,configuration:r.configuration}))";
            // Each yield reads its own source: a stream shared by several yields is not pushed down to storage and reads every original.
            String numeric=source(rawBucket,start,stop,sensor+" and r._field == \"value_numeric\" and r.series != \"legacy:legacy:0\"");
            String flag=sensor+" and r._field == \"value_boolean\" and r.series != \"legacy:legacy:0\"",flags=source(rawBucket,start,stop,flag);
            // InfluxDB 2.9.1 fails float(v:) of a pivoted boolean column ("filter: type conflict: bool != string"); select the number instead.
            String legacy=source(rawBucket,start,stop,sensor+" and r.series == \"legacy:legacy:0\" and (r._field == \"archive_visible\" or r._field == \"value_numeric\" or r._field == \"value_boolean\")")
                +" |> pivot(rowKey:[\"_time\"],columnKey:[\"_field\"],valueColumn:\"_value\") |> filter(fn:(r)=> (not exists r.archive_visible or r.archive_visible) and (exists r.value_numeric or exists r.value_boolean))"
                +" |> map(fn:(r)=> ({_start:r._start,_stop:r._stop,_time:r._time,series:r.series,configuration:r.configuration,_value:if exists r.value_boolean then (if r.value_boolean then 1.0 else 0.0) else float(v:r.value_numeric),kind:if exists r.value_boolean then \"B\" else \"N\"}))";
            var yields=new ArrayList<String>();
            for(var selector:List.of("first","last","min","max"))yields.add(numeric+every+" |> "+selector+"()"+point.replace("KIND","\"N\""));
            // Booleans have no min/max: the first false and first true originals are a window's extremes.
            // The value predicate stays inside the source filter so storage evaluates it.
            for(var data:List.of(flags+every+" |> last()",source(rawBucket,start,stop,flag+" and r._value == true")+every,source(rawBucket,start,stop,flag+" and r._value == false")+every))yields.add(data+" |> first()"+point.replace("KIND","\"B\""));
            yields.add(flags+every+" |> first()"+point.replace("KIND","\"B\""));
            // The legacy pivot cannot be pushed down, so it is evaluated once and shared by its five yields (repeating it per yield
            // took 5.2 s instead of 1.6 s over 10.5 days of real legacy originals).
            for(var selector:List.of("first","last","min","max"))yields.add("legacyRows"+every+" |> "+selector+"() |> keep(columns:[\"_time\",\"_value\",\"kind\",\"series\",\"configuration\"])");
            for(var data:List.of(numeric,flags,"legacyRows"))yields.add(data+every+counted);
            var flux=new StringBuilder("legacyRows = "+legacy+"\n");for(int index=0;index<yields.size();index++)flux.append(yields.get(index)).append(" |> yield(name:\"y").append(index).append("\")\n");
            for(var row:query(flux.toString())) {
                long nanos=epochNanos(row.get("_time")),index=Math.floorDiv(nanos,size)-first;
                if(index<0||index>=windows)throw new IllegalStateException("Original chart window out of range");
                if("C".equals(row.get("kind")))counts[(int)index]+=(long)Double.parseDouble(row.get("_value"));
                else candidates.computeIfAbsent(index,key->new ArrayList<>()).add(chartPoint(row));
            }
        }
        long total=Arrays.stream(counts).sum();
        var result=new TreeMap<String,Object>();result.put("truncated",false);result.put("totalObservations",total);
        if(originalsWithinBudget&&total<=pointBudget) {
            var rows=total==0?List.<Map<String,String>>of():query(source(rawBucket,start,stop,"r._measurement == \"sensor_original\" and (r._field == \"archive_visible\" or r._field == \"value_numeric\" or r._field == \"value_boolean\") and r.device_uid == "+quote(uid.toString())+" and r.property == "+quote(property))
                +" |> pivot(rowKey:[\"_time\"],columnKey:[\"_field\"],valueColumn:\"_value\") |> filter(fn:(r)=> (not exists r.archive_visible or r.archive_visible) and (exists r.value_numeric or exists r.value_boolean))"
                +" |> map(fn:(r)=> ({_start:r._start,_stop:r._stop,_time:r._time,series:r.series,configuration:r.configuration,_value:if exists r.value_boolean then (if r.value_boolean then 1.0 else 0.0) else float(v:r.value_numeric),kind:if exists r.value_boolean then \"B\" else \"N\"}))"
                +" |> group(columns:[\"_start\",\"_stop\"]) |> sort(columns:[\"_time\"]) |> limit(n:"+(pointBudget+1)+") |> keep(columns:[\"_time\",\"_value\",\"kind\",\"series\",\"configuration\"])");
            // Originals can arrive or be hidden between the two reads: a complete originals response counts what it returns, and
            // one that no longer fits falls back to the selection below (whose counts come from the same read as its points).
            if(rows.size()<=pointBudget){result.put("displayMode","ORIGINALS");result.put("totalObservations",(long)rows.size());result.put("items",rows.stream().map(InfluxTelemetryRepository::chartPoint).map(InfluxTelemetryRepository::chartItem).toList());return result;}
        }
        var items=new ArrayList<Map<String,Object>>();
        for(var window:candidates.values()) {
            var unique=new LinkedHashMap<String,ChartPoint>();
            var byTime=Comparator.comparingLong(ChartPoint::nanos).thenComparing(ChartPoint::identity);
            // Equal extremes resolve to the earliest original, as the storage selectors do.
            var lowest=Comparator.comparingDouble(ChartPoint::value).thenComparing(byTime);
            var highest=Comparator.comparingDouble(ChartPoint::value).thenComparing(byTime.reversed());
            for(var chosen:List.of(Collections.min(window,byTime),Collections.max(window,byTime),Collections.min(window,lowest),Collections.max(window,highest)))unique.put(chosen.nanos()+"|"+chosen.identity(),chosen);
            unique.values().stream().sorted(byTime).map(InfluxTelemetryRepository::chartItem).forEach(items::add);
        }
        result.put("displayMode","SELECTED_ORIGINALS");result.put("items",items);
        var selection=new TreeMap<String,Object>();selection.put("method","FIRST_MIN_MAX_LAST");selection.put("windowSeconds",windowSeconds);
        selection.put("bucketStart",Instant.ofEpochSecond(Math.multiplyExact(first,(long)windowSeconds)).toString());selection.put("counts",Arrays.stream(counts).boxed().toList());
        result.put("selection",selection);return result;
    }
    public Map<String,Object> read(UUID id,String output,Instant from,Instant to,int limit,String cursor,Integer bucket) {
        String filter="r._measurement == \"vd_result\" and r.vd_id == "+quote(id.toString())+" and r.output_id == "+quote(output);
        if(bucket==null)return documents(resultsSource(from,to,filter+" and r._field == \"document\""),from,to,limit,cursor);
        if(cursor!=null)throw new IllegalArgumentException("Chart aggregates do not use cursors");
        String base=resultsSource(from,to,filter+" and (r._field == \"value_numeric\" or r._field == \"value_boolean\")")+" |> map(fn:(r)=> ({r with _value:float(v:r._value)})) |> group(columns:[\"series\"])";
        var points=new TreeMap<String,Map<String,Object>>();
        for(var aggregate:List.of("mean","min","max","count")) {
            var rows=query(base+" |> aggregateWindow(every:"+bucket+"s,fn:"+aggregate+",createEmpty:false,timeSrc:\"_start\") |> group(columns:[]) |> sort(columns:[\"_time\",\"series\"]) |> limit(n:"+(limit+1)+")");
            if(rows.size()>limit)throw new IllegalArgumentException("Choose a larger chart bucket or narrower period");
            for(var row:rows){var point=points.computeIfAbsent(row.get("_time")+"|"+row.get("series"),key->new TreeMap<>(Map.of("evaluationAt",row.get("_time"),"generation",row.get("series"))));if(aggregate.equals("count"))point.put("count",Long.parseLong(row.get("_value")));else point.put(aggregate.equals("mean")?"average":aggregate,Double.parseDouble(row.get("_value")));}
        }
        return Map.of("items",points.values().stream().toList(),"aggregation",Map.of("bucketSeconds",bucket),"truncated",false);
    }
    /** Buckets enforce physical expiration; one result bucket for each supported retention period. */
    public int maintain(Instant now){return 0;}
    public void close(){http.close();}
}

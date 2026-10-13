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
        write(rawBucket,"sensor_original"+tags+" "+typedValue(document.get("value"))+",document="+field(observation.documentJson())+",series_document="+field(quote(metadata))+" "+storageTime);
        if(qualified && matchingOriginal(observation,document,configuration,false)) {
            String predicate="_measurement=\"sensor_original\" AND \"device_uid\"="+quote(observation.deviceUid().toString())+" AND \"property\"="+quote(observation.property())+" AND \"configuration\"="+quote(configuration)+" AND \"series\"=\"legacy:legacy:0\"";
            request("/api/v2/delete?org="+encode(org)+"&bucket="+encode(rawBucket),"application/json",quote(Map.of("start",observation.observedAt().toString(),"stop",observation.observedAt().plusNanos(1).toString(),"predicate",predicate)));
        }
    }
    private boolean matchingOriginal(Observation observation,Map<?,?> document,String configuration,boolean qualified) {
        String filter="r._measurement == \"sensor_original\" and r._field == \"document\" and r.device_uid == "+quote(observation.deviceUid().toString())+" and r.property == "+quote(observation.property())+" and r.configuration == "+quote(configuration)+" and r.series "+(qualified?"!=":"==")+" \"legacy:legacy:0\"";
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
            if(record.contains("_time") && record.contains("_value")){headers=record;continue;}
            if(record.size()!=headers.size() || headers.isEmpty())continue;
            var mapped=new HashMap<String,String>();for(int i=0;i<headers.size();i++)mapped.put(headers.get(i),record.get(i));
            if(mapped.containsKey("_time"))result.add(mapped);
        }
        return result;
    }
    private Map<String,Object> documents(String source,Instant from,Instant to,int limit,String cursor) {
        String continuation="";
        if(cursor!=null)try {
            String[] parts=new String(Base64.getUrlDecoder().decode(cursor),StandardCharsets.UTF_8).split("\\|",-1);if(parts.length!=2)throw new IllegalArgumentException();
            Instant at=Instant.parse(parts[0]);if(at.isBefore(from)||!at.isBefore(to)||parts[1].length()>100)throw new IllegalArgumentException();
            continuation=" |> filter(fn:(r)=> r._time > time(v:"+quote(parts[0])+") or (r._time == time(v:"+quote(parts[0])+") and r.series > "+quote(parts[1])+"))";
        }catch(RuntimeException e){throw new IllegalArgumentException("Invalid telemetry cursor");}
        var rows=query(source+continuation+" |> group(columns:[]) |> sort(columns:[\"_time\",\"series\"]) |> limit(n:"+(limit+1)+")");
        var items=new ArrayList<Map<String,Object>>();
        for(var row:rows.stream().limit(limit).toList())items.add(json.readValue(row.get("_value"),Map.class));
        var result=new TreeMap<String,Object>();result.put("items",items);result.put("truncated",rows.size()>limit);
        if(rows.size()>limit){var last=rows.get(limit-1);result.put("nextCursor",Base64.getUrlEncoder().withoutPadding().encodeToString((last.get("_time")+"|"+last.get("series")).getBytes(StandardCharsets.UTF_8)));}
        return result;
    }
    public Map<String,Object> readSensor(UUID uid,String property,Instant from,Instant to,int limit,String cursor) {
        var minimum=Instant.now().minusSeconds(rawDays*86400L);var start=from.isBefore(minimum)?minimum:from;
        if(!start.isBefore(to))return Map.of("items",List.of(),"truncated",false);
        return documents(source(rawBucket,start,to,"r._measurement == \"sensor_original\" and r._field == \"document\" and r.device_uid == "+quote(uid.toString())+" and r.property == "+quote(property)),from,to,limit,cursor);
    }
    public List<Map<String,Object>> sensorSeries() {
        var rows=query(source(rawBucket,Instant.now().minusSeconds(rawDays*86400L),Instant.now(),"r._measurement == \"sensor_original\" and r._field == \"series_document\"")+" |> group(columns:[\"device_uid\",\"property\"]) |> last() |> group(columns:[]) |> limit(n:501)");
        var result=new ArrayList<Map<String,Object>>();for(var row:rows){var document=json.readValue(row.get("_value"),Map.class);var item=new TreeMap<String,Object>();for(var key:List.of("deviceUid","deviceId","namespace","property","unit","type","observedAt","quality"))item.put(key,document.get(key));result.add(item);}return result;
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

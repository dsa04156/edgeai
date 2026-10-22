package io.edgeai.app.service;

import io.edgeai.app.exception.ControlPlaneException;
import io.edgeai.domain.repository.SensorTelemetryRepository;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class DataStoreService {
    private final SensorTelemetryRepository telemetry;
    private final Clock clock;
    private final SensorArchiveCollector collector;
    public DataStoreService(SensorTelemetryRepository telemetry,Clock clock,SensorArchiveCollector collector){this.telemetry=telemetry;this.clock=clock;this.collector=collector;}
    public Map<String,Object> catalog() {
        var result=new TreeMap<String,Object>();result.put("engine","InfluxDB");result.put("configured",telemetry.enabled());result.put("rawRetentionDays",telemetry.rawRetentionDays());result.put("asOf",clock.instant().toString());result.put("collector",collector.state());
        if(!telemetry.enabled()){result.put("status","DISABLED");result.put("sensors",List.of());result.put("truncated",false);return result;}
        try{var rows=telemetry.sensorSeries();result.put("status","READY");result.put("sensors",rows.stream().limit(500).toList());result.put("truncated",rows.size()>500);return result;}
        catch(RuntimeException e){throw unavailable();}
    }
    public Map<String,Object> history(UUID uid,String property,String fromText,String toText,int limit,String cursor) {
        return history(uid,property,fromText,toText,limit,cursor,"asc");
    }
    public Map<String,Object> history(UUID uid,String property,String fromText,String toText,int limit,String cursor,String order) {
        if(!property.matches("[a-z][a-z0-9_]{0,62}"))throw new IllegalArgumentException("Invalid measurement property");
        if(!"asc".equals(order)&&!"desc".equals(order))throw new IllegalArgumentException("Invalid measurement order");
        Instant to=toText==null?clock.instant():parseTime(toText),from=fromText==null?to.minusSeconds(3600):parseTime(fromText);
        if(!from.isBefore(to)||Duration.between(from,to).compareTo(Duration.ofDays(31))>0||limit<1||limit>2000||cursor!=null&&cursor.length()>256)throw new IllegalArgumentException("Invalid measurement period or limit");
        try{var rows=new TreeMap<>(order.equals("desc")?telemetry.readSensor(uid,property,from,to,limit,cursor,true):telemetry.readSensor(uid,property,from,to,limit,cursor));rows.put("deviceUid",uid.toString());rows.put("property",property);rows.put("from",from.toString());rows.put("to",to.toString());rows.put("retentionDays",telemetry.rawRetentionDays());return rows;}
        catch(IllegalArgumentException e){throw e;}catch(RuntimeException e){throw unavailable();}
    }
    /** Point budgets the chart accepts; a client without one gets the default. */
    public static final int MIN_POINTS=200,MAX_POINTS=2000,DEFAULT_POINTS=1000;
    /** Epoch-aligned selection windows (each divides a UTC day), so windows of overlapping requests coincide. */
    static final int[] WINDOWS={1,2,5,10,15,30,60,120,300,600,900,1800,3600,7200,10800,21600,43200,86400};
    /** Windows touched by [from,to), counting partial first and last windows; each contributes at most four points. */
    static long windows(Instant from,Instant to,int seconds){long size=seconds*1000000000L;return Math.floorDiv(nanos(to)-1,size)-Math.floorDiv(nanos(from),size)+1;}
    private static long nanos(Instant at){return Math.addExact(Math.multiplyExact(at.getEpochSecond(),1000000000L),at.getNano());}
    public Map<String,Object> chart(UUID uid,String property,String fromText,String toText) {
        return chart(uid,property,fromText,toText,null,null);
    }
    /**
     * {@code points} bounds the returned items (default {@value #DEFAULT_POINTS}). Without {@code window} the narrowest window whose
     * four-point selection fits is chosen and originals are returned unchanged when they fit; with {@code window} (a live tail that
     * replaces whole windows of an earlier response) that window is always used.
     */
    public Map<String,Object> chart(UUID uid,String property,String fromText,String toText,Integer points,Integer window) {
        if(!property.matches("[a-z][a-z0-9_]{0,62}"))throw new IllegalArgumentException("Invalid measurement property");
        Instant to=toText==null?clock.instant():parseTime(toText),from=fromText==null?to.minusSeconds(3600):parseTime(fromText);
        Duration period=Duration.between(from,to);
        if(!from.isBefore(to)||period.compareTo(Duration.ofDays(31))>0)throw new IllegalArgumentException("Invalid measurement period");
        int budget=points==null?DEFAULT_POINTS:points;
        if(budget<MIN_POINTS||budget>MAX_POINTS)throw new IllegalArgumentException("Invalid chart point budget");
        int seconds;
        if(window==null)seconds=java.util.Arrays.stream(WINDOWS).filter(w->windows(from,to,w)*4<=budget).findFirst().orElseThrow();
        else if(java.util.Arrays.stream(WINDOWS).anyMatch(w->w==window)&&windows(from,to,window)*4<=budget)seconds=window;
        else throw new IllegalArgumentException("Invalid chart selection window");
        try {
            var rows=new TreeMap<>(telemetry.readSensorChart(uid,property,from,to,seconds,budget,window==null));
            rows.put("deviceUid",uid.toString());rows.put("property",property);rows.put("from",from.toString());rows.put("to",to.toString());rows.put("retentionDays",telemetry.rawRetentionDays());rows.put("pointBudget",budget);
            return rows;
        }catch(RuntimeException e){throw unavailable();}
    }
    private static ControlPlaneException unavailable(){return new ControlPlaneException(503,"HISTORY_STORE_UNAVAILABLE","InfluxDB 저장소를 확인하세요.");}
    private static Instant parseTime(String text){try{return Instant.parse(text);}catch(java.time.format.DateTimeParseException e){throw new IllegalArgumentException("Invalid measurement timestamp");}}
}

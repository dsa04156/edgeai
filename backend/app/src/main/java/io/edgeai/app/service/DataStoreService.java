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
    private static ControlPlaneException unavailable(){return new ControlPlaneException(503,"HISTORY_STORE_UNAVAILABLE","InfluxDB 저장소를 확인하세요.");}
    private static Instant parseTime(String text){try{return Instant.parse(text);}catch(java.time.format.DateTimeParseException e){throw new IllegalArgumentException("Invalid measurement timestamp");}}
}

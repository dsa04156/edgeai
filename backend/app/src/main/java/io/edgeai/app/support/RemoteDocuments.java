package io.edgeai.app.support;
import io.edgeai.domain.remote.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
/** Stable internal work identity, independently checked against the actual reference HTTP adapter in tests. */
public final class RemoteDocuments {
    private static final JsonDocuments JSON=new JsonDocuments();
    private RemoteDocuments(){}
    private static Map<String,Object> identity(RemoteIdentity i){return Map.of("allocationId",i.allocationId().toString(),"runId",i.runId().toString(),"taskId",i.taskId().toString(),"attemptId",i.attemptId().toString(),"epoch",i.epoch());}
    private static Map<String,Object> file(RemoteFile f){return Map.of("port",f.port(),"bytes",f.bytes(),"sha256",f.sha256(),"mediaType",f.mediaType());}
    private static Map<String,Object> work(RemoteWork w){return Map.of("apiVersion","edgeai.remote.reference/v1","identity",identity(w.identity()),"serviceSpec",JSON.decode(w.serviceSpecJson()),"parameters",JSON.decode(w.parametersJson()),"inputs",w.inputs().stream().sorted(Comparator.comparing(RemoteFile::port)).map(RemoteDocuments::file).toList(),"expiresAt",w.expiresAt().toString());}
    public static String workJson(RemoteWork w){return JSON.boundedCanonical(work(w),262144);}
    public static String digest(RemoteWork w){return JSON.digest("edgeai-reference-allocation-v1",work(w));}
    public static RemoteWork readWork(String text){
        var m=(Map<?,?>)JSON.decode(text);var i=(Map<?,?>)m.get("identity");
        var id=new RemoteIdentity(uuid(i.get("allocationId")),uuid(i.get("runId")),uuid(i.get("taskId")),uuid(i.get("attemptId")),integer(i.get("epoch")));
        var inputs=((List<?>)m.get("inputs")).stream().map(v->{var f=(Map<?,?>)v;return new RemoteFile((String)f.get("port"),integer(f.get("bytes")),(String)f.get("sha256"),(String)f.get("mediaType"));}).toList();
        return new RemoteWork(id,JSON.canonical(m.get("serviceSpec")),JSON.canonical(m.get("parameters")),inputs,Instant.parse((String)m.get("expiresAt")));
    }
    public static String statusJson(RemoteStatus s){
        var m=new TreeMap<String,Object>();m.put("identity",identity(s.identity()));m.put("revision",s.revision());m.put("requestDigest",s.requestDigest());m.put("state",s.state().name());
        m.put("expiresAt",s.expiresAt()==null?null:s.expiresAt().toString());m.put("failureReason",s.failureReason());m.put("sourceMode",s.sourceMode());m.put("outputs",s.outputs().stream().map(RemoteDocuments::file).toList());return JSON.boundedCanonical(m,262144);
    }
    private static UUID uuid(Object v){return UUID.fromString((String)v);}
    private static long integer(Object v){return new BigDecimal(v.toString()).longValueExact();}
}

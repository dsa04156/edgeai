package io.edgeai.app.support;

import io.edgeai.domain.stream.*;
import io.edgeai.domain.stream.StreamBrokerGateway.Permission;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import tools.jackson.core.*;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.json.JsonMapper;
import static io.edgeai.app.support.WorkflowInput.*;

/** One frame at a time: never materialize the full 72 MiB checkpoint in the API heap. */
public final class StreamCheckpointDocument {
    public record Summary(long revision,String json){}
    private final MessageDigest canonical;
    private final JsonDocuments json=new JsonDocuments();
    private final JsonMapper mapper=JsonMapper.builder(JsonFactory.builder().streamReadConstraints(StreamReadConstraints.builder()
        .maxNestingDepth(16).maxStringLength(1398104).maxNumberLength(16).build()).build())
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    private final Map<String,Permission> permissions=new HashMap<>();
    private final Map<String,Object> bindings=new HashMap<>();
    private int routeCount,maxFrames,maxBuffer,maxState;
    private StreamCheckpointDocument(List<Permission> routes){
        try{canonical=MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
        for(var p:routes){
            var r=p.route();var g=p.generation();permissions.put(r.id().toString(),p);
            Object producer=r.deviceSource()?Map.of("kind","DEVICE_SESSION","deviceId",r.sourceDeviceId().toString(),"sessionId",g.producer().id().toString(),"epoch",g.producer().epoch())
                :Map.of("kind","TASK_ATTEMPT","attemptId",g.producer().id().toString(),"epoch",g.producer().epoch());
            bindings.put(r.id().toString(),Map.of("routeId",r.id().toString(),"generation",g.generation(),"producer",producer));
        }
        routeCount=routes.size();
    }
    public static Summary verify(Path file,StreamCheckpoint.Request request,List<Permission> routes,UUID attempt){
        try{
            check(Files.size(file)==request.bytes());
            return new StreamCheckpointDocument(routes).read(file,request,attempt);
        }catch(IOException|RuntimeException e){throw new IllegalArgumentException("Invalid stream checkpoint document");}
    }
    private Summary read(Path file,StreamCheckpoint.Request request,UUID attempt)throws IOException {
        try(var p=mapper.createParser(file)){
            check(p.nextToken()==JsonToken.START_OBJECT);emit("{");
            var version=field(p,"apiVersion",64,2);check(version.equals("edgeai.stream-checkpoint/v1"));emitField("apiVersion",version);emit(",");
            var execution=field(p,"executionSha256",64,2);check(execution.equals(request.executionSha256()));emitField("executionSha256",execution);emit(",");
            var manifest=object(field(p,"manifest",4096,512),"version","inputs","outputs","limits");
            check(number(manifest.get("version"),1,1)==1);
            var limits=object(manifest.get("limits"),"max_frames","max_buffer_bytes","max_state_bytes");
            maxFrames=(int)number(limits.get("max_frames"),routeCount,4096);
            maxBuffer=(int)number(limits.get("max_buffer_bytes"),routeCount,67108864);
            maxState=(int)number(limits.get("max_state_bytes"),1,1048576);
            var inputs=new ArrayList<Object>();var outputs=new ArrayList<Object>();
            permissions.keySet().stream().sorted().forEach(id->{var permission=permissions.get(id);
                (permission.generation().consumer().id().equals(attempt)?inputs:outputs).add(bindings.get(id));});
            check(inputs.size()<=16 && outputs.size()<=16);
            check(json.canonical(inputs).equals(json.canonical(manifest.get("inputs"))) && json.canonical(outputs).equals(json.canonical(manifest.get("outputs"))));
            emitField("manifest",manifest);emit(",");
            long revision=number(field(p,"revision",32,2),0,request.serial());emitField("revision",revision);emit(",\"routes\":[");
            name(p,"routes");check(p.currentToken()==JsonToken.START_ARRAY);
            var seen=new HashSet<String>();var cursors=new ArrayList<Object>();
            while(p.nextToken()!=JsonToken.END_ARRAY){
                check(seen.size()<routeCount && p.currentToken()==JsonToken.START_OBJECT);
                if(!seen.isEmpty())emit(",");emit("{");
                long committed=number(field(p,"committed",32,2),0,9007199254740991L);emitField("committed",committed);emit(",");
                Object ended=field(p,"ended",8,2);check(ended instanceof Boolean);boolean terminal=(Boolean)ended;emitField("ended",ended);emit(",\"frames\":[");
                name(p,"frames");check(p.currentToken()==JsonToken.START_ARRAY);
                long count=0,bytes=0;String frameRoute=null;boolean endSeen=false;
                while(p.nextToken()!=JsonToken.END_ARRAY){
                    check(!endSeen && count<maxFrames/routeCount && p.currentToken()==JsonToken.START_OBJECT);
                    var frame=object(small(p,349528,new int[]{32}),"apiVersion","routeId","generation","producer","sequence","kind","mediaType","payloadBase64","sha256");
                    String identity=text(frame.get("routeId"),36);var permission=permissions.get(identity);check(permission!=null);
                    check(frameRoute==null || frameRoute.equals(identity));frameRoute=identity;
                    check("edgeai.stream/v1".equals(frame.get("apiVersion")) && number(frame.get("sequence"),1,9007199254740991L)==committed+count+1);
                    var binding=object(bindings.get(identity),"routeId","generation","producer");
                    check(number(frame.get("generation"),1,9007199254740991L)==permission.generation().generation());
                    check(json.canonical(binding.get("producer")).equals(json.canonical(frame.get("producer"))));
                    byte[] payload=blob(frame.get("payloadBase64"),permission.route().maxPayloadBytes());
                    check(HexFormat.of().formatHex(hash(payload)).equals(text(frame.get("sha256"),64)));
                    if("END".equals(frame.get("kind"))){check(terminal && payload.length==0 && frame.get("mediaType")==null);endSeen=true;}
                    else check("DATA".equals(frame.get("kind")) && permission.route().mediaType().equals(frame.get("mediaType")));
                    String encoded=json.canonical(frame);check(encoded.length()<=524288);bytes+=encoded.length();check(bytes<=maxBuffer/routeCount);
                    if(count++>0)emit(",");emit(encoded);
                }
                emit("],");long received=number(field(p,"received",32,2),committed,9007199254740991L);emitField("received",received);emit(",");
                String identity=text(field(p,"routeId",36,2),36);check(permissions.containsKey(identity) && seen.add(identity));
                check(frameRoute==null || frameRoute.equals(identity));check(received-committed==count);
                check(!terminal || received>0);check(count==0 || terminal==endSeen);emitField("routeId",identity);emit("}");
                check(p.nextToken()==JsonToken.END_OBJECT);
                cursors.add(Map.of("routeId",identity,"received",received,"committed",committed,"ended",terminal));
            }
            check(seen.size()==routeCount);emit("],");
            long serial=number(field(p,"serial",32,2),0,9007199254740991L);check(serial==request.serial());emitField("serial",serial);emit(",");
            Object state=field(p,"stateBase64",1398104,2);byte[] stateBytes=blob(state,maxState);emitField("stateBase64",state);emit("}");
            check(p.nextToken()==JsonToken.END_OBJECT && p.nextToken()==null);
            check(HexFormat.of().formatHex(canonical.digest()).equals(request.sha256()));
            return new Summary(revision,json.boundedCanonical(Map.of("manifest",manifest,"revision",revision,"routes",cursors,
                "stateSha256",HexFormat.of().formatHex(hash(stateBytes)),"stateBytes",stateBytes.length),65536));
        }
    }
    private static void name(JsonParser p,String name){check(p.nextToken()==JsonToken.PROPERTY_NAME && name.equals(p.currentName()));check(p.nextToken()!=null);}
    private Object field(JsonParser p,String name,int length,int nodes){name(p,name);return small(p,length,new int[]{nodes});}
    private Object small(JsonParser p,int length,int[] budget){
        check(--budget[0]>=0);var token=p.currentToken();
        if(token==JsonToken.VALUE_STRING){String value=p.getString();check(value.length()<=length);return value;}
        if(token==JsonToken.VALUE_NUMBER_INT)return p.getLongValue();
        if(token==JsonToken.VALUE_TRUE)return true;if(token==JsonToken.VALUE_FALSE)return false;if(token==JsonToken.VALUE_NULL)return null;
        if(token==JsonToken.START_ARRAY){var values=new ArrayList<Object>();while(p.nextToken()!=JsonToken.END_ARRAY)values.add(small(p,length,budget));return values;}
        if(token==JsonToken.START_OBJECT){var values=new TreeMap<String,Object>();while(p.nextToken()!=JsonToken.END_OBJECT){
            check(p.currentToken()==JsonToken.PROPERTY_NAME);String name=p.currentName();check(name.length()<=64 && !values.containsKey(name));
            check(p.nextToken()!=null);values.put(name,small(p,length,budget));}return values;}
        throw new IllegalArgumentException("Invalid checkpoint token");
    }
    private static long number(Object value,long min,long max){long n=RunnerInput.integer(value);check(n>=min && n<=max);return n;}
    private static byte[] blob(Object value,int maximum){
        check(value instanceof String);String encoded=(String)value;check(encoded.length()<=4*((maximum+2)/3));
        byte[] bytes=Base64.getDecoder().decode(encoded);check(bytes.length<=maximum && Base64.getEncoder().encodeToString(bytes).equals(encoded));return bytes;
    }
    private static byte[] hash(byte[] value){try{return MessageDigest.getInstance("SHA-256").digest(value);}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private void emit(String value){canonical.update(value.getBytes(StandardCharsets.US_ASCII));}
    private void emitField(String name,Object value){emit("\""+name+"\":"+json.canonical(value));}
    private static void check(boolean condition){if(!condition)throw new IllegalArgumentException("Invalid stream checkpoint document");}
}

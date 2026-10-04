package io.edgeai.adapters.stream;

import io.edgeai.domain.stream.*;
import io.edgeai.domain.stream.StreamBrokerGateway.*;
import io.edgeai.domain.stream.RouteGeneration.BrokerReceipt;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.security.*;
import java.security.cert.CertificateFactory;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.*;
import org.eclipse.paho.mqttv5.client.*;
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence;
import org.eclipse.paho.mqttv5.common.*;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import static io.edgeai.domain.stream.StreamBrokerException.Reason.*;

/** Mosquitto 2.0 dynamic-security adapter. Grant only creates immutable roles; revoke leaves empty
 * tombstones, so a delayed create/addClientRole cannot resurrect an already revoked generation.
 * Requires a dedicated broker with deny defaults. No native TTL is implied by this adapter. */
public final class MosquittoStreamBroker implements StreamBrokerGateway {
    private static final String COMMAND="$CONTROL/dynamic-security/v1", RESPONSE=COMMAND+"/response";
    private final URI endpoint;private final String username,digest;private final Path passwordFile,keyFile;
    private final SSLSocketFactory sockets;private final Clock clock;private final String keyDigest;
    private final JsonMapper json=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    public MosquittoStreamBroker(String endpoint,String username,Path passwordFile,Path caFile,Path keyFile,String digest,Clock clock){
        this.endpoint=URI.create(endpoint);this.username=Objects.requireNonNull(username);this.passwordFile=Objects.requireNonNull(passwordFile);
        this.keyFile=Objects.requireNonNull(keyFile);this.digest=digest;this.clock=Objects.requireNonNull(clock);RouteGeneration.digest(digest);
        if(this.endpoint.getHost()==null || this.endpoint.getPort()<1 || this.endpoint.getPort()>65535 || this.endpoint.getUserInfo()!=null
            || !this.endpoint.getPath().isEmpty() || this.endpoint.getQuery()!=null || this.endpoint.getFragment()!=null
            || !(this.endpoint.getScheme().equals("ssl") || this.endpoint.getScheme().equals("tcp") && "127.0.0.1".equals(this.endpoint.getHost()))
            || !username.matches("[A-Za-z0-9_-]{1,128}"))throw new StreamBrokerException(CONFIGURATION);
        this.sockets=this.endpoint.getScheme().equals("ssl")?tls(Objects.requireNonNull(caFile)):null;
        this.keyDigest=keyDigest(privateFile(keyFile));
    }
    /** Call only after Pod/Device authentication and current generation checks. Never serialize/log this object. */
    public Credential credential(Principal principal){
        try {
            byte[] key=privateFile(keyFile);if(!keyDigest.equals(keyDigest(key)))throw new StreamBrokerException(CONFIGURATION);
            key=HexFormat.of().parseHex(new String(key,StandardCharsets.US_ASCII));
            var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));Arrays.fill(key,(byte)0);
            var secret=HexFormat.of().formatHex(mac.doFinal(("edgeai-mqtt-principal-v1\n"+digest+"\n"+principal.username()).getBytes(StandardCharsets.UTF_8)));
            return new Credential(principal.username(),secret.getBytes(StandardCharsets.US_ASCII));
        }catch(StreamBrokerException e){throw e;}catch(Exception e){throw new StreamBrokerException(CONFIGURATION);}
    }
    public static final class Credential {
        private final String username;private final byte[] password;
        private Credential(String username,byte[] password){this.username=username;this.password=password;}
        public String username(){return username;}
        public byte[] password(){return password.clone();}
        @Override public String toString(){return "StreamCredential[redacted]";}
    }
    @Override public BrokerReceipt grant(Permission permission){
        check(permission);var g=permission.generation();
        if(g.fencedAt()!=null || !clock.instant().isBefore(g.leaseUntil()))throw new StreamBrokerException(REVOKED);
        try(var connection=new Connection()){
            defaults(connection);
            for(boolean producer:List.of(true,false)){
                String role=permission.role(producer);var acls=acls(permission,producer);
                var create=connection.call("createRole",Map.of("rolename",role,"textname","edgeai-stream-v1","textdescription",g.policyDigest(),"acls",acls));
                error(create,"Role already exists");
                verifyRole(connection,permission,producer,false);
                var principal=producer?permission.producer():permission.consumer();var credential=credential(principal);
                var made=connection.call("createClient",Map.of("username",credential.username(),"clientid",credential.username(),
                    "password",new String(credential.password(),StandardCharsets.US_ASCII),"textname","edgeai-stream-v1","textdescription",digest+":"+keyDigest));
                error(made,"Client already exists");
                var existing=object(object(connection.checked("getClient",Map.of("username",credential.username())).get("data")).get("client"));
                if(!credential.username().equals(existing.get("clientid")) || !(digest+":"+keyDigest).equals(existing.get("textdescription"))
                    || Boolean.TRUE.equals(existing.get("disabled")) || !list(existing.get("groups")).isEmpty())throw new StreamBrokerException(CONFLICT);
                if(!member(existing,role)){
                    // Mosquitto 2.0 reports duplicate membership as "Internal error". A racing
                    // retry may already have attached it; prove the exact membership by readback.
                    error(connection.call("addClientRole",Map.of("username",credential.username(),"rolename",role,"priority",0)),"Internal error");
                    var observed=object(object(connection.checked("getClient",Map.of("username",credential.username())).get("data")).get("client"));
                    if(!member(observed,role))throw new StreamBrokerException(REJECTED);
                }
            }
            // A concurrent revoke can happen at any point. Its tombstones can never be granted again.
            verifyRole(connection,permission,true,false);verifyRole(connection,permission,false,false);
            if(!clock.instant().isBefore(g.leaseUntil()))throw new StreamBrokerException(REVOKED);
            return permission.receipt();
        }
    }
    @Override public BrokerReceipt revoke(Permission permission){
        check(permission);
        try(var connection=new Connection()){
            defaults(connection);
            for(boolean producer:List.of(true,false)){
                var name=permission.role(producer);
                error(connection.call("createRole",Map.of("rolename",name,"textname","edgeai-stream-v1","textdescription",permission.generation().policyDigest(),"acls",List.of())),"Role already exists");
                var role=role(connection,name);
                if(!permission.generation().policyDigest().equals(role.get("textdescription")))throw new StreamBrokerException(CONFLICT);
                connection.checked("modifyRole",Map.of("rolename",name,"acls",List.of()));
                verifyRole(connection,permission,producer,true);
            }
            return permission.receipt();
        }
    }
    private void check(Permission p){if(!digest.equals(p.generation().brokerDigest()))throw new StreamBrokerException(CONFLICT);}
    private void defaults(Connection c){
        var data=object(c.checked("getDefaultACLAccess",Map.of()).get("data"));var acls=list(data.get("acls"));
        var found=new HashSet<String>();
        for(var item:acls){var acl=object(item);if(!Boolean.FALSE.equals(acl.get("allow")))throw new StreamBrokerException(CONFIGURATION);found.add((String)acl.get("acltype"));}
        if(!found.equals(Set.of("publishClientSend","publishClientReceive","subscribe","unsubscribe")))throw new StreamBrokerException(CONFIGURATION);
    }
    private List<Map<String,Object>> acls(Permission p,boolean producer){
        String send=p.topic(producer?"frames":"acks"),receive=p.topic(producer?"acks":"frames");
        return List.of(acl("publishClientSend",send),acl("publishClientReceive",receive),acl("subscribeLiteral",receive),acl("unsubscribeLiteral",receive));
    }
    private static Map<String,Object> acl(String type,String topic){return Map.of("acltype",type,"topic",topic,"allow",true,"priority",0);}
    private static boolean member(Map<?,?> client,String role){
        for(var entry:list(client.get("roles"))){var r=object(entry);if(role.equals(r.get("rolename"))){if(!Integer.valueOf(0).equals(r.get("priority")))throw new StreamBrokerException(CONFLICT);return true;}}
        return false;
    }
    private Map<?,?> role(Connection c,String name){return object(object(c.checked("getRole",Map.of("rolename",name)).get("data")).get("role"));}
    private void verifyRole(Connection c,Permission p,boolean producer,boolean revoked){
        var r=role(c,p.role(producer));
        if(!p.generation().policyDigest().equals(r.get("textdescription")))throw new StreamBrokerException(CONFLICT);
        var actual=list(r.get("acls"));if(revoked){if(!actual.isEmpty())throw new StreamBrokerException(CONFLICT);return;}
        if(actual.isEmpty())throw new StreamBrokerException(REVOKED);
        if(actual.size()!=4 || !new HashSet<>(actual).equals(new HashSet<>(acls(p,producer))))throw new StreamBrokerException(CONFLICT);
    }
    private static Map<?,?> object(Object value){if(!(value instanceof Map<?,?> m))throw new StreamBrokerException(INVALID_RESPONSE);return m;}
    private static List<?> list(Object value){if(!(value instanceof List<?> l))throw new StreamBrokerException(INVALID_RESPONSE);return l;}
    private static void error(Map<?,?> response,String allowed){if(response.containsKey("error") && !Objects.equals(response.get("error"),allowed))throw new StreamBrokerException(REJECTED);}

    private record PendingResponse(String correlation,ArrayBlockingQueue<Map<?,?>> replies) {}
    private final class Connection implements AutoCloseable {
        private MqttClient client;
        private final AtomicReference<PendingResponse> expected=new AtomicReference<>();private final AtomicReference<StreamBrokerException> failure=new AtomicReference<>();
        Connection(){
            try {
                client=new MqttClient(endpoint.toString(),"edgeai-control-"+UUID.randomUUID(),new MemoryPersistence());client.setTimeToWait(3000);
                var options=new MqttConnectionOptions();options.setUserName(username);options.setPassword(privateFile(passwordFile));
                options.setCleanStart(true);options.setSessionExpiryInterval(0L);options.setAutomaticReconnect(false);options.setConnectionTimeout(3);
                options.setReceiveMaximum(8);options.setMaximumPacketSize(65536L);options.setHttpsHostnameVerificationEnabled(true);
                if(sockets!=null)options.setSocketFactory(sockets);
                client.connect(options);
                // Avoid Paho 1.2.5's recursive String[]/int[]/listener[] overload.
                var sub=new MqttSubscription(RESPONSE,1);sub.setRetainHandling(2);sub.setRetainAsPublished(true);
                var token=client.subscribe(new MqttSubscription[]{sub},new IMqttMessageListener[]{(topic,message)->{
                    if(message.getPayload().length>65536 || message.isRetained()){failure.set(new StreamBrokerException(INVALID_RESPONSE));return;}
                    try {
                        var root=object(json.readValue(message.getPayload(),Object.class));
                        // The caller can finish or start its next command while this callback runs.
                        // Capture once and keep delayed replies bound to that request's own queue.
                        var pending=expected.get();
                        for(var entry:list(root.get("responses"))){var response=object(entry);if(pending!=null && pending.correlation().equals(response.get("correlationData")))pending.replies().offer(response);}
                    }catch(RuntimeException invalid){failure.set(new StreamBrokerException(INVALID_RESPONSE));}
                }});
                for(int code:token.getReasonCodes())if(code>=128)throw new StreamBrokerException(REJECTED);
            }catch(Exception error){close();throw error instanceof StreamBrokerException s?s:new StreamBrokerException(UNAVAILABLE);}
        }
        Map<?,?> call(String command,Map<String,?> values){
            var correlation=UUID.randomUUID().toString();var pending=new PendingResponse(correlation,new ArrayBlockingQueue<>(1));
            if(!expected.compareAndSet(null,pending))throw new StreamBrokerException(CONFLICT);
            var body=new HashMap<String,Object>(values);body.put("command",command);body.put("correlationData",correlation);
            try {
                byte[] bytes=json.writeValueAsBytes(Map.of("commands",List.of(body)));if(bytes.length>32768)throw new StreamBrokerException(CONFIGURATION);
                client.publish(COMMAND,bytes,1,false);
                var reply=pending.replies().poll(3,TimeUnit.SECONDS);
                if(failure.get()!=null)throw failure.get();if(reply==null)throw new StreamBrokerException(UNAVAILABLE);
                if(!command.equals(reply.get("command")) || !correlation.equals(reply.get("correlationData")))throw new StreamBrokerException(INVALID_RESPONSE);return reply;
            }catch(InterruptedException e){Thread.currentThread().interrupt();throw new StreamBrokerException(UNAVAILABLE);}
            catch(StreamBrokerException e){throw e;}catch(Exception e){throw new StreamBrokerException(UNAVAILABLE);}
            finally{expected.compareAndSet(pending,null);}
        }
        Map<?,?> checked(String name,Map<String,?> values){var r=call(name,values);error(r,null);return r;}
        public void close(){if(client!=null){try{client.disconnectForcibly(0,100,false);}catch(Exception ignored){}try{client.close(true);}catch(Exception ignored){}}}
    }
    private static byte[] privateFile(Path path){
        try(var channel=Files.newByteChannel(path,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)){
            var permissions=Files.getPosixFilePermissions(path,LinkOption.NOFOLLOW_LINKS);
            if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS) || permissions.stream().anyMatch(p->p!=PosixFilePermission.OWNER_READ && p!=PosixFilePermission.OWNER_WRITE)
                || channel.size()<1 || channel.size()>4096)throw new StreamBrokerException(CONFIGURATION);
            var buffer=java.nio.ByteBuffer.allocate(4097);while(channel.read(buffer)>0){}buffer.flip();if(buffer.remaining()>4096)throw new StreamBrokerException(CONFIGURATION);var bytes=new byte[buffer.remaining()];buffer.get(bytes);return bytes;
        }catch(Exception e){throw new StreamBrokerException(CONFIGURATION);}
    }
    private static String keyDigest(byte[] key){
        if(key.length!=64 || !new String(key,StandardCharsets.US_ASCII).matches("[a-f0-9]{64}"))throw new StreamBrokerException(CONFIGURATION);
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key));}catch(Exception e){throw new StreamBrokerException(CONFIGURATION);}
    }
    private static SSLSocketFactory tls(Path ca){
        try(var stream=Files.newInputStream(ca)){
            var store=KeyStore.getInstance(KeyStore.getDefaultType());store.load(null,null);int count=0;
            for(var cert:CertificateFactory.getInstance("X.509").generateCertificates(stream))store.setCertificateEntry("ca-"+count++,cert);
            if(count==0)throw new IllegalArgumentException();var trust=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());trust.init(store);
            var context=SSLContext.getInstance("TLS");context.init(null,trust.getTrustManagers(),null);return context.getSocketFactory();
        }catch(Exception e){throw new StreamBrokerException(CONFIGURATION);}
    }
}

package io.edgeai.adapters.remote;

import io.edgeai.domain.remote.*;
import java.io.*;
import java.math.BigDecimal;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;
import java.security.cert.CertificateFactory;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.*;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import static io.edgeai.domain.remote.RemoteGatewayException.Reason.*;

/** EdgeAI reference protocol only. This is not an implementation of an unprovided 2세부 API. */
public final class ReferenceRemoteGateway implements RemoteGateway {
    private static final int LIMIT=262144;
    private final URI origin;
    private final Path tokenFile;
    private final Duration timeout;
    private final String sourceMode;
    private final HttpClient client;
    private final JsonMapper json=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    public ReferenceRemoteGateway(String origin,Path tokenFile,Path caFile,Duration timeout,String sourceMode) {
        this.origin=URI.create(origin.replaceAll("/+$",""));this.tokenFile=Objects.requireNonNull(tokenFile);this.timeout=timeout;this.sourceMode=sourceMode;
        if(this.origin.getHost()==null || this.origin.getUserInfo()!=null || this.origin.getQuery()!=null || this.origin.getFragment()!=null || !this.origin.getPath().isEmpty() ||
            !(this.origin.getScheme().equals("https") || this.origin.getScheme().equals("http") && Set.of("127.0.0.1","[::1]").contains(this.origin.getHost())))
            throw new IllegalArgumentException("Remote origin requires HTTPS; only literal loopback may use HTTP");
        if(timeout==null || timeout.toMillis()<1 || timeout.compareTo(Duration.ofSeconds(30))>0 || !Set.of("SYNTHETIC","EXTERNAL").contains(sourceMode))
            throw new IllegalArgumentException("Bounded timeout and explicit provider source mode required");
        var builder=HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).version(HttpClient.Version.HTTP_1_1);
        if(caFile!=null)builder.sslContext(tls(caFile));client=builder.build();
    }
    @Override public RemoteStatus reserve(RemoteWork work) {
        byte[] body;
        try {
            var spec=json.readValue(work.serviceSpecJson(),Object.class);var parameters=json.readValue(work.parametersJson(),Object.class);
            if(!(spec instanceof Map<?,?>) || !(parameters instanceof Map<?,?>))throw new IllegalArgumentException();
            body=canonical(Map.of("apiVersion","edgeai.remote.reference/v1","identity",identity(work.identity()),"serviceSpec",spec,"parameters",parameters,
                "inputs",work.inputs().stream().sorted(Comparator.comparing(RemoteFile::port)).map(ReferenceRemoteGateway::file).toList(),"expiresAt",work.expiresAt().toString()),0).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            if(body.length>LIMIT)throw new IllegalArgumentException();
        } catch(RuntimeException error){throw new RemoteGatewayException(INVALID_INPUT);}
        var digest="sha256:"+HexFormat.of().formatHex(hash(("edgeai-reference-allocation-v1\n"+new String(body,java.nio.charset.StandardCharsets.UTF_8)).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var result=metadata(request(work.identity(),"").header("Content-Type","application/json").header("X-EdgeAI-Request-Digest",digest).PUT(HttpRequest.BodyPublishers.ofByteArray(body)),work.identity(),false).orElseThrow();
        if(!digest.equals(result.requestDigest()) || !work.expiresAt().equals(result.expiresAt()))throw new RemoteGatewayException(INVALID_RESPONSE);
        return result;
    }
    @Override public RemoteStatus uploadInput(RemoteIdentity identity,RemoteFile input,Path source) {
        try {
            if(!Files.isRegularFile(source,LinkOption.NOFOLLOW_LINKS) || Files.size(source)!=input.bytes())throw new RemoteGatewayException(INTEGRITY_FAILED);
            var digest=sha();try(var stream=Files.newInputStream(source)){byte[] buffer=new byte[65536];for(int n;(n=stream.read(buffer))!=-1;)digest.update(buffer,0,n);}
            if(!HexFormat.of().formatHex(digest.digest()).equals(input.sha256()))throw new RemoteGatewayException(INTEGRITY_FAILED);
            return metadata(request(identity,"/inputs/"+input.port()).header("Content-Type",input.mediaType())
                .PUT(HttpRequest.BodyPublishers.ofFile(source)),identity,false).orElseThrow();
        }catch(IOException e){throw new RemoteGatewayException(INVALID_INPUT);}
    }
    @Override public RemoteStatus start(RemoteIdentity identity){return metadata(request(identity,"/start").POST(HttpRequest.BodyPublishers.noBody()),identity,false).orElseThrow();}
    @Override public RemoteStatus start(RemoteStart authority){
        var body=new TreeMap<String,Object>();body.put("apiVersion","edgeai.remote.start/v1");body.put("identity",identity(authority.identity()));
        body.put("requestDigest",authority.requestDigest());body.put("expiresAt",authority.expiresAt().toString());
        body.put("offloadId",authority.offloadId()==null?null:authority.offloadId().toString());body.put("startDeadline",authority.startDeadline()==null?null:authority.startDeadline().toString());
        return metadata(request(authority.identity(),"/start").header("Content-Type","application/json")
            .POST(HttpRequest.BodyPublishers.ofString(canonical(body,0))),authority.identity(),false).orElseThrow();
    }
    @Override public Optional<RemoteStatus> inspect(RemoteIdentity identity){return metadata(request(identity,"").GET(),identity,true);}
    @Override public RemoteStatus cancel(RemoteIdentity identity){return metadata(request(identity,"/cancel").POST(HttpRequest.BodyPublishers.noBody()),identity,false).orElseThrow();}
    @Override public void downloadOutput(RemoteIdentity identity,RemoteFile output,Path destination) {
        Path temporary=null;
        try {
            Path absolute=destination.toAbsolutePath();if(Files.exists(absolute,LinkOption.NOFOLLOW_LINKS))throw new RemoteGatewayException(INVALID_INPUT);
            temporary=Files.createTempFile(absolute.getParent(),".edgeai-remote-",".part",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            final Path target=temporary;
            var response=send(request(identity,"/outputs/"+output.port()).GET(),info->info.statusCode()==200?new FileBody(target,output):HttpResponse.BodySubscribers.mapping(new LimitedBody(),bytes->(Path)null));
            status(response.statusCode(),false);
            if(response.statusCode()!=200)throw new RemoteGatewayException(INVALID_RESPONSE);
            if(!response.headers().firstValue("Content-Type").orElse("").equals(output.mediaType()))throw new RemoteGatewayException(INVALID_RESPONSE);
            // Atomic no-overwrite publication on the same filesystem, including a concurrent creator.
            Files.createLink(absolute,temporary);
        }catch(RemoteGatewayException e){throw e;}
        catch(Exception e){throw new RemoteGatewayException(INTEGRITY_FAILED);}
        finally {if(temporary!=null)try{Files.deleteIfExists(temporary);}catch(IOException ignored){}}
    }
    private HttpRequest.Builder request(RemoteIdentity identity,String suffix) {
        String token;
        try{token=Files.readString(tokenFile).strip();if(!token.matches("[A-Za-z0-9_-]{32,256}"))throw new IOException();}
        catch(IOException e){throw new RemoteGatewayException(AUTH_REJECTED);}
        return HttpRequest.newBuilder(origin.resolve("/reference/v1/allocations/"+identity.allocationId()+suffix)).timeout(timeout)
            .header("Authorization","Bearer "+token).header("Accept","application/json")
            .header("X-EdgeAI-Run-Id",identity.runId().toString()).header("X-EdgeAI-Task-Id",identity.taskId().toString())
            .header("X-EdgeAI-Attempt-Id",identity.attemptId().toString()).header("X-EdgeAI-Epoch",Long.toString(identity.epoch()));
    }
    private Optional<RemoteStatus> metadata(HttpRequest.Builder request,RemoteIdentity expected,boolean missingAllowed) {
        var reply=send(request,info->new LimitedBody());if(!status(reply.statusCode(),missingAllowed))return Optional.empty();
        try {
            if(!reply.headers().firstValue("Content-Type").orElse("").split(";",2)[0].strip().equals("application/json"))throw new IllegalArgumentException();
            var value=object(json.readValue(reply.body(),Object.class),"identity","revision","requestDigest","state","expiresAt","failureReason","sourceMode","outputs");
            var id=object(value.get("identity"),"allocationId","runId","taskId","attemptId","epoch");
            var actual=new RemoteIdentity(uuid(id.get("allocationId")),uuid(id.get("runId")),uuid(id.get("taskId")),uuid(id.get("attemptId")),integer(id.get("epoch")));
            if(!expected.equals(actual) || !sourceMode.equals(value.get("sourceMode")))throw new IllegalArgumentException();
            if(!(value.get("outputs") instanceof List<?> list))throw new IllegalArgumentException();
            var outputs=new ArrayList<RemoteFile>();for(var item:list){var f=object(item,"port","bytes","sha256","mediaType");outputs.add(new RemoteFile((String)f.get("port"),integer(f.get("bytes")),(String)f.get("sha256"),(String)f.get("mediaType")));}
            return Optional.of(new RemoteStatus(actual,integer(value.get("revision")),(String)value.get("requestDigest"),RemoteStatus.State.valueOf((String)value.get("state")),
                value.get("expiresAt")==null?null:Instant.parse((String)value.get("expiresAt")),(String)value.get("failureReason"),(String)value.get("sourceMode"),outputs));
        }catch(RuntimeException e){throw new RemoteGatewayException(INVALID_RESPONSE);}
    }
    private <T> HttpResponse<T> send(HttpRequest.Builder request,HttpResponse.BodyHandler<T> handler) {
        CompletableFuture<HttpResponse<T>> pending=null;
        try {pending=client.sendAsync(request.build(),handler);return pending.get(timeout.toMillis(),TimeUnit.MILLISECONDS);}
        catch(InterruptedException e){Thread.currentThread().interrupt();if(pending!=null)pending.cancel(true);throw new RemoteGatewayException(UNAVAILABLE);}
        catch(Exception e){if(pending!=null)pending.cancel(true);Throwable cause=e;for(int i=0;i<5 && cause!=null;i++,cause=cause.getCause())if(cause instanceof RemoteGatewayException remote)throw remote;throw new RemoteGatewayException(UNAVAILABLE);}
    }
    private static boolean status(int status,boolean missingAllowed) {
        if(status==200 || status==201 || status==202)return true;if(status==404 && missingAllowed)return false;
        throw new RemoteGatewayException(switch(status){case 401,403->AUTH_REJECTED;case 404->NOT_FOUND;case 409->CONFLICT;case 422->UNSUPPORTED;case 400,413->INVALID_INPUT;default->status>=500 || status==429?UNAVAILABLE:INVALID_RESPONSE;});
    }
    private static Map<String,Object> identity(RemoteIdentity i){return Map.of("allocationId",i.allocationId().toString(),"runId",i.runId().toString(),"taskId",i.taskId().toString(),"attemptId",i.attemptId().toString(),"epoch",i.epoch());}
    private static Map<String,Object> file(RemoteFile f){return Map.of("port",f.port(),"bytes",f.bytes(),"sha256",f.sha256(),"mediaType",f.mediaType());}
    private static Map<?,?> object(Object value,String... fields){if(!(value instanceof Map<?,?> map) || !map.keySet().equals(Set.of(fields)))throw new IllegalArgumentException();return map;}
    private static UUID uuid(Object value){var id=UUID.fromString((String)value);if(!id.toString().equals(value))throw new IllegalArgumentException();return id;}
    private static long integer(Object value){if(!(value instanceof Number n))throw new IllegalArgumentException();return new BigDecimal(n.toString()).longValueExact();}
    private String canonical(Object value,int depth) {
        if(depth>32)throw new IllegalArgumentException();if(value==null)return "null";if(value instanceof Boolean)return value.toString();
        if(value instanceof String text){
            for(int i=0;i<text.length();i++) {
                char c=text.charAt(i);if(c==0)throw new IllegalArgumentException();
                if(Character.isHighSurrogate(c)){if(++i>=text.length() || !Character.isLowSurrogate(text.charAt(i)))throw new IllegalArgumentException();}
                else if(Character.isLowSurrogate(c))throw new IllegalArgumentException();
            }
            return json.writeValueAsString(text);
        }
        if(value instanceof Number n){var number=new BigDecimal(n.toString()).stripTrailingZeros();if(Math.abs((long)number.scale())>1000 || number.precision()>1000)throw new IllegalArgumentException();return number.signum()==0?"0":number.toPlainString();}
        if(value instanceof List<?> list){var out=new StringJoiner(",","[","]");list.forEach(v->out.add(canonical(v,depth+1)));return out.toString();}
        if(value instanceof Map<?,?> map){var sorted=new TreeMap<String,Object>();map.forEach((k,v)->sorted.put((String)k,v));var out=new StringJoiner(",","{","}");sorted.forEach((k,v)->out.add(canonical(k,depth+1)+":"+canonical(v,depth+1)));return out.toString();}
        throw new IllegalArgumentException();
    }
    private static MessageDigest sha(){try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException e){throw new IllegalStateException();}}
    private static byte[] hash(byte[] bytes){return sha().digest(bytes);}
    private static SSLContext tls(Path ca) {
        try(var stream=Files.newInputStream(ca)) {
            var store=KeyStore.getInstance(KeyStore.getDefaultType());store.load(null,null);int count=0;
            for(var certificate:CertificateFactory.getInstance("X.509").generateCertificates(stream))store.setCertificateEntry("ca-"+count++,certificate);
            if(count==0)throw new IllegalArgumentException();var trust=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());trust.init(store);
            var context=SSLContext.getInstance("TLS");context.init(null,trust.getTrustManagers(),null);return context;
        }catch(Exception e){throw new IllegalArgumentException("Cannot configure Remote CA");}
    }
    @Override public void close(){client.close();}
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate=HttpResponse.BodySubscribers.ofByteArray();private Flow.Subscription subscription;private long bytes;
        public CompletionStage<byte[]> getBody(){return delegate.getBody();}public void onSubscribe(Flow.Subscription s){subscription=s;delegate.onSubscribe(s);}
        public void onNext(List<ByteBuffer> items){for(var b:items)bytes+=b.remaining();if(bytes>LIMIT){subscription.cancel();delegate.onError(new RemoteGatewayException(INVALID_RESPONSE));}else delegate.onNext(items);}
        public void onError(Throwable error){delegate.onError(error);}public void onComplete(){delegate.onComplete();}
    }
    private static final class FileBody implements HttpResponse.BodySubscriber<Path> {
        private final HttpResponse.BodySubscriber<Path> delegate;private final RemoteFile expected;private final MessageDigest digest=sha();private Flow.Subscription subscription;private long bytes;
        FileBody(Path file,RemoteFile expected){this.expected=expected;delegate=HttpResponse.BodySubscribers.ofFile(file,StandardOpenOption.WRITE,StandardOpenOption.TRUNCATE_EXISTING);}
        public CompletionStage<Path> getBody(){return delegate.getBody();}public void onSubscribe(Flow.Subscription s){subscription=s;delegate.onSubscribe(s);}
        public void onNext(List<ByteBuffer> items){for(var b:items){bytes+=b.remaining();digest.update(b.asReadOnlyBuffer());}if(bytes>expected.bytes()){subscription.cancel();delegate.onError(new RemoteGatewayException(INTEGRITY_FAILED));}else delegate.onNext(items);}
        public void onError(Throwable error){delegate.onError(error);}
        public void onComplete(){if(bytes!=expected.bytes() || !HexFormat.of().formatHex(digest.digest()).equals(expected.sha256()))delegate.onError(new RemoteGatewayException(INTEGRITY_FAILED));else delegate.onComplete();}
    }
}

package io.edgeai.adapters.metrics;

import io.edgeai.domain.node.SensorAccessSource;
import static io.edgeai.domain.node.SensorAccessSource.Failure.Kind.*;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public final class EdgeXSensorAccess implements SensorAccessSource, AutoCloseable {
    private final URI metadata, data, command;
    private final Clock clock;
    private final JsonMapper json=new JsonMapper();
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
    public EdgeXSensorAccess(String metadata, String data, String command, Clock clock) {
        this.metadata=origin(metadata); this.data=origin(data); this.command=origin(command); this.clock=clock;
    }
    private static URI origin(String value) {
        URI uri=URI.create(value.replaceAll("/+$", ""));
        if (!Set.of("http","https").contains(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null
            || !uri.getPath().isEmpty() || uri.getQuery()!=null || uri.getFragment()!=null) throw new IllegalArgumentException("EdgeX URL must be an HTTP(S) origin");
        return uri;
    }
    private static String name(String value) {
        if (value==null || !value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) throw new Failure(INVALID,"센서·측정 항목·명령 이름을 확인하세요.");
        return value;
    }
    private JsonNode request(URI origin,String path,String method,Object body) {
        try {
            var builder=HttpRequest.newBuilder(origin.resolve(path)).timeout(Duration.ofSeconds(5)).header("Accept","application/json");
            if (method.equals("GET")) builder.GET();
            else builder.header("Content-Type","application/json").method(method,HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            var response=client.send(builder.build(), ignored -> new LimitedBody());
            if (response.statusCode()==404) throw new Failure(NOT_FOUND,"EdgeX 센서 또는 명령을 찾을 수 없습니다.");
            if (response.statusCode()==400) throw new Failure(INVALID,"EdgeX가 명령 값을 거절했습니다. 입력값과 장치 규격을 확인하세요.");
            if (response.statusCode()==423) throw new Failure(LOCKED,"EdgeX 장치가 잠겨 있습니다.");
            if (response.statusCode()!=200) throw new Failure(UNAVAILABLE,"EdgeX 응답을 확인할 수 없습니다. 명령은 자동 재시도하지 않습니다.");
            var root=json.readTree(response.body());
            if (root==null || root.path("statusCode").asInt()!=200) throw new Failure(UNAVAILABLE,"EdgeX 응답 형식을 확인할 수 없습니다.");
            return root;
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new Failure(UNAVAILABLE,"EdgeX 요청이 중단됐습니다. 명령 결과를 확인하세요."); }
        catch (IOException | tools.jackson.core.JacksonException e) { throw new Failure(UNAVAILABLE,"EdgeX 연결에 실패했습니다. 명령 결과를 확인한 뒤 재시도하세요."); }
    }
    private JsonNode device(String device) {
        var found=request(metadata,"/api/v3/device/name/"+name(device),"GET",null).path("device");
        if (!found.path("name").asText().equals(device)) throw new Failure(UNAVAILABLE,"EdgeX 센서 응답이 일치하지 않습니다.");
        return found;
    }
    @Override public Readings readings(String device,String resource,int limit) {
        name(device); if (limit<1 || limit>500) throw new Failure(INVALID,"조회 개수는 1~500이어야 합니다.");
        if (resource!=null && !resource.isEmpty()) name(resource);
        device(device);
        var root=request(data,"/api/v3/reading/device/name/"+device+(resource==null || resource.isEmpty()?"":"/resourceName/"+resource)+"?limit="+limit,"GET",null);
        var values=decode(root.path("readings"),device);
        if (values.size()>limit || (resource!=null && !resource.isEmpty() && values.stream().anyMatch(r->!r.resource().equals(resource)))) throw new Failure(UNAVAILABLE,"EdgeX 측정 응답이 조회 범위를 벗어났습니다.");
        return new Readings(device,clock.instant(),values);
    }
    private List<Reading> decode(JsonNode readings,String device) {
        if (!readings.isArray() || readings.size()>500) throw new Failure(UNAVAILABLE,"EdgeX 측정 응답을 확인할 수 없습니다.");
        var values=new ArrayList<Reading>();
        for (var row:readings) {
            if (!row.path("deviceName").asText().equals(device) || !row.path("origin").canConvertToLong() || row.path("origin").asLong()<=0) throw new Failure(UNAVAILABLE,"EdgeX 측정 시각·장치 정보가 올바르지 않습니다.");
            long ns=row.path("origin").asLong();
            String type=row.path("valueType").asText();
            String value=type.equals("Binary")?"[binary]":row.has("value")?row.path("value").asText():row.has("objectValue")?row.path("objectValue").toString():"";
            values.add(new Reading(row.path("resourceName").asText(),type,value,row.path("units").asText(),Instant.ofEpochSecond(ns/1_000_000_000L,ns%1_000_000_000L)));
        }
        return values.stream().sorted(Comparator.comparing(Reading::observedAt).thenComparing(Reading::resource)).toList();
    }
    @Override public Commands commands(String device) {
        var registered=device(device);
        var root=request(command,"/api/v3/device/name/"+device,"GET",null).path("deviceCoreCommand");
        if (!root.path("deviceName").asText().equals(device) || !root.path("coreCommands").isArray() || root.path("coreCommands").size()>128) throw new Failure(UNAVAILABLE,"EdgeX 명령 목록이 올바르지 않습니다.");
        var commands=new ArrayList<Command>();
        for (var item:root.path("coreCommands")) {
            var parameters=new ArrayList<Parameter>();
            for (var p:item.path("parameters")) parameters.add(new Parameter(name(p.path("resourceName").asText()),p.path("valueType").asText()));
            commands.add(new Command(name(item.path("name").asText()),item.path("get").asBoolean(),item.path("set").asBoolean(),List.copyOf(parameters)));
        }
        return new Commands(device,registered.path("adminState").asText(),registered.path("operatingState").asText(),List.copyOf(commands));
    }
    @Override public CommandResult execute(String device,String requested,String method,Map<String,String> values) {
        name(requested);
        var available=commands(device);
        if (!available.adminState().equals("UNLOCKED")) throw new Failure(LOCKED,"잠긴 센서에는 명령을 보낼 수 없습니다.");
        var matches=available.commands().stream().filter(c->c.name().equals(requested)).toList();
        if (matches.size()!=1) throw new Failure(NOT_FOUND,"등록된 명령을 찾을 수 없습니다.");
        var selected=matches.getFirst();
        if (!("GET".equals(method) && selected.readable() || "PUT".equals(method) && selected.writable())) throw new Failure(INVALID,"센서가 지원하지 않는 명령 방식입니다.");
        if (values==null) values=Map.of();
        if (method.equals("GET") && !values.isEmpty()) throw new Failure(INVALID,"읽기 명령에는 값을 전달할 수 없습니다.");
        if (method.equals("PUT")) {
            var expected=new HashSet<String>(); selected.parameters().forEach(p->expected.add(p.resource()));
            if (expected.isEmpty() || expected.size()>64 || !expected.equals(values.keySet()) || values.values().stream().anyMatch(v->v==null || v.length()>4096)) throw new Failure(INVALID,"명령에 필요한 측정 항목별 값을 입력하세요.");
        }
        // Ignore upstream url/path fields: only the configured Core Command origin is trusted.
        var result=request(command,"/api/v3/device/name/"+device+"/"+requested+(method.equals("GET")?"?ds-pushevent=false&ds-returnevent=true":""),method,values);
        return new CommandResult(device,requested,method,clock.instant(),method.equals("GET")?decode(result.path("event").path("readings"),device):List.of());
    }
    @Override public void close() { client.close(); }
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate=HttpResponse.BodySubscribers.ofByteArray();
        private Flow.Subscription subscription; private long size;
        public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
        public void onSubscribe(Flow.Subscription value) { subscription=value; delegate.onSubscribe(value); }
        public void onNext(List<ByteBuffer> values) {
            for (var value:values) size+=value.remaining();
            if(size>2*1024*1024) { subscription.cancel();delegate.onError(new IOException("EdgeX response too large")); } else delegate.onNext(values);
        }
        public void onError(Throwable error) {delegate.onError(error);} public void onComplete(){delegate.onComplete();}
    }
}

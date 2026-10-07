package io.edgeai.app.service;

import com.sun.net.httpserver.HttpServer;
import io.edgeai.adapters.metrics.EdgeXSensorAccess;
import io.edgeai.domain.node.SensorAccessSource.Failure;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class SensorAccessTest {
    private HttpServer server;
    private EdgeXSensorAccess source;
    private final List<String> calls=new ArrayList<>();
    private String state="UNLOCKED", payload="", readings="", commandBody="";
    private int executeStatus=200;
    private final String reading="{\"deviceName\":\"sensor-1\",\"resourceName\":\"temperature\",\"valueType\":\"Float64\",\"value\":\"0\",\"units\":\"C\",\"origin\":1791279804267386000}";
    @BeforeEach void start() throws Exception {
        readings="["+reading+"]";
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            String path=exchange.getRequestURI().getPath(),query=exchange.getRequestURI().getQuery();
            synchronized(calls) {calls.add(exchange.getRequestMethod()+" "+exchange.getRequestURI());}
            String result;int status=200;
            if(path.equals("/api/v3/device/name/sensor-1") && query==null) {
                // The fixture serves both envelopes; production uses distinct configured service origins.
                result="{\"statusCode\":200,\"device\":{\"name\":\"sensor-1\",\"adminState\":\""+state+"\",\"operatingState\":\"UP\"},\"deviceCoreCommand\":{\"deviceName\":\"sensor-1\",\"coreCommands\":[{\"name\":\"temperature\",\"get\":true,\"set\":false,\"url\":\"http://attacker.invalid\",\"parameters\":[{\"resourceName\":\"temperature\",\"valueType\":\"Float64\"}]},{\"name\":\"SetValue\",\"set\":true,\"parameters\":[{\"resourceName\":\"temperature\",\"valueType\":\"Float64\"}]}]}}";
            } else if(path.startsWith("/api/v3/reading/device/name/sensor-1")) result="{\"statusCode\":200,\"readings\":"+readings+"}";
            else if(path.equals("/api/v3/device/name/sensor-1/temperature")) result="{\"statusCode\":200,\"event\":{\"readings\":["+reading+"]}}";
            else if(path.equals("/api/v3/device/name/sensor-1/SetValue")) {commandBody=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);status=executeStatus;result=payload.isEmpty()?"{\"statusCode\":200}":payload;}
            else {status=404;result="{\"statusCode\":404}";}
            byte[] body=result.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(status,body.length);exchange.getResponseBody().write(body);exchange.close();
        });
        server.start();String url="http://127.0.0.1:"+server.getAddress().getPort();
        source=new EdgeXSensorAccess(url,url,url,Clock.fixed(Instant.parse("2026-10-06T10:00:00Z"),ZoneOffset.UTC));
    }
    @AfterEach void stop(){source.close();server.stop(0);}
    @Test void preservesZeroUnitsAndNanosecondsInHistory() {
        var result=source.readings("sensor-1","temperature",50);
        assertEquals("0",result.readings().getFirst().value());assertEquals("C",result.readings().getFirst().units());
        assertEquals(Instant.ofEpochSecond(1791279804,267386000),result.readings().getFirst().observedAt());
        assertTrue(calls.getLast().endsWith("/resourceName/temperature?limit=50"));
    }
    @Test void missingSamplesStayEmptyAndForeignSamplesFail() {
        readings="[]";assertTrue(source.readings("sensor-1","",100).readings().isEmpty());
        readings="["+reading.replace("sensor-1","other")+"]";
        assertEquals(Failure.Kind.UNAVAILABLE,assertThrows(Failure.class,()->source.readings("sensor-1","",100)).kind());
    }
    @Test void validatesPathsAndLimitsBeforeNetwork() {
        for(String value:List.of("../private","sensor/other","https://host","sensor%2fother")) assertThrows(Failure.class,()->source.readings(value,"",100));
        assertThrows(Failure.class,()->source.readings("sensor-1","",501));assertTrue(calls.isEmpty());
    }
    @Test void returnsCapabilitiesWithoutUpstreamUrlsAndExecutesReadWithoutPublishing() {
        var commands=source.commands("sensor-1");assertFalse(commands.commands().getFirst().writable());
        assertFalse(commands.toString().contains("attacker"));
        assertEquals("0",source.execute("sensor-1","temperature","GET",Map.of()).readings().getFirst().value());
        assertTrue(calls.getLast().contains("ds-pushevent=false&ds-returnevent=true"));
    }
    @Test void unsupportedAndLockedCommandsNeverExecute() {
        assertEquals(Failure.Kind.INVALID,assertThrows(Failure.class,()->source.execute("sensor-1","temperature","PUT",Map.of("temperature","1"))).kind());
        assertThrows(Failure.class,()->source.execute("sensor-1","unknown","GET",Map.of()));
        state="LOCKED";assertEquals(Failure.Kind.LOCKED,assertThrows(Failure.class,()->source.execute("sensor-1","SetValue","PUT",Map.of("temperature","1"))).kind());
        assertTrue(calls.stream().noneMatch(c->c.startsWith("PUT")));
    }
    @Test void writeRequiresExactParametersAndForwardsOnlyValues() {
        assertThrows(Failure.class,()->source.execute("sensor-1","SetValue","PUT",Map.of("unknown","1")));
        source.execute("sensor-1","SetValue","PUT",Map.of("temperature","21"));
        assertEquals("{\"temperature\":\"21\"}",commandBody);
        assertEquals(1,calls.stream().filter(c->c.startsWith("PUT")).count());
    }
    @Test void upstreamFailureIsSanitizedAndNeverRetried() {
        executeStatus=500;payload="private password response";
        var failure=assertThrows(Failure.class,()->source.execute("sensor-1","SetValue","PUT",Map.of("temperature","21")));
        assertEquals(Failure.Kind.UNAVAILABLE,failure.kind());assertFalse(failure.getMessage().contains("password"));
        assertEquals(1,calls.stream().filter(c->c.startsWith("PUT")).count());
    }
}

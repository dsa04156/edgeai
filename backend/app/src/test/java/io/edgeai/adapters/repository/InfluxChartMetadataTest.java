package io.edgeai.adapters.repository;

import com.sun.net.httpserver.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** The chart reads twice (selection/counts, then originals when they fit); originals stored or hidden in between must not leave stale metadata. */
class InfluxChartMetadataTest {
    @TempDir Path temporary;
    HttpServer server;
    InfluxTelemetryRepository repository;
    String selection,originals;
    final List<String> writes=Collections.synchronizedList(new ArrayList<>());
    // One whole epoch-aligned five-second window inside the retained period.
    final Instant from=Instant.ofEpochSecond((Instant.now().getEpochSecond()-60)/5*5),to=from.plusSeconds(5);
    @BeforeEach void start() throws Exception {
        Path token=temporary.resolve("token");Files.writeString(token,"owned-test-credential-never-printed");
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            String body=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
            if(!exchange.getRequestURI().getPath().equals("/api/v2/query")){writes.add(body);exchange.sendResponseHeaders(503,-1);return;}
            byte[] data=(body.contains("yield(name:")?selection:originals).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,data.length);exchange.getResponseBody().write(data);exchange.close();
        });
        server.start();
        repository=new InfluxTelemetryRepository("http://127.0.0.1:"+server.getAddress().getPort(),"edgeai-test",token.toString(),"sensor-originals","vd-results-",30,true);
    }
    @AfterEach void stop(){repository.close();server.stop(0);}
    String at(int second){return from.plusSeconds(second).toString();}
    /** Selection read: one candidate per original and the window's count, as the per-yield CSV blocks arrive. */
    String selectionRead(int count,int... seconds){
        var csv=new StringBuilder(",result,table,_time,_value,kind,series,configuration\n");
        for(int second:seconds)csv.append(",y0,0,").append(at(second)).append(",").append(second).append(",N,gen:s:0,c\n");
        return csv.append("\n,result,table,_time,_value,kind,series,configuration\n,y12,0,").append(from).append(",").append(count).append(",C,gen:s:0,c\n").toString();
    }
    String originalsRead(int... seconds){
        var csv=new StringBuilder(",result,table,_time,_value,kind,series,configuration\n");
        for(int second:seconds)csv.append(",_result,0,").append(at(second)).append(",").append(second).append(",N,gen:s:0,c\n");
        return csv.toString();
    }
    @SuppressWarnings("unchecked") List<Map<String,Object>> items(Map<String,Object> chart){return (List<Map<String,Object>>)chart.get("items");}
    Map<String,Object> chart(int budget){return repository.readSensorChart(UUID.randomUUID(),"temperature",from,to,5,budget,true);}

    @Test void anOriginalStoredBetweenTheReadsIsCountedWithTheOriginalsReturned() {
        selection=selectionRead(1,2);originals=originalsRead(2,3);
        var chart=chart(200);
        assertThat(chart.get("displayMode")).isEqualTo("ORIGINALS");
        assertThat(items(chart)).hasSize(2);assertThat(chart.get("totalObservations")).isEqualTo(2L);
    }
    @Test void anOriginalHiddenBetweenTheReadsIsNotCounted() {
        selection=selectionRead(2,1,2);originals=originalsRead(2);
        var chart=chart(200);
        assertThat(chart.get("displayMode")).isEqualTo("ORIGINALS");
        assertThat(items(chart)).hasSize(1);assertThat(chart.get("totalObservations")).isEqualTo(1L);
    }
    @Test void originalsThatNoLongerFitFallBackToTheSelectionAndItsOwnCounts() {
        selection=selectionRead(200,0,4);
        originals=originalsRead(java.util.stream.IntStream.range(0,201).map(i->i%5).toArray());
        var chart=chart(200);
        assertThat(chart.get("displayMode")).isEqualTo("SELECTED_ORIGINALS");
        assertThat(items(chart)).hasSize(2).hasSizeLessThanOrEqualTo(200);
        assertThat(chart.get("totalObservations")).isEqualTo(200L);
        @SuppressWarnings("unchecked") var counts=(List<Long>)((Map<String,Object>)chart.get("selection")).get("counts");
        assertThat(counts).containsExactly(200L);
        assertThat(writes).isEmpty();
    }
}

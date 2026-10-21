package io.edgeai.adapters.repository;

import io.edgeai.app.service.DataStoreService;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

/**
 * Chart point budget against a private, ephemeral InfluxDB 2.9.1 loaded by
 * output/verification/sensor-chart-performance/scripts/fixture-influx.py (never a shared store). The repository runs in
 * read-only inspection mode, so the chart path cannot write. Skipped unless EDGEAI_CHART_FIXTURE names the fixture manifest.
 */
@EnabledIfEnvironmentVariable(named="EDGEAI_CHART_FIXTURE",matches=".+")
class SensorChartFixtureTest {
    static final JsonMapper json=new JsonMapper();
    static Map<String,Object> manifest;static String origin,token;static InfluxTelemetryRepository repository;static DataStoreService service;
    static long end;static UUID dense,flag,sparse,empty;
    static final long H=3600_000,D=24*H;
    @BeforeAll @SuppressWarnings("unchecked") static void open() throws Exception {
        manifest=json.readValue(Files.readString(Path.of(System.getenv("EDGEAI_CHART_FIXTURE"))),Map.class);
        origin=System.getenv().getOrDefault("EDGEAI_CHART_FIXTURE_INFLUX","http://127.0.0.1:19287");String tokenFile=System.getenv("EDGEAI_CHART_FIXTURE_TOKEN");token=Files.readString(Path.of(tokenFile)).trim();
        repository=new InfluxTelemetryRepository(origin,"edgeai-test",tokenFile,"sensor-originals","vd-results-",30,true);
        service=new DataStoreService(repository,Clock.systemUTC(),null);
        end=((Number)manifest.get("end")).longValue();dense=UUID.fromString((String)manifest.get("dense"));flag=UUID.fromString((String)manifest.get("bool"));sparse=UUID.fromString((String)manifest.get("sparse"));empty=UUID.fromString((String)manifest.get("empty"));
    }
    @AfterAll static void close(){repository.close();}
    static String at(long ms){return Instant.ofEpochMilli(ms).toString();}
    @SuppressWarnings("unchecked") static List<Map<String,Object>> items(Map<String,Object> chart){return (List<Map<String,Object>>)chart.get("items");}
    static long time(Map<String,Object> item){return Instant.parse((String)item.get("observedAt")).toEpochMilli();}
    static double number(Map<String,Object> item){return item.get("value") instanceof Boolean b?(b?1:0):((Number)item.get("value")).doubleValue();}
    static Map<String,Object> chart(UUID uid,String property,long from,long to,Integer points,Integer window){
        long started=System.nanoTime();var chart=service.chart(uid,property,at(from),at(to),points,window);
        System.out.printf("chart %s %s..%s points=%s window=%s -> %s items=%d total=%s in %d ms%n",property,at(from),at(to),points,window,chart.get("displayMode"),items(chart).size(),chart.get("totalObservations"),(System.nanoTime()-started)/1000000);
        return chart;
    }
    static void assertBoundedAndOrdered(Map<String,Object> chart,long from,long to,int budget){
        var items=items(chart);assertThat(items.size()).isLessThanOrEqualTo(budget);
        for(int i=0;i<items.size();i++){assertThat(time(items.get(i))).isBetween(from,to-1);if(i>0)assertThat(time(items.get(i))).isGreaterThanOrEqualTo(time(items.get(i-1)));}
        assertThat(items).noneMatch(item->number(item)==99999.0);
    }
    /** Brute force over raw visible originals: the pivot with archive_visible, no window selectors, no pushdown. */
    static List<long[]> raw(UUID uid,String property,long from,long to) throws Exception {
        String flux="from(bucket:\"sensor-originals\") |> range(start:"+at(from)+",stop:"+at(to)+") |> filter(fn:(r)=> r._measurement == \"sensor_original\" and r.device_uid == \""+uid+"\" and r.property == \""+property+"\" and (r._field == \"archive_visible\" or r._field == \"value_numeric\" or r._field == \"value_boolean\"))"
            +" |> pivot(rowKey:[\"_time\"],columnKey:[\"_field\"],valueColumn:\"_value\") |> filter(fn:(r)=> (not exists r.archive_visible or r.archive_visible) and exists r.value_numeric) |> group() |> sort(columns:[\"_time\"]) |> keep(columns:[\"_time\",\"value_numeric\"])";
        var response=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(origin+"/api/v2/query?org=edgeai-test")).header("Authorization","Token "+token).header("Content-Type","application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("query",flux,"type","flux","dialect",Map.of("header",true,"annotations",List.of()))))).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        var rows=new ArrayList<long[]>();var lines=response.body().split("\r?\n");var header=Arrays.asList(lines[0].split(","));
        for(int i=1;i<lines.length;i++){if(lines[i].isBlank())continue;var cells=lines[i].split(",");var t=Instant.parse(cells[header.indexOf("_time")]);rows.add(new long[]{t.toEpochMilli(),Double.doubleToLongBits(Double.parseDouble(cells[header.indexOf("value_numeric")]))});}
        return rows;
    }

    @Test void denseThreeDaysStayWithinBudgetAndKeepEverySpikeEndpointAndExactCount() {
        long from=end-3*D,to=end;var chart=chart(dense,"temperature",from,to,2000,null);
        assertThat(chart.get("displayMode")).isEqualTo("SELECTED_ORIGINALS");assertBoundedAndOrdered(chart,from,to,2000);
        // 3 days at 1 Hz minus the 2 h hole, two same-millisecond originals and one old-format legacy point; the hidden legacy point is excluded.
        assertThat(((Number)chart.get("totalObservations")).longValue()).isEqualTo(3*86400-7200+2+1);
        @SuppressWarnings("unchecked") var selection=(Map<String,Object>)chart.get("selection");
        assertThat(((List<Number>)selection.get("counts")).stream().mapToLong(Number::longValue).sum()).isEqualTo(3*86400-7200+2+1);
        var items=items(chart);
        assertThat(time(items.getFirst())).isEqualTo(end-3*D+311);assertThat(time(items.getLast())).isEqualTo(end-1000+311);
        @SuppressWarnings("unchecked") var spikes=(Map<String,Number>)manifest.get("spikes");
        for(var spike:spikes.entrySet())assertThat(items).as("spike %s",spike).anyMatch(item->time(item)==Long.parseLong(spike.getKey())&&number(item)==spike.getValue().doubleValue());
        assertThat(items).anyMatch(item->time(item)==((Number)manifest.get("legacyVisibleAt")).longValue()&&number(item)==-77.25);
        assertThat(items.stream().mapToDouble(SensorChartFixtureTest::number).max().orElseThrow()).isEqualTo(1000.0);
        assertThat(items.stream().mapToDouble(SensorChartFixtureTest::number).min().orElseThrow()).isEqualTo(-500.0);
    }
    @Test void selectionEqualsBruteForceFirstMinMaxLastPerAlignedWindowIncludingPartialEdgesAndLegacy() throws Exception {
        long from=end-6*H-12_345,to=end-4*H-7_777;int window=60;
        var chart=chart(dense,"temperature",from,to,1000,window);assertBoundedAndOrdered(chart,from,to,1000);
        var raw=raw(dense,"temperature",from,to);var byWindow=new TreeMap<Long,List<long[]>>();
        for(var row:raw)byWindow.computeIfAbsent(Math.floorDiv(row[0],window*1000L),key->new ArrayList<>()).add(row);
        var expected=new ArrayList<String>();
        for(var rows:byWindow.values()){
            var chosen=new LinkedHashSet<long[]>();chosen.add(rows.getFirst());chosen.add(rows.getLast());
            chosen.add(rows.stream().min(Comparator.comparingDouble(r->Double.longBitsToDouble(r[1]))).orElseThrow());chosen.add(rows.stream().max(Comparator.comparingDouble(r->Double.longBitsToDouble(r[1]))).orElseThrow());
            chosen.stream().sorted(Comparator.comparingLong(r->r[0])).forEach(r->expected.add(r[0]+"="+Double.longBitsToDouble(r[1])));
        }
        assertThat(items(chart).stream().map(item->time(item)+"="+number(item)).toList()).containsExactlyElementsOf(expected);
        @SuppressWarnings("unchecked") var selection=(Map<String,Object>)chart.get("selection");
        assertThat(Instant.parse((String)selection.get("bucketStart")).toEpochMilli()).isEqualTo(Math.floorDiv(from,60_000L)*60_000L);
        @SuppressWarnings("unchecked") var counts=(List<Number>)selection.get("counts");
        assertThat(counts).hasSize((int)(Math.floorDiv(to-1,60_000L)-Math.floorDiv(from,60_000L)+1));
        assertThat(counts.stream().mapToLong(Number::longValue).sum()).isEqualTo(raw.size()).isEqualTo(((Number)chart.get("totalObservations")).longValue());
    }
    @Test void aTailWithTheSameWindowReproducesTheWholeWindowsOfTheFullResponse() {
        long from=end-3*H,to=end-H+30_000;var full=chart(dense,"temperature",from,to,2000,null);
        @SuppressWarnings("unchecked") int window=((Number)((Map<String,Object>)full.get("selection")).get("windowSeconds")).intValue();
        long tail=Math.floorDiv(to-20*60_000,window*1000L)*window*1000L;var part=chart(dense,"temperature",tail,to,2000,window);
        assertThat(items(part)).isEqualTo(items(full).stream().filter(item->time(item)>=tail).toList());
    }
    @Test void longRangesBooleansSparseEmptyAndSameMillisecondOriginals() {
        var month=chart(dense,"temperature",end-30*D,end,1000,null);assertBoundedAndOrdered(month,end-30*D,end,1000);
        assertThat(items(month)).anyMatch(item->number(item)==1000.0).anyMatch(item->number(item)==-500.0);
        long pulse=((Number)manifest.get("pulse")).longValue();
        var flags=chart(flag,"connected",end-34*H,end,500,null);assertBoundedAndOrdered(flags,end-34*H,end,500);
        assertThat(flags.get("displayMode")).isEqualTo("SELECTED_ORIGINALS");assertThat(items(flags)).allMatch(item->item.get("value") instanceof Boolean);
        assertThat(items(flags)).anyMatch(item->time(item)==pulse&&item.get("value").equals(true)).anyMatch(item->time(item)==end-2*H&&item.get("value").equals(true)).anyMatch(item->item.get("value").equals(false));
        assertThat(((Number)flags.get("totalObservations")).longValue()).isEqualTo(120_000);
        var few=chart(sparse,"pressure",end-30*D,end,200,null);
        assertThat(few.get("displayMode")).isEqualTo("ORIGINALS");assertThat(items(few)).hasSize(50);assertThat(few.get("totalObservations")).isEqualTo(50L);
        var none=chart(empty,"temperature",end-30*D,end,200,null);
        assertThat(none.get("displayMode")).isEqualTo("ORIGINALS");assertThat(items(none)).isEmpty();assertThat(none.get("totalObservations")).isEqualTo(0L);
        long same=((Number)manifest.get("sameMs")).longValue();var pair=chart(dense,"temperature",same-5000,same+5000,200,null);
        assertThat(pair.get("displayMode")).isEqualTo("ORIGINALS");assertThat(items(pair).stream().filter(item->time(item)==same).map(SensorChartFixtureTest::number).toList()).contains(5.5,-5.5).hasSize(3); // the pair shares its millisecond with the regular 1 Hz original
    }
    /** Same ranges as scripts/bench-old-flux.py (the previous Flux on this fixture), three runs each; evidence output only. */
    @Test void timingsForTheRangesOfTheOldFluxBaseline() {
        record Range(String label,UUID uid,String property,long span){}
        for(var range:List.of(new Range("1h",dense,"temperature",H),new Range("24h",dense,"temperature",D),new Range("36h",dense,"temperature",36*H),new Range("3d",dense,"temperature",3*D),new Range("30d",dense,"temperature",30*D-60_000),new Range("bool-34h",flag,"connected",34*H)))
            for(int run=0;run<3;run++){var chart=chart(range.uid(),range.property(),end-range.span(),end,2000,null);assertBoundedAndOrdered(chart,end-range.span(),end,2000);System.out.println("timing "+range.label());}
    }
    @Test void defaultsAndInvalidBudgetsOrWindowsAreDocumentedNotSilentlyClamped() {
        var older=service.chart(dense,"temperature",at(end-D),at(end));assertThat(older.get("pointBudget")).isEqualTo(1000);assertThat(items(older).size()).isLessThanOrEqualTo(1000);
        for(int points:new int[]{0,199,2001})assertThatThrownBy(()->service.chart(dense,"temperature",at(end-H),at(end),points,null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->service.chart(dense,"temperature",at(end-H),at(end),2000,7)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->service.chart(dense,"temperature",at(end-D),at(end),2000,1)).as("a window that cannot fit the budget").isInstanceOf(IllegalArgumentException.class);
    }
}

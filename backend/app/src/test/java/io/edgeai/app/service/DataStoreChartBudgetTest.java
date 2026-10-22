package io.edgeai.app.service;

import io.edgeai.domain.repository.SensorTelemetryRepository;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class DataStoreChartBudgetTest {
    record Call(Instant from,Instant to,int window,int budget,boolean originals) {}
    final List<Call> calls=new ArrayList<>();
    final SensorTelemetryRepository archive=new SensorTelemetryRepository() {
        public boolean enabled(){return true;}
        public void append(Observation observation){throw new AssertionError("charts never write");}
        public Map<String,Object> readSensor(UUID uid,String property,Instant from,Instant to,int limit,String cursor){throw new AssertionError();}
        public List<Map<String,Object>> sensorSeries(){return List.of();}
        public int rawRetentionDays(){return 30;}
        @Override public Map<String,Object> readSensorChart(UUID uid,String property,Instant from,Instant to,int window,int budget,boolean originals){
            calls.add(new Call(from,to,window,budget,originals));return Map.of("items",List.of(),"truncated",false,"displayMode","ORIGINALS","totalObservations",0);
        }
    };
    final Instant now=Instant.parse("2026-10-21T09:00:00.123Z");
    final DataStoreService service=new DataStoreService(archive,Clock.fixed(now,ZoneOffset.UTC),null);
    final UUID uid=UUID.randomUUID();
    Call plan(Duration span,Integer points){service.chart(uid,"temperature",now.minus(span).toString(),now.toString(),points,null);return calls.getLast();}

    @Test void theNarrowestAlignedWindowWhoseFourPointsFitTheBudgetIsChosenCountingPartialEdgeWindows() {
        // 1 h ending .123 s past the hour touches 3601 one-second windows (14,404 points) and 361 ten-second windows (1,444).
        assertThat(plan(Duration.ofHours(1),2000).window()).isEqualTo(10);
        assertThat(plan(Duration.ofHours(1),500).window()).isEqualTo(30);
        assertThat(plan(Duration.ofDays(1),2000).window()).isEqualTo(300); // 60 s: 1,441 windows → 5,764 points
        assertThat(plan(Duration.ofDays(31),2000).window()).isEqualTo(7200); // 3,600 s: 745 windows → 2,980 points
        assertThat(plan(Duration.ofDays(31),200).window()).isEqualTo(86400); // 32 partial-edged days → 128 points
        for(var call:calls)assertThat(DataStoreService.windows(call.from(),call.to(),call.window())*4).isLessThanOrEqualTo(call.budget());
        assertThat(calls).allMatch(Call::originals);
    }
    @Test void olderClientsGetTheDefaultBudgetAndTheResponseNamesIt() {
        var chart=service.chart(uid,"temperature",null,null);
        assertThat(calls.getLast().budget()).isEqualTo(DataStoreService.DEFAULT_POINTS);assertThat(chart.get("pointBudget")).isEqualTo(1000);
    }
    @Test void aLiveTailKeepsTheRequestedWindowAndNeverSwitchesToOriginals() {
        service.chart(uid,"temperature",now.minusSeconds(70).toString(),now.toString(),2000,10);
        assertThat(calls.getLast()).extracting(Call::window,Call::originals).containsExactly(10,false);
    }
    @Test void outOfRangeBudgetsAndUnalignedOrOversizedWindowsAreRejectedBeforeAnyQuery() {
        for(Integer points:new Integer[]{0,199,2001,-5})assertThatThrownBy(()->plan(Duration.ofHours(1),points)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("budget");
        assertThatThrownBy(()->service.chart(uid,"temperature",now.minusSeconds(70).toString(),now.toString(),2000,7)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->service.chart(uid,"temperature",now.minus(Duration.ofDays(1)).toString(),now.toString(),2000,1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->service.chart(uid,"temperature",now.minus(Duration.ofDays(32)).toString(),now.toString(),2000,null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(calls).isEmpty();
    }
}

package io.edgeai.app.support;

import io.edgeai.domain.execution.Task;
import io.edgeai.domain.runtime.ServiceExecutionSpec;
import io.edgeai.domain.stream.DataRoute;
import io.edgeai.domain.workflow.Dag;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class StreamRunPlanTest {
    private final JsonDocuments json=new JsonDocuments();
    private record Fixture(List<Task> tasks,Map<UUID,ServiceExecutionSpec> specs,Dag dag,List<DataRoute> routes) {}
    @SuppressWarnings("unchecked") private ServiceExecutionSpec spec(boolean output,int frames)throws Exception {
        var value=(Map<String,Object>)json.decode(Files.readString(Path.of("../../contracts/profiles/service-stream.example.json")));
        var stream=(Map<String,Object>)value.get("stream");var port=Map.of("mediaType","application/json","maxPayloadBytes",4096);
        stream.put("inputs",Map.of("input",port));stream.put("outputs",output?Map.of("output",port):Map.of());
        ((Map<String,Object>)stream.get("limits")).put("maxFrames",frames);
        return ServiceExecutionInput.parseSpec(json.canonical(value));
    }
    private Fixture fixture(boolean forwardBatch,boolean reverseBatch,int frames)throws Exception {
        var run=UUID.randomUUID();var now=Instant.now();var tasks=new ArrayList<Task>();var specs=new HashMap<UUID,ServiceExecutionSpec>();
        for(String key:List.of("a","b","c","d")){
            var t=new Task(UUID.randomUUID(),run,UUID.randomUUID(),key,"READY",null,now,now);tasks.add(t);specs.put(t.id(),spec(key.equals("a")||key.equals("b"),frames));
        }
        var edges=new ArrayList<Dag.Edge>();edges.add(new Dag.Edge("a","c","output","input",Dag.Mode.STREAM));edges.add(new Dag.Edge("b","d","output","input",Dag.Mode.STREAM));
        if(forwardBatch)edges.add(new Dag.Edge("a","d","result","file-input",Dag.Mode.BATCH));
        if(reverseBatch)edges.add(new Dag.Edge("b","c","result","file-input",Dag.Mode.BATCH));
        var dag=new Dag(tasks.stream().map(t->new Dag.Node(t.key(),UUID.randomUUID(),"{}")).toList(),edges);
        var routes=new ArrayList<DataRoute>();
        for(int i=0;i<2;i++){
            var from=tasks.get(i);var to=tasks.get(i+2);
            routes.add(new DataRoute(UUID.randomUUID(),run,null,UUID.randomUUID(),UUID.randomUUID(),"SYNTHETIC","samples",from.id(),"input","application/json",4096,now));
            routes.add(new DataRoute(UUID.randomUUID(),run,from.id(),null,UUID.randomUUID(),null,"output",to.id(),"input","application/json",4096,now));
        }
        return new Fixture(tasks,specs,dag,routes);
    }
    @Test void waitsForAllPublishedEdgesAndAllDeviceInputs()throws Exception {
        var f=fixture(false,false,128);
        for(int missing=0;missing<f.routes().size();missing++){
            var partial=new ArrayList<>(f.routes());partial.remove(missing);
            assertThat(StreamRunPlan.compile(f.tasks(),f.specs(),f.dag(),partial)).isEmpty();
        }
        assertThat(StreamRunPlan.compile(f.tasks(),f.specs(),f.dag(),f.routes())).isPresent();
    }
    @Test void batchBetweenComponentsDoesNotMergeCompletionBarriers()throws Exception {
        var f=fixture(true,false,128);var plan=StreamRunPlan.compile(f.tasks(),f.specs(),f.dag(),f.routes()).orElseThrow();
        assertThat(plan.componentTasks(f.tasks().getFirst().id())).containsExactlyInAnyOrder(f.tasks().get(0).id(),f.tasks().get(2).id());
        assertThat(plan.componentRoutes(f.tasks().getFirst().id())).hasSize(2);
    }
    @Test void structurallyAcyclicDagCanDeadlockAcrossStreamComponents()throws Exception {
        var f=fixture(true,true,128); // Both edges go from original roots to leaves, yet the collapsed components cycle.
        assertThatThrownBy(()->StreamRunPlan.compile(f.tasks(),f.specs(),f.dag(),f.routes())).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Cyclic BATCH");
    }
    @Test void rejectsBudgetsThatCannotHoldInputAndOutputProgress()throws Exception {
        var f=fixture(false,false,2);
        assertThatThrownBy(()->StreamRunPlan.compile(f.tasks(),f.specs(),f.dag(),f.routes())).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("hold");
    }
}

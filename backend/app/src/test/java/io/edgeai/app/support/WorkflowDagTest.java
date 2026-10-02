package io.edgeai.app.support;
import io.edgeai.domain.workflow.Dag;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class WorkflowDagTest {
    private Dag.Node node(String key) { return new Dag.Node(key,UUID.randomUUID(),"{}"); }
    private Dag.Edge edge(String from,String to,String port) { return new Dag.Edge(from,to,"output",port,Dag.Mode.BATCH); }
    @Test void fanInFanOutAndDisconnectedBranchesHaveCorrectRootsAndDescendants() {
        var dag=new Dag(List.of(node("a"),node("b"),node("c"),node("d"),node("independent")),
            List.of(edge("a","b","input"),edge("a","c","input"),edge("b","d","left"),edge("c","d","right")));
        assertThat(dag.roots()).containsExactlyInAnyOrder("a","independent");
        assertThat(dag.descendants("a")).containsExactlyInAnyOrder("b","c","d");
        assertThat(dag.descendants("d")).isEmpty();
    }
    @Test void rejectsCyclesDuplicateKeysDanglingEdgesAndCompetingInputProducers() {
        var nodes=List.of(node("a"),node("b"),node("c"));
        for(var edges:List.of(List.of(edge("a","a","in")),List.of(edge("a","b","in"),edge("b","c","in"),edge("c","a","in")),
            List.of(edge("missing","a","in")),List.of(edge("a","c","in"),edge("b","c","in")),List.of(edge("a","b","in"),edge("a","b","in"))))
            assertThatThrownBy(()->new Dag(nodes,edges)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new Dag(List.of(node("a"),node("a")),List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new Dag(List.of(),List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new Dag(java.util.stream.IntStream.range(0,129).mapToObj(i->node("t"+i)).toList(),List.of())).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void strictInputNormalizesDagOrderAndPreservesLargeNumbers() {
        String profile=UUID.randomUUID().toString();
        String json="{\"tasks\":[{\"key\":\"b\",\"serviceProfileVersionId\":\""+profile+"\",\"parameters\":{\"serial\":9007199254740993}},{\"key\":\"a\",\"serviceProfileVersionId\":\""+profile+"\",\"parameters\":{}}],\"dependencies\":[]}";
        var dag=WorkflowInput.storedDag(json);
        assertThat(dag.tasks().getFirst().key()).isEqualTo("a");
        assertThat(WorkflowInput.JSON.canonical(WorkflowInput.document(dag))).contains("9007199254740993");
        assertThatThrownBy(()->WorkflowInput.storedDag(json.replace("\"dependencies\":[]","\"dependencies\":[],\"unknown\":true"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->WorkflowInput.uuid("0-0-0-0-1")).isInstanceOf(IllegalArgumentException.class);
    }
}

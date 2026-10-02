package io.edgeai.app.service;

import io.edgeai.app.exception.ControlPlaneException;
import io.edgeai.app.support.WorkflowInput;
import io.edgeai.domain.profile.ProfileIdentity;
import io.edgeai.domain.repository.*;
import io.edgeai.domain.workflow.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static io.edgeai.app.support.WorkflowInput.*;

@Service
public class WorkflowService {
    private final WorkflowRepository repository;
    private final ProfileRepository profiles;
    public WorkflowService(WorkflowRepository repository,ProfileRepository profiles) { this.repository=repository;this.profiles=profiles; }
    @Transactional
    public Creation<Workflow> create(String body) {
        var input=parse(body,"key","displayName");String key=text(input.get("key"),100),name=text(input.get("displayName"),128);
        ProfileIdentity.validateKey(key);String digest=JSON.digest("edgeai-workflow-v1",input);
        boolean created=repository.create(key,name,digest);var workflow=repository.findByKey(key).orElseThrow();
        if(!workflow.creationDigest().equals(digest)) throw error(409,"WORKFLOW_CONFLICT","같은 Workflow 키에 다른 생성 입력이 있습니다.");
        return new Creation<>(workflow,created);
    }
    public List<Workflow> list(int limit,int offset) { page(limit,offset);return repository.list(limit+1,offset); }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public WorkflowSnapshot detail(UUID id,String version,int limit,int offset) {
        page(limit,offset);var workflow=find(id,false);
        var versions=version==null?repository.versions(id,limit+1,offset):List.of(repository.version(id,WorkflowInput.version(version)).orElseThrow(()->error(404,"WORKFLOW_NOT_FOUND","발행된 DAG 버전을 찾을 수 없습니다.")));
        return new WorkflowSnapshot(workflow,versions);
    }
    @Transactional
    public Creation<WorkflowVersion> publish(UUID id,String body) {
        var input=parse(body,"version","tasks","dependencies");String version=WorkflowInput.version(input.get("version"));
        var dag=dag(input);var document=document(dag);String canonical=JSON.boundedCanonical(document,65536),digest=JSON.digest("edgeai-workflow-dag-v1",document);
        find(id,true);
        var existing=repository.version(id,version);
        if(existing.isPresent()) {
            if(!existing.get().digest().equals(digest)) throw error(409,"WORKFLOW_CONFLICT","발행된 버전의 내용이 다릅니다. 새 버전으로 발행하세요.");
            return new Creation<>(existing.get(),false);
        }
        for(var task:dag.tasks()) {
            var profile=profiles.find(task.serviceProfileVersionId()).orElseThrow(()->error(404,"WORKFLOW_NOT_FOUND","참조할 SERVICE Profile 버전이 없습니다."));
            if(profile.identity().kind()!=ProfileIdentity.Kind.SERVICE) throw new IllegalArgumentException("SERVICE Profile required");
        }
        return new Creation<>(repository.publish(id,version,dag,canonical,digest),true);
    }
    private Workflow find(UUID id,boolean lock) { return repository.find(id,lock).orElseThrow(()->error(404,"WORKFLOW_NOT_FOUND","Workflow를 찾을 수 없습니다.")); }
    public static void page(int limit,int offset) { if(limit<1 || limit>100 || offset<0 || offset>1000000) throw new IllegalArgumentException("Invalid page"); }
    static ControlPlaneException error(int status,String code,String message) { return new ControlPlaneException(status,code,message); }
}

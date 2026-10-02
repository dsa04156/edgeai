package io.edgeai.app.service;
import io.edgeai.app.config.RunnerPrincipal;
import io.edgeai.app.support.RunnerInput;
import io.edgeai.domain.repository.*;
import io.edgeai.domain.runtime.RuntimeTelemetry;
import java.time.*;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static io.edgeai.app.support.WorkflowInput.*;
import static io.edgeai.app.service.WorkflowService.error;

@Service
public class RuntimeTelemetryService {
    private final TelemetryRepository repository;
    private final RuntimeLifecycleService lifecycle;
    private final Clock clock;
    public RuntimeTelemetryService(TelemetryRepository repository,RuntimeLifecycleService lifecycle,Clock clock){this.repository=repository;this.lifecycle=lifecycle;this.clock=clock;}
    @Transactional
    public RuntimeTelemetry record(RunnerPrincipal principal,String body) {
        var input=RunnerInput.parse(body,principal,"sequence","observedAt","intervalMillis","cpuUsageMicros","cpuLimitMillicores","memoryBytes","memoryLimitBytes","latencyMicros","latencyObservedAt");
        // The Run lock is retained until insert/prune; read server time after acquiring it.
        lifecycle.authorize(principal.attemptId(),principal.epoch(),principal.pod().podUid());
        var now=clock.instant().truncatedTo(ChronoUnit.MICROS);
        long interval=RunnerInput.integer(input.get("intervalMillis"));if(interval<200 || interval>60000)throw new IllegalArgumentException("Invalid sample interval");
        var value=new RuntimeTelemetry(principal.attemptId(),RunnerInput.integer(input.get("sequence")),instant(input.get("observedAt")),now,(int)interval,
            number(input.get("cpuUsageMicros")),number(input.get("cpuLimitMillicores")),number(input.get("memoryBytes")),number(input.get("memoryLimitBytes")),number(input.get("latencyMicros")),input.get("latencyObservedAt")==null?null:instant(input.get("latencyObservedAt")));
        var replay=repository.find(value.attemptId(),value.sequence());
        if(replay.isPresent()) {
            if(!replay.get().sameMeasurement(value))throw error(409,"TELEMETRY_CONFLICT","동일 측정 순번의 내용이 다릅니다.");
            return replay.get();
        }
        var latest=repository.recent(value.attemptId(),1);
        if(!latest.isEmpty() && (value.sequence()<=latest.getFirst().sequence() || !value.observedAt().isAfter(latest.getFirst().observedAt())))
            throw error(409,"TELEMETRY_OUT_OF_ORDER","이전 측정 순번이나 시각을 다시 사용할 수 없습니다.");
        if(value.observedAt().isBefore(now.minusSeconds(60)) || value.observedAt().isAfter(now.plusSeconds(5)) ||
            value.latencyObservedAt()!=null && (value.latencyObservedAt().isBefore(value.observedAt().minusSeconds(30)) || value.latencyObservedAt().isAfter(value.observedAt().plusSeconds(5))))
            throw error(409,"TELEMETRY_EXPIRED","측정 시각이 허용 범위를 벗어났습니다.");
        repository.append(value);return value;
    }
    private static Long number(Object value){return value==null?null:RunnerInput.integer(value);}
    private static Instant instant(Object value) {
        try {return Instant.parse(text(value,40)).truncatedTo(ChronoUnit.MICROS);}
        catch(DateTimeException e){throw new IllegalArgumentException("UTC telemetry instant required");}
    }
}

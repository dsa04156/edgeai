package io.edgeai.domain.repository;

import io.edgeai.domain.stream.*;
import java.time.Instant;
import java.util.*;

/** Mutations run under optional VD -> Device -> Run -> Route locks. No broker I/O here. */
public interface DataRouteRepository {
    Optional<DataRoute> route(UUID id,boolean lock);
    Optional<DataRoute> input(UUID runId,UUID taskId,String port);
    List<DataRoute> forRun(UUID runId,int limit,int offset);
    void create(DataRoute route);
    Optional<RouteGeneration> generation(UUID id);
    Optional<RouteGeneration> open(UUID routeId);
    long lastGeneration(UUID routeId);
    List<RouteGeneration> history(UUID routeId,int limit,int offset);
    void prepare(DataRoute route,RouteGeneration generation);
    void activate(UUID id,Instant now);
    void renew(UUID id,Instant until,Instant now);
    void fence(UUID id,String reason,Instant now);
    void closed(UUID id,Instant now);
}

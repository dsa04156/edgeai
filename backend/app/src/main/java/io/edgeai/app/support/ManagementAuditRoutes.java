package io.edgeai.app.support;

import io.edgeai.domain.audit.ManagementAudit;
import java.util.List;
import java.util.UUID;
import org.springframework.util.AntPathMatcher;

/** Fixed contract vocabulary; raw paths and non-UUID path variables never enter audit persistence. */
public final class ManagementAuditRoutes {
    private ManagementAuditRoutes() {}
    public record Route(String method,String template,String operation) {}
    public static final List<Route> ROUTES=List.of(
        new Route("GET","/api/v1/platform","getPlatform"),
        new Route("GET","/api/v1/csrf","getCsrfToken"),
        new Route("POST","/api/v1/profiles/{kind}","publishProfile"),
        new Route("GET","/api/v1/profiles/{kind}","listProfiles"),
        new Route("GET","/api/v1/profiles/{kind}/{key}/versions/{version}","getProfileVersion"),
        new Route("DELETE","/api/v1/profiles/{kind}/{key}/versions/{version}","deleteProfileVersion"),
        new Route("POST","/api/v1/devices","registerDevice"),
        new Route("GET","/api/v1/devices","listDevices"),
        new Route("GET","/api/v1/devices/{deviceId}","getDevice"),
        new Route("PATCH","/api/v1/devices/{deviceId}","updateDevice"),
        new Route("DELETE","/api/v1/devices/{deviceId}","releaseDevice"),
        new Route("DELETE","/api/v1/devices/{deviceId}/registration","deleteDeviceRegistration"),
        new Route("PUT","/api/v1/devices/{deviceId}/attachments/{nodeId}","attachDevice"),
        new Route("POST","/api/v1/devices/{deviceId}/sessions","openDeviceSession"),
        new Route("POST","/api/v1/devices/{deviceId}/observations","reportDeviceObservation"),
        new Route("GET","/api/v1/nodes","listNodes"),
        new Route("GET","/api/v1/node-metrics","getNodeMetrics"),
        new Route("GET","/api/v1/infrastructure","getInfrastructure"),
        new Route("GET","/api/v1/sensors/readings","getSensorReadings"),
        new Route("GET","/api/v1/sensors/commands","getSensorCommands"),
        new Route("POST","/api/v1/sensors/command","executeSensorCommand"),
        new Route("GET","/api/v1/sensors/registration-options","getSensorRegistrationOptions"),
        new Route("POST","/api/v1/sensors/registrations","registerSensor"),
        new Route("GET","/api/v1/nodes/{nodeId}","getNode"),
        new Route("POST","/api/v1/workflows","createWorkflow"),
        new Route("GET","/api/v1/workflows","listWorkflows"),
        new Route("GET","/api/v1/workflows/{workflowId}","getWorkflow"),
        new Route("POST","/api/v1/workflows/{workflowId}/versions","publishWorkflowVersion"),
        new Route("POST","/api/v1/workflow-runs","createWorkflowRun"),
        new Route("GET","/api/v1/workflow-runs","listWorkflowRuns"),
        new Route("GET","/api/v1/workflow-runs/{runId}","getWorkflowRun"),
        new Route("GET","/api/v1/workflow-runs/{runId}/placements","getRunPlacements"),
        new Route("GET","/api/v1/workflow-runs/{runId}/streams","listRunStreamRoutes"),
        new Route("POST","/api/v1/workflow-runs/{runId}/cancel","cancelWorkflowRun"),
        new Route("GET","/api/v1/tasks/{taskId}","getTask"),
        new Route("POST","/api/v1/tasks/{taskId}/cancel","cancelTask"),
        new Route("GET","/api/v1/tasks/{taskId}/results","getTaskResults"),
        new Route("POST","/api/v1/tasks/{taskId}/offload","offloadTask"),
        new Route("GET","/api/v1/operations/{operationId}","getOperation"),
        new Route("POST","/api/v1/virtual-devices","createVirtualDevice"),
        new Route("GET","/api/v1/virtual-devices","listVirtualDevices"),
        new Route("GET","/api/v1/virtual-devices/{vdId}","getVirtualDevice"),
        new Route("PATCH","/api/v1/virtual-devices/{vdId}","updateVirtualDevice"),
        new Route("DELETE","/api/v1/virtual-devices/{vdId}","releaseVirtualDevice"),
        new Route("DELETE","/api/v1/virtual-devices/{vdId}/registration","deleteVirtualDeviceRegistration"),
        new Route("POST","/api/v1/virtual-devices/{vdId}/provision","provisionVirtualDevice"),
        new Route("POST","/api/v1/virtual-devices/{vdId}/replace","replaceVirtualDeviceRuntime"),
        new Route("POST","/api/v1/virtual-devices/{vdId}/drain","drainVirtualDeviceRuntime"),
        new Route("GET","/api/v1/virtual-devices/{vdId}/execution","getVirtualDeviceExecution"),
        new Route("POST","/api/v1/devices/{deviceId}/sessions/{sessionId}/stream-token","issueDeviceStreamToken"),
        new Route("GET","/api/v1/audit-requests","listManagementAuditRequests"),
        new Route("GET","/api/v1/audit-requests/{auditId}","getManagementAuditRequest"));
    private static final AntPathMatcher MATCHER=new AntPathMatcher();
    public static ManagementAudit.Request request(String method,String path) {
        String safeMethod=method.matches("[A-Z]{1,16}")?method:"OTHER";
        for (var route:ROUTES) {
            if (!route.method().equals(method) || !MATCHER.match(route.template(),path)) continue;
            var variables=MATCHER.extractUriTemplateVariables(route.template(),path);
            var ids=variables.values().stream().filter(v->v.matches("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}"))
                .map(UUID::fromString).limit(2).toList();
            return new ManagementAudit.Request(UUID.randomUUID(),safeMethod,route.operation(),route.template(),
                ids.isEmpty()?null:ids.get(0),ids.size()<2?null:ids.get(1));
        }
        return new ManagementAudit.Request(UUID.randomUUID(),safeMethod,"unmappedManagementRequest","/api/v1/**",null,null);
    }
}

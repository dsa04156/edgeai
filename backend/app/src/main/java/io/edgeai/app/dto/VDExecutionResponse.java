package io.edgeai.app.dto;

import io.edgeai.domain.vd.VDRuntimeBinding;
import java.time.Instant;
import java.util.*;

public record VDExecutionResponse(UUID vdId,boolean enabled,Instant asOf,VDRuntimeResponse current,
        VDOperationResponse pendingOperation,List<VDRuntimeResponse> runtimeHistory,boolean runtimeHistoryTruncated,
        List<VDRuntimeBinding> bindings,boolean bindingsTruncated,List<VDOperationResponse> operations,boolean operationsTruncated) {}

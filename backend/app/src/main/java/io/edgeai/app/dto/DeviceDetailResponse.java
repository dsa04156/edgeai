package io.edgeai.app.dto;
import java.time.Instant;
import java.util.*;
public record DeviceDetailResponse(DeviceResponse device, List<DeviceAttachmentResponse> attachments, List<DeviceSessionResponse> sessions, List<DeviceObservationResponse> observations) {

}

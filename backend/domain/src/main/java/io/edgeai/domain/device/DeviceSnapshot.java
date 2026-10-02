package io.edgeai.domain.device;
import java.util.List;
public record DeviceSnapshot(Device device,List<DeviceAttachment> attachments,List<DeviceSession> sessions,List<DeviceObservation> observations) {}

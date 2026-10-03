package io.edgeai.app.config;
import java.util.UUID;
public record DeviceStreamPrincipal(UUID deviceId,UUID sessionId,long epoch) {}

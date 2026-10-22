package io.edgeai.app.service;

import io.edgeai.app.dto.InferenceModelsResponse;
import io.edgeai.app.dto.InferenceResultResponse;
import io.edgeai.app.exception.ControlPlaneException;
import io.edgeai.app.exception.ProfilePayloadTooLargeException;
import io.edgeai.app.support.JsonDocuments;
import io.edgeai.domain.inference.ObjectDetectionRuntime;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Semaphore;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Sends one validated image to the configured detection runtime; nothing is stored. */
@Service
public class InferenceService {
    public static final int MAX_IMAGE_BYTES = 4 << 20, MAX_SIDE = 4096, MAX_PIXELS = 16_777_216, MAX_DETECTIONS = 100;
    // Base64 of the largest image plus the JSON envelope.
    private static final int MAX_BODY_BYTES = 6 << 20;
    private static final JsonDocuments JSON = new JsonDocuments();
    private record Binding(String id, String name, String nodeName, String deviceName, String provider) {}
    private record Image(byte[] bytes, int width, int height) {}
    private final ObjectDetectionRuntime runtime;
    private final Binding binding;
    private final Semaphore inFlight = new Semaphore(1);

    public InferenceService(ObjectProvider<ObjectDetectionRuntime> runtime,
            @Value("${edgeai.inference.model-id:}") String id, @Value("${edgeai.inference.model-name:}") String name,
            @Value("${edgeai.inference.node-name:}") String nodeName, @Value("${edgeai.inference.device-name:}") String deviceName,
            @Value("${edgeai.inference.provider:}") String provider) {
        this.runtime = runtime.getIfAvailable();
        if (this.runtime == null) { binding = null; return; }
        for (String value : List.of(id, name, nodeName, deviceName, provider))
            if (value.isBlank()) throw new IllegalStateException("Enabled inference requires model, node, device and provider");
        binding = new Binding(id, name, nodeName, deviceName, provider);
    }

    public InferenceModelsResponse models() {
        if (binding == null) return new InferenceModelsResponse(List.of());
        return new InferenceModelsResponse(List.of(new InferenceModelsResponse.Model(binding.id(), binding.name(),
            binding.nodeName(), binding.deviceName(), ready())));
    }

    public InferenceResultResponse predict(InputStream body) throws IOException {
        byte[] raw = body.readNBytes(MAX_BODY_BYTES + 1);
        if (raw.length > MAX_BODY_BYTES) throw tooLarge();
        Object parsed;
        try { parsed = JSON.parse(new String(raw, StandardCharsets.UTF_8), MAX_BODY_BYTES); }
        catch (ProfilePayloadTooLargeException e) { throw tooLarge(); }
        if (!(parsed instanceof Map<?, ?> request) || !request.keySet().equals(Set.of("modelId", "imageBase64"))
                || !(request.get("modelId") instanceof String modelId) || !(request.get("imageBase64") instanceof String encoded))
            throw invalid();
        if (binding == null || !binding.id().equals(modelId))
            throw new ControlPlaneException(404, "INFERENCE_MODEL_NOT_FOUND", "설정된 추론 모델이 아닙니다.");
        var image = image(encoded);
        if (!inFlight.tryAcquire()) throw busy();
        try { return result(runtime.predict(Base64.getEncoder().encodeToString(image.bytes())), image); }
        catch (ObjectDetectionRuntime.Failure failure) {
            throw switch (failure.reason()) {
                case BUSY -> busy();
                case REJECTED -> new ControlPlaneException(400, "INFERENCE_IMAGE_REJECTED", "추론 런타임이 이미지를 처리하지 못했습니다.");
                case TOO_LARGE -> tooLarge();
                case UNAVAILABLE -> new ControlPlaneException(503, "INFERENCE_RUNTIME_UNAVAILABLE", "추론 런타임에 연결할 수 없거나 응답 시간이 초과됐습니다.");
                case INVALID_RESPONSE -> invalidResponse();
            };
        }
        finally { inFlight.release(); }
    }

    private boolean ready() {
        try {
            var health = runtime.health();
            return "READY".equals(health.get("status")) && identified(health);
        } catch (ObjectDetectionRuntime.Failure failure) { return false; }
    }

    private boolean identified(Map<?, ?> document) {
        return binding.id().equals(document.get("modelId")) && binding.nodeName().equals(document.get("nodeName"))
            && binding.deviceName().equals(document.get("deviceName")) && binding.provider().equals(document.get("provider"));
    }

    private static Image image(String encoded) {
        if ((long) encoded.length() * 3 / 4 > MAX_IMAGE_BYTES + 2) throw tooLarge();
        byte[] bytes;
        try { bytes = Base64.getDecoder().decode(encoded); }
        catch (IllegalArgumentException e) { throw invalid(); }
        if (bytes.length > MAX_IMAGE_BYTES) throw tooLarge();
        String format;
        if (bytes.length > 8 && Arrays.equals(Arrays.copyOf(bytes, 8), new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'})) format = "png";
        else if (bytes.length > 3 && (bytes[0] & 255) == 0xff && (bytes[1] & 255) == 0xd8 && (bytes[2] & 255) == 0xff) format = "jpeg";
        else throw invalid();
        // Header dimensions only; the runtime decodes the pixels.
        var reader = ImageIO.getImageReadersByFormatName(format).next();
        int width, height;
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            reader.setInput(input, true, true);
            width = reader.getWidth(0); height = reader.getHeight(0);
        } catch (IOException | RuntimeException e) { throw invalid(); }
        finally { reader.dispose(); }
        if (width < 1 || height < 1 || width > MAX_SIDE || height > MAX_SIDE || (long) width * height > MAX_PIXELS) throw invalid();
        return new Image(bytes, width, height);
    }

    private InferenceResultResponse result(Map<?, ?> result, Image image) {
        try {
            if (!identified(result) || !Integer.valueOf(image.width()).equals(integer(result.get("imageWidth")))
                    || !Integer.valueOf(image.height()).equals(integer(result.get("imageHeight"))))
                throw invalidResponse();
            double inferenceMs = number(result.get("inferenceMs"));
            if (inferenceMs < 0 || !(result.get("detections") instanceof List<?> items) || items.size() > MAX_DETECTIONS) throw invalidResponse();
            var detections = new ArrayList<InferenceResultResponse.Detection>();
            for (var item : items) {
                if (!(item instanceof Map<?, ?> detection) || !(detection.get("label") instanceof String label)
                        || label.isBlank() || label.length() > 128) throw invalidResponse();
                double score = number(detection.get("score")), x = number(detection.get("x")), y = number(detection.get("y"));
                double width = number(detection.get("width")), height = number(detection.get("height"));
                // Boxes are original-image pixels; allow half a pixel of rounding at the edges.
                if (score < 0 || score > 1 || x < 0 || y < 0 || width <= 0 || height <= 0
                        || x + width > image.width() + 0.5 || y + height > image.height() + 0.5) throw invalidResponse();
                detections.add(new InferenceResultResponse.Detection(label, score, x, y, width, height));
            }
            return new InferenceResultResponse(binding.id(), binding.nodeName(), binding.deviceName(), binding.provider(),
                image.width(), image.height(), inferenceMs, detections);
        } catch (IllegalArgumentException e) { throw invalidResponse(); }
    }

    private static Integer integer(Object value) {
        return value instanceof BigInteger number && number.bitLength() < 32 ? number.intValue() : null;
    }
    private static double number(Object value) {
        if (value instanceof BigInteger || value instanceof BigDecimal) {
            double number = ((Number) value).doubleValue();
            if (Double.isFinite(number)) return number;
        }
        throw new IllegalArgumentException("Number required");
    }
    private static ControlPlaneException invalid() {
        return new ControlPlaneException(400, "INVALID_INFERENCE_REQUEST", "modelId와 4096×4096 이하 JPEG/PNG의 base64(imageBase64)를 입력하세요.");
    }
    private static ControlPlaneException tooLarge() {
        return new ControlPlaneException(413, "INFERENCE_IMAGE_TOO_LARGE", "이미지는 4 MiB 이하로 업로드하세요.");
    }
    private static ControlPlaneException busy() {
        return new ControlPlaneException(503, "INFERENCE_BUSY", "다른 추론이 진행 중입니다. 잠시 후 다시 시도하세요.");
    }
    private static ControlPlaneException invalidResponse() {
        return new ControlPlaneException(502, "INFERENCE_RUNTIME_INVALID_RESPONSE", "추론 런타임 응답을 검증하지 못했습니다.");
    }
}

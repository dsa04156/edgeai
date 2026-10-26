package io.edgeai.app.service;

import io.edgeai.app.exception.ControlPlaneException;
import io.edgeai.app.support.JsonDocuments;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Reads the ustar frames artifact written by the Hawkeye capture workload: manifest.json plus frame-NNNN.jpg members.
 * Anything else in the archive (links, PAX headers, unknown names) is rejected.
 */
final class HawkeyeFrames {
    private static final JsonDocuments JSON = new JsonDocuments();
    private static final Pattern FRAME = Pattern.compile("frame-[0-9]{4}\\.jpg");
    private HawkeyeFrames() {}

    static byte[] frame(byte[] tar, int seq) {
        return frame(members(tar), seq);
    }

    static byte[] frame(Map<String, byte[]> members, int seq) {
        if (!(JSON.parse(new String(Objects.requireNonNull(members.get("manifest.json"), "manifest"), StandardCharsets.UTF_8), 1048576)
                instanceof Map<?, ?> manifest) || !"edgeai.hawkeye.frames/v1".equals(manifest.get("apiVersion"))
                || !(manifest.get("frames") instanceof List<?> frames)) throw invalid();
        for (var item : frames) {
            if (!(item instanceof Map<?, ?> frame) || !(frame.get("seq") instanceof Number number) || number.intValue() != seq) continue;
            if (!(frame.get("file") instanceof String file) || !FRAME.matcher(file).matches() || !(frame.get("sha256") instanceof String sha)) throw invalid();
            byte[] image = members.get(file);
            int n = image == null ? 0 : image.length;
            if (n < 4 || !sha256(image).equals(sha) || (image[0] & 255) != 255 || (image[1] & 255) != 216
                    || (image[n - 2] & 255) != 255 || (image[n - 1] & 255) != 217) throw invalid();
            return image;
        }
        throw new ControlPlaneException(404, "FRAME_NOT_FOUND", "확정된 영상 프레임이 없습니다.");
    }

    static Map<String, byte[]> members(byte[] tar) {
        var members = new HashMap<String, byte[]>();
        int offset = 0;
        try {
            while (offset + 512 <= tar.length) {
                if (zero(tar, offset)) return members;
                String name = text(tar, offset, 100);
                if (text(tar, offset + 257, 6).equals("ustar") && !text(tar, offset + 345, 155).isEmpty()) throw invalid();
                byte type = tar[offset + 156];
                long size = Long.parseLong(text(tar, offset + 124, 12).trim(), 8);
                if ((type != '0' && type != 0) || size < 0 || size > tar.length - offset - 512L
                        || !(name.equals("manifest.json") || FRAME.matcher(name).matches()) || members.containsKey(name)) throw invalid();
                members.put(name, Arrays.copyOfRange(tar, offset + 512, offset + 512 + (int) size));
                offset += 512 + (int) ((size + 511) / 512 * 512);
            }
        } catch (NumberFormatException e) { throw invalid(); }
        throw invalid();
    }

    private static boolean zero(byte[] data, int offset) {
        for (int i = offset; i < offset + 512; i++) if (data[i] != 0) return false;
        return true;
    }
    private static String text(byte[] data, int offset, int length) {
        int end = offset;
        while (end < offset + length && data[end] != 0) end++;
        return new String(data, offset, end - offset, StandardCharsets.US_ASCII);
    }
    static String sha256(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static ControlPlaneException invalid() {
        return new ControlPlaneException(502, "FRAMES_ARTIFACT_INVALID", "확정된 프레임 파일의 형식이나 SHA-256이 맞지 않습니다.");
    }
}

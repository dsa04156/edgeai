package io.edgeai.app.dto;
import java.time.Instant;
import java.util.*;
public record ResourcePageResponse<T>(List<T> items, Integer nextOffset) {
    
}

package io.edgeai.domain.aimodel;

import java.util.List;

/** One registry listing page; {@code next} is the explicit continuation value ("last") or null when complete. */
public record RegistryPage(List<String> items, String next) {
    public RegistryPage { items = List.copyOf(items); }
}

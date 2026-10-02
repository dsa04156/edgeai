package io.edgeai.app.dto;

import java.util.List;

public record ProfilePageResponse(List<ProfileVersionResponse> items, Integer nextOffset) {}

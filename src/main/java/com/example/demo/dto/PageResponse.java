package com.example.demo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.data.domain.Page;

import java.util.List;

@Schema(description = "公開APIの固定ページレスポンス")
public record PageResponse<T>(
        List<T> content,
        @Schema(description = "0-based page number", example = "0") int page,
        @Schema(description = "Page size", example = "20") int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last) {

    public PageResponse {
        content = List.copyOf(content);
    }

    public static <T> PageResponse<T> from(Page<T> source) {
        return new PageResponse<>(source.getContent(), source.getNumber(), source.getSize(),
                source.getTotalElements(), source.getTotalPages(), source.isFirst(), source.isLast());
    }
}

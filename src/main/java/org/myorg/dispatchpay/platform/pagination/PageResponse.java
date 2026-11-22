package org.myorg.dispatchpay.platform.pagination;

import java.util.List;
import java.util.function.Function;

import org.springframework.data.domain.Page;

/** Stable page envelope; never serialize Spring Data's {@code Page} directly. */
public record PageResponse<T>(List<T> items, int page, int size, long totalElements, int totalPages) {

    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }

    public static <T> PageResponse<T> of(List<T> items, PageQuery query, long totalElements) {
        int totalPages = (int) ((totalElements + query.size() - 1) / query.size());
        return new PageResponse<>(items, query.page(), query.size(), totalElements, totalPages);
    }

    public <R> PageResponse<R> map(Function<? super T, ? extends R> mapper) {
        List<R> mapped = items.stream().<R>map(mapper).toList();
        return new PageResponse<>(mapped, page, size, totalElements, totalPages);
    }
}

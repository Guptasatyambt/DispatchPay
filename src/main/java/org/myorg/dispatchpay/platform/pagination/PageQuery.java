package org.myorg.dispatchpay.platform.pagination;

import org.myorg.dispatchpay.platform.error.ApiException;
import org.myorg.dispatchpay.platform.error.ErrorCode;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Zero-based offset pagination with a hard page-size cap. Controllers declare a
 * {@code PageQuery} parameter and it is resolved from {@code ?page=&size=}.
 */
public record PageQuery(int page, int size) {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    public PageQuery {
        if (page < 0) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "page must be greater than or equal to 0");
        }
        if (size < 1 || size > MAX_SIZE) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "size must be between 1 and " + MAX_SIZE);
        }
    }

    public static PageQuery firstPage() {
        return new PageQuery(0, DEFAULT_SIZE);
    }

    public long offset() {
        return (long) page * size;
    }

    public Pageable toPageable(Sort sort) {
        return PageRequest.of(page, size, sort);
    }
}

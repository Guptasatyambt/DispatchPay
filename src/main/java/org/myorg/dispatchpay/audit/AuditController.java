package org.myorg.dispatchpay.audit;

import java.time.Instant;
import java.time.format.DateTimeParseException;

import org.myorg.dispatchpay.audit.AuditHistoryService.AuditEventView;
import org.myorg.dispatchpay.iam.access.BusinessAccess;
import org.myorg.dispatchpay.platform.error.ApiException;
import org.myorg.dispatchpay.platform.error.ErrorCode;
import org.myorg.dispatchpay.platform.pagination.PageQuery;
import org.myorg.dispatchpay.platform.pagination.PageResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Audit history of the business selected with {@value BusinessAccess#HEADER}, newest first.
 * Optional filters: {@code resourceType}, {@code resourceId}, and {@code from} (inclusive) /
 * {@code to} (exclusive) as ISO-8601 instants. History is read-only: there is no write endpoint.
 */
@RestController
@RequestMapping("/v1/audit-events")
public class AuditController {

    private final AuditHistoryService historyService;

    public AuditController(AuditHistoryService historyService) {
        this.historyService = historyService;
    }

    @GetMapping
    PageResponse<AuditEventView> list(
            BusinessAccess access,
            PageQuery page,
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) String resourceId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        Instant fromInstant = parse(from, "from");
        Instant toInstant = parse(to, "to");
        if (fromInstant != null && toInstant != null && !fromInstant.isBefore(toInstant)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "from must be before to");
        }
        return historyService.find(access, blankToNull(resourceType), blankToNull(resourceId), fromInstant,
                toInstant, page);
    }

    private static Instant parse(String raw, String name) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(raw.strip());
        } catch (DateTimeParseException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    name + " must be an ISO-8601 instant such as 2026-11-25T00:00:00Z");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}

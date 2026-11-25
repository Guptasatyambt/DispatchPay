package org.myorg.dispatchpay.iam.worker;

import java.util.UUID;

import org.myorg.dispatchpay.iam.access.BusinessAccess;
import org.myorg.dispatchpay.iam.worker.WorkerService.WorkerDetail;
import org.myorg.dispatchpay.iam.worker.WorkerService.WorkerSummary;
import org.myorg.dispatchpay.platform.error.ApiException;
import org.myorg.dispatchpay.platform.error.ErrorCode;
import org.myorg.dispatchpay.platform.pagination.PageQuery;
import org.myorg.dispatchpay.platform.pagination.PageResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Owner-only list and detail of the selected business's workers. */
@RestController
@RequestMapping("/v1/iam/workers")
public class WorkerController {

    private final WorkerService workerService;

    public WorkerController(WorkerService workerService) {
        this.workerService = workerService;
    }

    @GetMapping
    PageResponse<WorkerSummary> list(BusinessAccess access, PageQuery page) {
        return workerService.list(access, page);
    }

    @GetMapping("/{id}")
    WorkerDetail get(BusinessAccess access, @PathVariable String id) {
        return workerService.get(access, parse(id));
    }

    /** A malformed id is just another id that does not exist here. */
    private static UUID parse(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.NOT_FOUND, "Worker not found");
        }
    }
}

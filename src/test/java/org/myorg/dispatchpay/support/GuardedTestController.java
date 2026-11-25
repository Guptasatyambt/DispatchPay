package org.myorg.dispatchpay.support;

import java.util.Map;

import org.myorg.dispatchpay.iam.access.BusinessAccess;
import org.myorg.dispatchpay.iam.access.Permission;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Stands in for a permission-gated business operation until real ones exist. */
@RestController
public class GuardedTestController {

    @PostMapping("/v1/test/guarded/order-create")
    Map<String, String> createOrder(BusinessAccess access) {
        access.require(Permission.ORDER_CREATE);
        return Map.of("membershipId", access.membershipId().toString());
    }
}

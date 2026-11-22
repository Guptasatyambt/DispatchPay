package org.myorg.dispatchpay.iam.user;

import java.util.UUID;

/** Global identity. Business access comes from memberships, never from the account itself. */
public record UserAccount(UUID id, String mobile, Status status) {

    public enum Status {
        ACTIVE, DISABLED
    }

    public boolean isActive() {
        return status == Status.ACTIVE;
    }
}

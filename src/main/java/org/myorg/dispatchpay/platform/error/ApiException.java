package org.myorg.dispatchpay.platform.error;

/**
 * Business or request failure that maps to a documented error code. The detail message is
 * returned to the client, so it must never contain secrets or another tenant's data.
 */
public class ApiException extends RuntimeException {

    private final ErrorCode code;

    public ApiException(ErrorCode code, String detail) {
        super(detail);
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }
}

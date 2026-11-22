package org.myorg.dispatchpay.iam.auth.otp;

import java.time.Duration;

import org.myorg.dispatchpay.iam.auth.MobileNumber;

/**
 * SMS provider adapter. Implementations must never log the code (outside a dev-only sender)
 * and are called after the challenge transaction commits, never inside it.
 */
public interface OtpSender {

    void send(MobileNumber mobile, String code, Duration validity);
}

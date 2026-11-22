package org.myorg.dispatchpay.iam.auth.otp;

import java.time.Duration;

import org.myorg.dispatchpay.iam.auth.MobileNumber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Local development only: prints the OTP to the console instead of sending an SMS.
 * Other profiles must provide a real {@link OtpSender} or the application will not start.
 */
@Component
@Profile("dev")
class DevLoggingOtpSender implements OtpSender {

    private static final Logger log = LoggerFactory.getLogger(DevLoggingOtpSender.class);

    @Override
    public void send(MobileNumber mobile, String code, Duration validity) {
        log.warn("[DEV ONLY] OTP for {} is {} (valid {})", mobile.masked(), code, validity);
    }
}

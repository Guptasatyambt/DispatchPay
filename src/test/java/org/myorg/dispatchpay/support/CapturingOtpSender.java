package org.myorg.dispatchpay.support;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.myorg.dispatchpay.iam.auth.MobileNumber;
import org.myorg.dispatchpay.iam.auth.otp.OtpSender;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Records the last code per mobile instead of sending SMS; can simulate provider failure. */
@Component
@Profile("test")
public class CapturingOtpSender implements OtpSender {

    private final Map<String, String> lastCodes = new ConcurrentHashMap<>();
    private final AtomicInteger sends = new AtomicInteger();
    private volatile boolean failing;

    @Override
    public void send(MobileNumber mobile, String code, Duration validity) {
        sends.incrementAndGet();
        if (failing) {
            throw new IllegalStateException("simulated SMS provider outage");
        }
        lastCodes.put(mobile.e164(), code);
    }

    public String lastCode(String e164) {
        return lastCodes.get(e164);
    }

    public int sends() {
        return sends.get();
    }

    public void failing(boolean failing) {
        this.failing = failing;
    }

    public void reset() {
        lastCodes.clear();
        sends.set(0);
        failing = false;
    }
}

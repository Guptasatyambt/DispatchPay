package org.myorg.dispatchpay.support;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.myorg.dispatchpay.iam.auth.MobileNumber;
import org.myorg.dispatchpay.iam.invitation.InvitationSender;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Records the last invitation token per mobile instead of sending SMS; can simulate provider failure. */
@Component
@Profile("test")
public class CapturingInvitationSender implements InvitationSender {

    private final Map<String, String> lastTokens = new ConcurrentHashMap<>();
    private final AtomicInteger sends = new AtomicInteger();
    private volatile boolean failing;

    @Override
    public void send(MobileNumber mobile, String businessName, String token, Instant expiresAt) {
        sends.incrementAndGet();
        if (failing) {
            throw new IllegalStateException("simulated SMS provider outage");
        }
        lastTokens.put(mobile.e164(), token);
    }

    public String lastToken(String e164) {
        return lastTokens.get(e164);
    }

    public int sends() {
        return sends.get();
    }

    public void failing(boolean failing) {
        this.failing = failing;
    }

    public void reset() {
        lastTokens.clear();
        sends.set(0);
        failing = false;
    }
}

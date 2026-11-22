package org.myorg.dispatchpay.iam.auth;

import org.myorg.dispatchpay.iam.auth.otp.OtpService;
import org.myorg.dispatchpay.iam.auth.session.SessionService;
import org.myorg.dispatchpay.iam.auth.session.SessionTokens;
import org.myorg.dispatchpay.iam.user.UserAccount;
import org.myorg.dispatchpay.iam.user.UserAccountRepository;
import org.myorg.dispatchpay.platform.error.ApiException;
import org.myorg.dispatchpay.platform.error.ErrorCode;
import org.myorg.dispatchpay.platform.ratelimit.RateLimiter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** OTP sign-in: verifying the code, creating the identity on first use and opening a session. */
@Service
public class AuthService {

    private enum Rejection { INVALID_OTP, DISABLED }

    private record VerifyOutcome(SessionTokens tokens, Rejection rejection) {
    }

    private final OtpService otpService;
    private final SessionService sessionService;
    private final UserAccountRepository users;
    private final RateLimiter rateLimiter;
    private final AuthProperties properties;
    private final TransactionTemplate transactionTemplate;

    public AuthService(OtpService otpService, SessionService sessionService, UserAccountRepository users,
            RateLimiter rateLimiter, AuthProperties properties, PlatformTransactionManager transactionManager) {
        this.otpService = otpService;
        this.sessionService = sessionService;
        this.users = users;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * Code check, account creation and session issue commit together. A wrong code is reported
     * only after its failed-attempt count has committed, so retries cannot bypass the lockout.
     */
    public SessionTokens verifyOtp(MobileNumber mobile, String code, String deviceId, String deviceName,
            String clientIp) {
        rateLimiter.consume("otp-verify:ip", clientIp, properties.otp().limits().verifyPerIp());

        VerifyOutcome outcome = transactionTemplate.execute(status -> {
            if (!otpService.verify(mobile, code)) {
                return new VerifyOutcome(null, Rejection.INVALID_OTP);
            }
            UserAccount user = users.findOrCreateByMobile(mobile.e164());
            if (!user.isActive()) {
                return new VerifyOutcome(null, Rejection.DISABLED);
            }
            return new VerifyOutcome(sessionService.open(user, deviceId, deviceName), null);
        });

        if (outcome.rejection() == Rejection.INVALID_OTP) {
            throw new ApiException(ErrorCode.INVALID_OTP, "The code is invalid or has expired");
        }
        if (outcome.rejection() == Rejection.DISABLED) {
            throw new ApiException(ErrorCode.ACCOUNT_DISABLED, "This account is disabled");
        }
        return outcome.tokens();
    }
}

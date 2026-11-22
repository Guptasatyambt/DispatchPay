package org.myorg.dispatchpay.iam.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.myorg.dispatchpay.iam.auth.AuthDtos.OtpRequest;
import org.myorg.dispatchpay.iam.auth.AuthDtos.OtpRequestAccepted;
import org.myorg.dispatchpay.iam.auth.AuthDtos.OtpVerifyRequest;
import org.myorg.dispatchpay.iam.auth.AuthDtos.RefreshRequest;
import org.myorg.dispatchpay.iam.auth.AuthDtos.TokenResponse;
import org.myorg.dispatchpay.iam.auth.otp.OtpService;
import org.myorg.dispatchpay.iam.auth.session.SessionService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public authentication endpoints. Client IP comes from {@code getRemoteAddr()}; behind a proxy,
 * enable {@code server.forward-headers-strategy} so it reflects the real client.
 */
@RestController
@RequestMapping("/v1/auth")
public class AuthController {

    private static final String GENERIC_OTP_MESSAGE = "If the number can receive SMS, a code has been sent";

    private final OtpService otpService;
    private final AuthService authService;
    private final SessionService sessionService;

    public AuthController(OtpService otpService, AuthService authService, SessionService sessionService) {
        this.otpService = otpService;
        this.authService = authService;
        this.sessionService = sessionService;
    }

    @PostMapping("/otp/request")
    ResponseEntity<OtpRequestAccepted> requestOtp(@Valid @RequestBody OtpRequest body, HttpServletRequest request) {
        OtpService.OtpIssued issued = otpService.request(MobileNumber.parse(body.mobile()), body.deviceId(),
                request.getRemoteAddr());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new OtpRequestAccepted(GENERIC_OTP_MESSAGE, issued.validity().toSeconds(),
                        issued.resendAfter().toSeconds()));
    }

    @PostMapping("/otp/verify")
    ResponseEntity<TokenResponse> verifyOtp(@Valid @RequestBody OtpVerifyRequest body, HttpServletRequest request) {
        var tokens = authService.verifyOtp(MobileNumber.parse(body.mobile()), body.code(), body.deviceId(),
                body.deviceName(), request.getRemoteAddr());
        return noStore(TokenResponse.from(tokens));
    }

    @PostMapping("/token/refresh")
    ResponseEntity<TokenResponse> refresh(@Valid @RequestBody RefreshRequest body, HttpServletRequest request) {
        var tokens = sessionService.refresh(body.refreshToken(), request.getRemoteAddr());
        return noStore(TokenResponse.from(tokens));
    }

    private static ResponseEntity<TokenResponse> noStore(TokenResponse body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}

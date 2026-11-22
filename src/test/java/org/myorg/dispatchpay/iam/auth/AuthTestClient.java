package org.myorg.dispatchpay.iam.auth;

import org.myorg.dispatchpay.support.CapturingOtpSender;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Drives the /v1/auth endpoints from tests. */
final class AuthTestClient {

    static final String DEFAULT_IP = "203.0.113.10";

    private final MockMvc mockMvc;
    private final CapturingOtpSender otpSender;
    private final JsonMapper json = JsonMapper.builder().build();

    AuthTestClient(MockMvc mockMvc, CapturingOtpSender otpSender) {
        this.mockMvc = mockMvc;
        this.otpSender = otpSender;
    }

    ResultActions requestOtp(String mobile) throws Exception {
        return requestOtp(mobile, DEFAULT_IP);
    }

    ResultActions requestOtp(String mobile, String ip) throws Exception {
        return post("/v1/auth/otp/request", "{\"mobile\":\"" + mobile + "\"}", ip);
    }

    ResultActions verify(String mobile, String code) throws Exception {
        return post("/v1/auth/otp/verify",
                "{\"mobile\":\"" + mobile + "\",\"code\":\"" + code + "\",\"deviceId\":\"device-0001\"}", DEFAULT_IP);
    }

    ResultActions refresh(String refreshToken) throws Exception {
        return post("/v1/auth/token/refresh", "{\"refreshToken\":\"" + refreshToken + "\"}", DEFAULT_IP);
    }

    /** Requests and verifies an OTP; returns the token response. */
    JsonNode signIn(String e164) throws Exception {
        requestOtp(e164);
        String body = verify(e164, otpSender.lastCode(e164)).andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    JsonNode read(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private ResultActions post(String path, String body, String ip) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.post(path)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .with(request -> {
                    request.setRemoteAddr(ip);
                    return request;
                }));
    }
}

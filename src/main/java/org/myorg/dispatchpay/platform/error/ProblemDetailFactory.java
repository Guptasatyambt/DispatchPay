package org.myorg.dispatchpay.platform.error;

import java.time.Clock;

import org.myorg.dispatchpay.platform.web.CorrelationId;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

/** Builds the standard error envelope; shared by MVC exception handling and the security filters. */
@Component
public class ProblemDetailFactory {

    private final Clock clock;

    public ProblemDetailFactory(Clock clock) {
        this.clock = clock;
    }

    public ProblemDetail create(ErrorCode code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), detail);
        problem.setTitle(code.status().getReasonPhrase());
        enrich(problem, code);
        return problem;
    }

    public void enrich(ProblemDetail problem, ErrorCode code) {
        problem.setProperty("code", code.name());
        problem.setProperty("correlationId", CorrelationId.current().orElse(null));
        problem.setProperty("timestamp", clock.instant());
    }
}

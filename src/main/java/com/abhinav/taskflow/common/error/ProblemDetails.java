package com.abhinav.taskflow.common.error;

import com.abhinav.taskflow.common.logging.CorrelationIdFilter;
import org.slf4j.MDC;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

import java.net.URI;
import java.time.Instant;
import java.util.Optional;

/**
 * The one place that decides what a TaskFlow error body contains, used by GlobalExceptionHandler (errors inside
 * Spring MVC) and by the security chain's entry point and access-denied handler (errors before MVC is reached).
 */
public final class ProblemDetails {

    private ProblemDetails() {}

    /** A complete body for code outside Spring MVC. The status is explicit because one code can carry two (401/400). */
    public static ProblemDetail create(ErrorCode errorCode, HttpStatusCode status, String detail, String instancePath) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
        body.setType(errorCode.type());
        body.setTitle(errorCode.title());
        enrich(body, errorCode, instancePath);
        return body;
    }

    /** The properties every error body carries, whoever built it: code, timestamp, instance, correlationId. */
    public static void enrich(ProblemDetail body, ErrorCode errorCode, String instancePath) {
        body.setProperty("code", errorCode.code());
        body.setProperty("timestamp", Instant.now());
        if (body.getInstance() == null) {
            body.setInstance(URI.create(instancePath));
        }
        Optional.ofNullable(MDC.get(CorrelationIdFilter.MDC_KEY)).ifPresent(id -> body.setProperty("correlationId", id));
    }
}

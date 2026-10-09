package com.abhinav.taskflow.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;

import java.io.IOException;

/**
 * Writes a ProblemDetail straight to the servlet response, for errors raised in the filter chain (before
 * Spring MVC, so @RestControllerAdvice never sees them). Uses Boot's ObjectMapper, which carries Spring's
 * ProblemDetail mixin: the extra properties (code, correlationId, …) come out at the top level, as in MVC.
 */
class ProblemResponseWriter {

    private final ObjectMapper objectMapper;

    ProblemResponseWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    void write(HttpServletResponse response, ProblemDetail body) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(body.getStatus());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}

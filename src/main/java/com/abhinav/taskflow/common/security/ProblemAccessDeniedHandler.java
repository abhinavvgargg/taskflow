package com.abhinav.taskflow.common.security;

import com.abhinav.taskflow.common.error.CommonErrorCode;
import com.abhinav.taskflow.common.error.ProblemDetails;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;

/** Every 403 from the filter chain (denyAll, hasRole) as the same ProblemDetail as the rest of the API. */
public class ProblemAccessDeniedHandler implements AccessDeniedHandler {

    private static final String ACCESS_DENIED_DETAIL = "You don't have permission to access this resource.";

    private final ProblemResponseWriter writer;

    ProblemAccessDeniedHandler(ProblemResponseWriter writer) {
        this.writer = writer;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException accessDeniedException) throws IOException {
        writer.write(response, ProblemDetails.create(CommonErrorCode.ACCESS_DENIED, HttpStatus.FORBIDDEN,
                ACCESS_DENIED_DETAIL, request.getRequestURI()));
    }
}

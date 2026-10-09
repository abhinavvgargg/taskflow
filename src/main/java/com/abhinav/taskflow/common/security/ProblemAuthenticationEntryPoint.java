package com.abhinav.taskflow.common.security;

import com.abhinav.taskflow.common.error.CommonErrorCode;
import com.abhinav.taskflow.common.error.ProblemDetails;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.server.resource.BearerTokenError;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.IOException;

/**
 * Every 401 (and the 400 for several bearer tokens) as the same ProblemDetail as the rest of the API.
 *
 * <p>Two callers, set in two places in the chain: the bearer filter calls it when a presented token is rejected,
 * and ExceptionTranslationFilter calls it when a protected path is reached with no token at all.
 *
 * <p>The detail is a fixed sentence per code, never the exception's message: the JWT library's messages describe
 * the token ("Jwt expired at …"). For the same reason the WWW-Authenticate header carries only the error code,
 * not Spring's error_description.
 */
public class ProblemAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private static final String AUTHENTICATION_REQUIRED_DETAIL = "Authentication is required to access this resource.";
    private static final String INVALID_ACCESS_TOKEN_DETAIL = "The access token was rejected.";

    private final ProblemResponseWriter writer;

    ProblemAuthenticationEntryPoint(ProblemResponseWriter writer) {
        this.writer = writer;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException authException) throws IOException {
        if (authException instanceof OAuth2AuthenticationException oauth2Exception) {
            String errorCode = oauth2Exception.getError().getErrorCode();
            // Keep the status Spring chose: 401 invalid_token, 400 invalid_request (several tokens)
            HttpStatusCode status = oauth2Exception.getError() instanceof BearerTokenError bearerTokenError
                    ? bearerTokenError.getHttpStatus()
                    : HttpStatus.UNAUTHORIZED;

            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"" + errorCode + "\"");
            writer.write(response, ProblemDetails.create(CommonErrorCode.INVALID_ACCESS_TOKEN, status,
                    INVALID_ACCESS_TOKEN_DETAIL, request.getRequestURI()));
            return;
        }

        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        writer.write(response, ProblemDetails.create(CommonErrorCode.AUTHENTICATION_REQUIRED, HttpStatus.UNAUTHORIZED,
                AUTHENTICATION_REQUIRED_DETAIL, request.getRequestURI()));
    }
}

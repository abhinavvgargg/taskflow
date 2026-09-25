package com.abhinav.taskflow.common.error;

import com.abhinav.taskflow.common.logging.CorrelationIdFilter;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(ApplicationException.class)
    public ResponseEntity<Object> handleApplicationException(ApplicationException ex, WebRequest request)
    {
        log.debug("Domain exception [code={}]: {}", ex.getErrorCode().code(), ex.getMessage());
        return problem(ex, ex.getErrorCode(), ex.getMessage(), ex.getProperties(), request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Object> handleDataIntegrityViolation(DataIntegrityViolationException ex, WebRequest request)
    {
        String constraint = CommonErrorUtility.constraintNameOf(ex);
        log.warn("Constraint violation [constraint={}]", constraint, ex);

        return problem(ex, CommonErrorCode.RESOURCE_CONFLICT, "The request conflicts with existing data", constraint == null ? Map.of() : Map.of("constraint", constraint), request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpected(Exception ex, WebRequest request)
    {
        log.error("Unhandled exception on {}", pathOf(request), ex);   // ← full stack trace, server-side only
        return problem(ex, CommonErrorCode.INTERNAL_ERROR, "An unexpected error occurred", Map.of(), request);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request)
    {
        List<Map<String, String>> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> Map.of(
                        "field", fe.getField(),
                        "message", fe.getDefaultMessage() == null ? "is invalid" : fe.getDefaultMessage()))
                .toList();

        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, "Request validation failed");
        body.setTitle(CommonErrorCode.VALIDATION_FAILED.title());
        body.setProperty("fieldErrors", fieldErrors);

        return handleExceptionInternal(ex, body, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleNoResourceFoundException(NoResourceFoundException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {

        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, "No resource found");
        body.setTitle(CommonErrorCode.NOT_FOUND.title());

        return handleExceptionInternal(ex, body, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {

        ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, statusCode, request);

        if (response != null && response.getBody() instanceof ProblemDetail problemDetail) {
            ErrorCode errorCode = resolveErrorCode(ex, statusCode);
            problemDetail.setProperty("code", errorCode.code());
            problemDetail.setProperty("timestamp", Instant.now());
            if (problemDetail.getInstance() == null) {
                problemDetail.setInstance(URI.create(pathOf(request)));
            }
            Optional.ofNullable(MDC.get(CorrelationIdFilter.MDC_KEY)).ifPresent(id -> problemDetail.setProperty("correlationId", id));
        }
        return response;
    }

    private ErrorCode resolveErrorCode(Exception ex, HttpStatusCode statusCode) {
        if (ex instanceof ApplicationException applicationException) {
            return applicationException.getErrorCode();          // domain error: it knows its own code
        }
        if (ex instanceof MethodArgumentNotValidException) {
            return CommonErrorCode.VALIDATION_FAILED;            // explicit override
        }
        if (ex instanceof DataIntegrityViolationException) {
            return CommonErrorCode.RESOURCE_CONFLICT;
        }
        return CommonErrorCode.forStatus(statusCode);            // protocol error: status is the classification
    }

    private ResponseEntity<Object> problem(Exception ex, ErrorCode errorCode, String detail,
                                           Map<String, Object> properties, WebRequest request) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(errorCode.status(), detail);
        body.setType(errorCode.type());
        body.setTitle(errorCode.title());
        properties.forEach(body::setProperty);
        return handleExceptionInternal(ex, body, new HttpHeaders(), errorCode.status(), request);
    }

    private String pathOf(WebRequest request) {
        return request instanceof ServletWebRequest servletWebRequest
                ? servletWebRequest.getRequest().getRequestURI()
                : "unknown";
    }
}

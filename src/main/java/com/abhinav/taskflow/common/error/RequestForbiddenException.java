package com.abhinav.taskflow.common.error;

/** A 403 raised by application code (the security chain's own 403s go through ProblemAccessDeniedHandler). */
public class RequestForbiddenException extends ApplicationException {

    public RequestForbiddenException(ErrorCode errorCode, String detail) {
        super(errorCode, detail);
    }
}

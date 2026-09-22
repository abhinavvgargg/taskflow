package com.abhinav.taskflow.common.error;

public class ResourceConflictException extends ApplicationException {

    public ResourceConflictException(ErrorCode errorCode, String detail) {
        super(errorCode, detail);
    }
}

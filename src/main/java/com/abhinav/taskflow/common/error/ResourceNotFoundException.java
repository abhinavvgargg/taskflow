package com.abhinav.taskflow.common.error;

public class ResourceNotFoundException extends ApplicationException {

    public ResourceNotFoundException(ErrorCode errorCode, String detail) {
        super(errorCode, detail);
    }
}

package com.abhinav.taskflow.common.error;

public class ResourceInvalidException extends ApplicationException {

    public ResourceInvalidException(ErrorCode errorCode, String detail) {
        super(errorCode, detail);
    }
}

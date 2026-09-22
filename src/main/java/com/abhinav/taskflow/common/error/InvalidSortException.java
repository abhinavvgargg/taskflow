package com.abhinav.taskflow.common.error;

public class InvalidSortException extends ApplicationException {

    public InvalidSortException(ErrorCode errorCode, String detail) {
        super(errorCode, detail);
    }
}

package com.abhinav.taskflow.common.error;

import lombok.Getter;

import java.util.LinkedHashMap;
import java.util.Map;

@Getter
public abstract class ApplicationException extends RuntimeException {

    private final ErrorCode errorCode;
    private final Map<String, Object> properties = new LinkedHashMap<>();

    public ApplicationException(ErrorCode errorCode, String detail) {
        super(detail);
        this.errorCode = errorCode;
    }

    public ApplicationException(ErrorCode errorCode, String detail, Throwable cause) {
        super(detail, cause);
        this.errorCode = errorCode;
    }

    public ApplicationException with(String key, Object value) {
        properties.put(key, value);
        return this;
    }
}

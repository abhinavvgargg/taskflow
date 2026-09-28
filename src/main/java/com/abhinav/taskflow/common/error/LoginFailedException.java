package com.abhinav.taskflow.common.error;

public class LoginFailedException extends ApplicationException{

    public LoginFailedException(ErrorCode errorCode, String detail) {
        super(errorCode, detail);
    }
}

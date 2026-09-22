package com.abhinav.taskflow.common.error;

import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;

public interface ErrorCode {

    String code();
    HttpStatus status();
    String title();

    default URI type() {
        return URI.create("about:blank");
    }
}

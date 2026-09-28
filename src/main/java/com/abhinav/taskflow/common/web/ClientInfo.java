package com.abhinav.taskflow.common.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;

public record ClientInfo(String ipAddress, String userAgent) {

    public static final int MAX_USER_AGENT_LENGTH = 512;

    public static final ClientInfo UNKNOWN = new ClientInfo(null, null);

    public static ClientInfo from(HttpServletRequest request) {
        return new ClientInfo(request.getRemoteAddr(), truncate(request.getHeader(HttpHeaders.USER_AGENT)));
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= MAX_USER_AGENT_LENGTH ? value : value.substring(0, MAX_USER_AGENT_LENGTH);
    }

    @Override
    public String toString() {
        return "ClientInfo[<redacted>]";
    }
}

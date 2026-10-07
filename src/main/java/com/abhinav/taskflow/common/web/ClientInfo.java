package com.abhinav.taskflow.common.web;

import jakarta.annotation.Nullable;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.regex.Pattern;

public record ClientInfo(String ipAddress, String userAgent) {

    public static final int MAX_USER_AGENT_LENGTH = 512;
    private static final String OCTET = "(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)";
    private static final Pattern IPV4_LITERAL = Pattern.compile(OCTET + "(\\." + OCTET + "){3}");

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

    public @Nullable InetAddress inetAddress() {
        if (ipAddress == null || ipAddress.isBlank()) {
            return null;
        }
        String literal = ipAddress.strip();
        boolean ipv6 = literal.indexOf(':') >= 0;
        if (!ipv6 && !IPV4_LITERAL.matcher(literal).matches()) {
            return null;
        }
        try {
            InetAddress parsed = InetAddress.getByName(literal);
            return InetAddress.getByAddress(parsed.getAddress());
        } catch (UnknownHostException e) {
            return null;
        }
    }

    @Override
    public String toString() {
        return "ClientInfo[<redacted>]";
    }
}

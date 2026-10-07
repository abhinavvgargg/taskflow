package com.abhinav.taskflow.user;

import com.abhinav.taskflow.user.session.SessionProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** The one place that knows the refresh cookie's attributes. Its output contains the raw token: never log it. */
@Component
@RequiredArgsConstructor
public class RefreshCookie {

    private final SessionProperties sessionProperties;

    public ResponseCookie set(String rawToken, Duration maxAge) {
        return base(rawToken).maxAge(maxAge).build();
    }

    // §2.3 / §2.4: same name, path and attributes, or the browser treats it as a different cookie and keeps the old one
    public ResponseCookie clear() {
        return base("").maxAge(Duration.ZERO).build();
    }

    private ResponseCookie.ResponseCookieBuilder base(String value) {
        SessionProperties.CookieSettings settings = sessionProperties.cookie();
        return ResponseCookie.from(settings.name(), value)
                .httpOnly(true)
                .secure(settings.secure())
                .sameSite(settings.sameSite().attributeValue())
                .path(settings.path());
    }
}
package com.abhinav.taskflow.user;

import com.abhinav.taskflow.common.error.ProblemDetails;
import com.abhinav.taskflow.common.web.ClientInfo;
import com.abhinav.taskflow.user.session.SessionErrorCode;
import com.abhinav.taskflow.user.token.ResendVerificationRequest;
import com.abhinav.taskflow.user.token.VerifyTokenRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserRegistrationService userRegistrationService;
    private final RegistrationWorkflow registrationWorkflow;
    private final LoginService loginService;
    private final PasswordWorkflow passwordWorkflow;
    private final RefreshCookie refreshCookie;
    private final SessionWorkflow sessionWorkflow;

    private static final String INVALID_REFRESH_TOKEN_DETAIL = "The refresh token is invalid or has expired. Sign in again.";

    @PostMapping("/register")
    public ResponseEntity<UserAccountResponse> registerUserAccount (@Valid @RequestBody RegisterRequest registerRequest) {
        UserAccountResponse userAccountResponse = registrationWorkflow.registerAndSendEmail(registerRequest);
        return ResponseEntity.status(HttpStatus.CREATED).body(userAccountResponse);
    }

    @PostMapping("/verify-email")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void verifyEmail (@Valid @RequestBody VerifyTokenRequest verifyTokenRequest, HttpServletRequest request) {
        userRegistrationService.verify(verifyTokenRequest.token(), ClientInfo.from(request));
    }

    @PostMapping("/verify-email/resend")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void resendEmail (@Valid @RequestBody ResendVerificationRequest resendVerificationRequest) {
        registrationWorkflow.resendVerificationEmail(resendVerificationRequest.email());
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login (@Valid @RequestBody LoginRequest loginRequest, HttpServletRequest request) {
        LoginResult result = loginService.login(loginRequest, ClientInfo.from(request));
        ResponseCookie cookie = refreshCookie.set(result.session().refreshToken(), result.session().refreshTokenMaxAge());

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(LoginResponse.from(result));
    }

    /**
     * Rotates the refresh cookie and returns a new access token. No body: the credential is the cookie.
     * A rejection is answered here rather than thrown, because it needs a Set-Cookie header (clearing the cookie)
     * that an exception handler wouldn't add.
     */
    @PostMapping("/refresh")
    public ResponseEntity<?> refresh (HttpServletRequest request) {
        RefreshOutcome outcome = sessionWorkflow.refresh(request.getHeader(HttpHeaders.ORIGIN), refreshCookie.read(request), ClientInfo.from(request));

        return switch (outcome) {
            case RefreshOutcome.Granted granted -> ResponseEntity.ok()
                    .header(HttpHeaders.SET_COOKIE, refreshCookie.set(granted.refreshToken(), granted.refreshTokenMaxAge()).toString())
                    .body(RefreshResponse.from(granted.accessToken()));
            case RefreshOutcome.Denied denied -> {
                ResponseEntity.BodyBuilder response = ResponseEntity.status(SessionErrorCode.INVALID_REFRESH_TOKEN.status());
                if (denied.clearCookie()) {
                    response.header(HttpHeaders.SET_COOKIE, refreshCookie.clear().toString());
                }
                yield response.body(ProblemDetails.create(SessionErrorCode.INVALID_REFRESH_TOKEN, SessionErrorCode.INVALID_REFRESH_TOKEN.status(),
                        INVALID_REFRESH_TOKEN_DETAIL, request.getRequestURI()));
            }
        };
    }

    /**
     * Logs out THIS device: revokes the session the refresh cookie belongs to. Always 204 and always clears the cookie,
     * also for a missing, stale or unknown cookie (idempotent: a double-click or a dead session is still "logged out").
     * Only a disallowed Origin fails (403), before anything is read or cleared.
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout (HttpServletRequest request) {
        sessionWorkflow.logout(request.getHeader(HttpHeaders.ORIGIN), refreshCookie.read(request));
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, refreshCookie.clear().toString())
                .build();
    }

    @PostMapping("/password-reset/request")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void requestPasswordReset (@Valid @RequestBody PasswordResetRequest passwordResetRequest) {
        passwordWorkflow.requestReset(passwordResetRequest.email());
    }

    @PostMapping("/password-reset/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void confirmPasswordReset (@Valid @RequestBody PasswordResetConfirmRequest passwordResetConfirmRequest, HttpServletRequest request) {
        passwordWorkflow.confirmReset(passwordResetConfirmRequest.token(), passwordResetConfirmRequest.newPassword(), ClientInfo.from(request));
    }
}

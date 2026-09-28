package com.abhinav.taskflow.user;

import com.abhinav.taskflow.common.web.ClientInfo;
import com.abhinav.taskflow.user.token.ResendVerificationRequest;
import com.abhinav.taskflow.user.token.VerifyTokenRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserRegistrationService userRegistrationService;
    private final RegistrationWorkflow registrationWorkflow;
    private final LoginService loginService;

    @PostMapping("/register")
    public ResponseEntity<UserAccountResponse> registerUserAccount (@Valid @RequestBody RegisterRequest registerRequest)
    {
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
    public ResponseEntity<UserAccountResponse> login (@Valid @RequestBody LoginRequest loginRequest, HttpServletRequest request) {
        UserAccountResponse userAccountResponse = loginService.login(loginRequest, ClientInfo.from(request));
        return ResponseEntity.ok(userAccountResponse);
    }
}

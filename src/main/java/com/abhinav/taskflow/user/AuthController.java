package com.abhinav.taskflow.user;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserRegistrationService userRegistrationService;

    @PostMapping("/register")
    public ResponseEntity<UserAccountResponse> registerUserAccount (@Valid @RequestBody RegisterRequest registerRequest)
    {
        UserAccountResponse  userAccountResponse = userRegistrationService.registerUser(registerRequest);
        return ResponseEntity.status(HttpStatus.CREATED).body(userAccountResponse);
    }
}

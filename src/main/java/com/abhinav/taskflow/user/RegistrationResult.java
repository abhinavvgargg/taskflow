package com.abhinav.taskflow.user;

import com.abhinav.taskflow.user.token.IssuedToken;

public record RegistrationResult(UserAccountResponse userAccountResponse, IssuedToken issuedToken) {
}

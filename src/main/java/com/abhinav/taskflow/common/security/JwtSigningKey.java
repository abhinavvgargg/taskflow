package com.abhinav.taskflow.common.security;

import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;

public record JwtSigningKey(String keyId, RSAPublicKey publicKey, RSAPrivateKey privateKey) {

    @Override
    public String toString() {
        return "JwtSigningKey[keyId=%s]".formatted(keyId);
    }
}

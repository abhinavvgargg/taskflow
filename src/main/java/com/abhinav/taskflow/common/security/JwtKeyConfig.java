package com.abhinav.taskflow.common.security;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Arrays;
import java.util.UUID;

@Slf4j
@Configuration
public class JwtKeyConfig {

    private static final int MIN_KEY_SIZE = 2048;
    private static final Profiles EPHEMERAL_KEY_PROFILES = Profiles.of("dev", "test");

    @Bean
    JwtSigningKey jwtSigningKey(JwtProperties properties, Environment environment) {
        if (properties.hasConfiguredKey()) {
            return loadConfiguredKey(properties);
        }

        String activeProfiles = Arrays.toString(environment.getActiveProfiles());

        if (environment.acceptsProfiles(EPHEMERAL_KEY_PROFILES)) {
            JwtSigningKey key = generateEphemeralKey();
            log.warn("No JWT signing key configured: generated an ephemeral RSA key (kid={}) for profiles {}. Access tokens stop working after a restart.",
                    key.keyId(), activeProfiles);
            return key;
        }

        throw new IllegalStateException("No JWT signing key configured for profiles " + activeProfiles
                + ". Set taskflow.security.jwt.key-id, taskflow.security.jwt.private-key and taskflow.security.jwt.public-key"
                + " (env vars TASKFLOW_SECURITY_JWT_KEY_ID, TASKFLOW_SECURITY_JWT_PRIVATE_KEY, TASKFLOW_SECURITY_JWT_PUBLIC_KEY).");
    }

    @Bean
    JwtEncoder jwtEncoder(JwtSigningKey signingKey) {
        RSAKey jwk = new RSAKey.Builder(signingKey.publicKey())
                .privateKey(signingKey.privateKey())
                .keyID(signingKey.keyId())
                .build();
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(jwk)));
    }

    private static JwtSigningKey loadConfiguredKey(JwtProperties properties) {
        RSAPrivateKey privateKey = parsePrivateKey(properties.privateKey());
        RSAPublicKey publicKey = parsePublicKey(properties.publicKey());

        // An RSA pair shares its modulus: public = (n, e), private = (n, d)
        if (!publicKey.getModulus().equals(privateKey.getModulus())) {
            throw new IllegalStateException("taskflow.security.jwt.public-key does not belong to taskflow.security.jwt.private-key");
        }

        if (publicKey.getModulus().bitLength() < MIN_KEY_SIZE) {
            throw new IllegalStateException("The JWT signing key must be at least " + MIN_KEY_SIZE + " bits");
        }

        return new JwtSigningKey(properties.keyId(), publicKey, privateKey);
    }

    private static RSAPrivateKey parsePrivateKey(String pem) {
        try {
            return RsaKeyConverters.pkcs8().convert(asStream(pem));
        } catch (RuntimeException e) {
            throw new IllegalStateException("taskflow.security.jwt.private-key is not a PEM-encoded PKCS#8 RSA key (-----BEGIN PRIVATE KEY-----)", e);
        }
    }

    private static RSAPublicKey parsePublicKey(String pem) {
        try {
            return RsaKeyConverters.x509().convert(asStream(pem));
        } catch (RuntimeException e) {
            throw new IllegalStateException("taskflow.security.jwt.public-key is not a PEM-encoded X.509 RSA key (-----BEGIN PUBLIC KEY-----)", e);
        }
    }

    private static InputStream asStream(String pem) {
        return new ByteArrayInputStream(pem.getBytes(StandardCharsets.UTF_8));
    }

    private static JwtSigningKey generateEphemeralKey() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(MIN_KEY_SIZE);
            KeyPair pair = generator.generateKeyPair();
            return new JwtSigningKey("ephemeral-" + UUID.randomUUID(),
                    (RSAPublicKey) pair.getPublic(), (RSAPrivateKey) pair.getPrivate());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("RSA is not available in this JVM", e);
        }
    }
}

package com.example.gateway.security.token;

import java.time.Duration;

/** An access token an identity provider issued for a backend, with how long it lasts. */
public record IssuedToken(String accessToken, Duration expiresIn) {

    @Override
    public String toString() {
        return "IssuedToken[expiresIn=" + expiresIn + "]";
    }
}

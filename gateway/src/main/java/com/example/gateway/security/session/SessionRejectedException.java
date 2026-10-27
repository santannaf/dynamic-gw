package com.example.gateway.security.session;

/** The identity provider refused the session (logged out, disabled, expired). */
public class SessionRejectedException extends RuntimeException {

    public SessionRejectedException(String message) {
        super(message);
    }
}

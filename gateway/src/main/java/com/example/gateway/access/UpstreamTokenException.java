package com.example.gateway.access;

/**
 * The identity channel could not get the token a route picks for its backend.
 * The message names what failed and never carries a token.
 */
public class UpstreamTokenException extends RuntimeException {

    public enum Reason {
        /** The identity provider did not answer: 503, try again later. */
        UNAVAILABLE,
        /** The identity provider refused: 502, the route's setup needs a fix. */
        REFUSED,
        /** The caller has to log in through the route's identity provider again. */
        LOGIN_REQUIRED
    }

    private final Reason reason;

    public UpstreamTokenException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public UpstreamTokenException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}

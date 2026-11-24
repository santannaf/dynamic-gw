package com.example.gateway.access;

/** Why an exchange that carried credentials ended up without identity. */
public enum AccessProblem {
    /** A bearer token was present but failed validation. */
    INVALID_TOKEN,
    /** The session cookie pointed to a session the provider no longer accepts. */
    SESSION_EXPIRED,
    /** The identity provider could not be reached to revalidate the session. */
    PROVIDER_UNAVAILABLE
}

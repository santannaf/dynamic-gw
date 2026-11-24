package com.example.gateway.security.ldap;

import java.util.List;

/** A directory user as the gateway needs it; never carries the password. */
public record LdapUser(String dn, String subject, String username, String email, List<String> groups) {

    public LdapUser {
        groups = groups == null ? List.of() : List.copyOf(groups);
    }
}

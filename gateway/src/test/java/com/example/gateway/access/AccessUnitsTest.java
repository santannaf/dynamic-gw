package com.example.gateway.access;

import com.example.shared.routes.RouteAccess;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AccessUnitsTest {

    @Test
    void policyDefaultsAndFailsClosed() {
        assertThat(AccessPolicy.of("r", null, AccessPolicy.Mode.AUTHENTICATED).mode())
                .isEqualTo(AccessPolicy.Mode.AUTHENTICATED);
        assertThat(AccessPolicy.of("r", null, AccessPolicy.Mode.PUBLIC).mode())
                .isEqualTo(AccessPolicy.Mode.PUBLIC);
        assertThat(AccessPolicy.of("r", new RouteAccess(null, List.of("group:a"), null), AccessPolicy.Mode.PUBLIC).mode())
                .as("anyOf without mode means authenticated")
                .isEqualTo(AccessPolicy.Mode.AUTHENTICATED);
        assertThat(AccessPolicy.of("r", new RouteAccess("everyone", null, null), AccessPolicy.Mode.PUBLIC).mode())
                .as("unknown mode fails closed")
                .isEqualTo(AccessPolicy.Mode.AUTHENTICATED);
        assertThat(AccessPolicy.of("r", new RouteAccess("public", List.of("group:a"), null), AccessPolicy.Mode.PUBLIC).mode())
                .isEqualTo(AccessPolicy.Mode.AUTHENTICATED);
        assertThat(AccessPolicy.of("r", new RouteAccess("public", null, true), AccessPolicy.Mode.AUTHENTICATED))
                .isEqualTo(new AccessPolicy(AccessPolicy.Mode.PUBLIC, List.of(), true));
    }

    @Test
    void browserNavigationNeedsExplicitHtmlAndNoCredentials() {
        assertThat(BrowserNavigation.matches(MockServerHttpRequest.get("/x")
                .header(HttpHeaders.ACCEPT, "text/html,application/xhtml+xml,*/*;q=0.8").build())).isTrue();
        assertThat(BrowserNavigation.matches(MockServerHttpRequest.get("/x").header(HttpHeaders.ACCEPT, "*/*").build())).isFalse();
        assertThat(BrowserNavigation.matches(MockServerHttpRequest.post("/x").header(HttpHeaders.ACCEPT, "text/html").build())).isFalse();
        assertThat(BrowserNavigation.matches(MockServerHttpRequest.get("/x").header(HttpHeaders.ACCEPT, "text/html")
                .header("X-Requested-With", "XMLHttpRequest").build())).isFalse();
        assertThat(BrowserNavigation.matches(MockServerHttpRequest.get("/x").header(HttpHeaders.ACCEPT, "text/html")
                .header(HttpHeaders.AUTHORIZATION, "Bearer x").build())).isFalse();
        assertThat(BrowserNavigation.matches(MockServerHttpRequest.get("/x").header(HttpHeaders.ACCEPT, "not a media type").build())).isFalse();
    }

    @Test
    void identityHeadersStripSpoofedValuesAndInjectVerifiedOnes() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("x-auth-request-user", "mallory");
        headers.add("X-Auth-Request-Groups", "admins");
        headers.add("X-Other", "kept");
        GatewayIdentity identity = new GatewayIdentity("sub-1", "alice", "alice@tools.local", "sid", "tools-cli",
                Set.of("group:b", "group:a", "role:r"), GatewayIdentity.AuthMethod.BEARER, "token", null);

        IdentityHeaders.strip(headers, "X-Auth-Request-");
        assertThat(headers.headerNames()).containsExactly("X-Other");

        IdentityHeaders.inject(headers, identity, "X-Auth-Request-", 4096);
        assertThat(headers.getFirst("X-Auth-Request-User")).isEqualTo("alice");
        assertThat(headers.getFirst("X-Auth-Request-Groups")).isEqualTo("a,b");
        assertThat(headers.getFirst("X-Auth-Request-Roles")).isEqualTo("r");
        assertThat(headers.getFirst("X-Auth-Request-Client-Id")).isEqualTo("tools-cli");
    }

    @Test
    void longGroupListIsTruncatedAtASeparatorAndFlagged() {
        HttpHeaders headers = new HttpHeaders();
        GatewayIdentity identity = new GatewayIdentity("s", "u", null, null, null,
                Set.of("group:aaaa", "group:bbbb", "group:cccc"), GatewayIdentity.AuthMethod.SESSION, null, null);

        IdentityHeaders.inject(headers, identity, "X-Auth-Request-", 10);

        assertThat(headers.getFirst("X-Auth-Request-Groups")).isEqualTo("aaaa,bbbb");
        assertThat(headers.getFirst("X-Auth-Request-Groups-Truncated")).isEqualTo("true");
    }

    @Test
    void gatewayCookiesAreRemovedAndOthersKept() {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, "theme=dark; GW_SESSION=abc; REDIRECT_URI=x");
        headers.add(HttpHeaders.COOKIE, "GW_SESSION=def");

        IdentityHeaders.stripCookies(headers, Set.of("GW_SESSION", "REDIRECT_URI"));

        assertThat(headers.get(HttpHeaders.COOKIE)).containsExactly("theme=dark");
    }

    @Test
    void controlCharactersNeverReachHeaders() {
        HttpHeaders headers = new HttpHeaders();
        GatewayIdentity identity = new GatewayIdentity("s", "ali\r\nce", null, null, null, Set.of(),
                GatewayIdentity.AuthMethod.SESSION, null, null);

        IdentityHeaders.inject(headers, identity, "X-Auth-Request-", 4096);

        assertThat(headers.getFirst("X-Auth-Request-User")).isEqualTo("ali  ce");
    }

    @Test
    void identityToStringNeverShowsTheToken() {
        GatewayIdentity identity = new GatewayIdentity("s", "u", null, null, null, Set.of(),
                GatewayIdentity.AuthMethod.BEARER, "eyJsecret", null);

        assertThat(identity.toString()).doesNotContain("eyJsecret");
    }
}

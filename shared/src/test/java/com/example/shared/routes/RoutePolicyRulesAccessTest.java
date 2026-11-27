package com.example.shared.routes;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class RoutePolicyRulesAccessTest {

    private static final RouteObo PEDIDOS = new RouteObo(List.of("api://backend-b/pedidos.read"));

    @Test
    void accessOfEveryScenarioIsValid() {
        passes(access("public", null, null, null, null, null, null));
        passes(access(null, List.of("group:abc-users"), null, null, null, null, null));
        passes(access(null, List.of("group:abc-users"), null, "ldap", null, null, null));
        passes(access(null, List.of("role:abc-user"), true, null, null, "abc-api", null));
        passes(access(null, List.of("role:business-monitor-user"), true, "keycloak", "microsoft",
                "business-monitor", null));
        passes(access(null, List.of("role:pedidos-leitura"), true, null, "microsoft", null, PEDIDOS));
    }

    @Test
    void unknownModeFails() {
        fails(access("everyone", null, null, null, null, null, null), "access.mode must be public or authenticated");
    }

    @Test
    void authorityWithoutTypeFails() {
        fails(access(null, List.of("admins"), null, null, null, null, null), "'admins'");
        fails(access(null, List.of("admins"), null, null, null, null, null), "<type>:<name>");
    }

    @Test
    void nullAuthorityIsReportedInsteadOfBreakingTheRoute() {
        fails(access(null, Arrays.asList("group:a", null), null, null, null, null, null), "invalid authority 'null'");
    }

    @Test
    void publicWithAnyOfFails() {
        fails(access("public", List.of("group:a"), null, null, null, null, null), "mode=public cannot be combined");
    }

    @Test
    void publicAcceptsNoOtherField() {
        String fragment = "access.mode=public accepts no other access field";
        fails(access("public", null, true, null, null, null, null), fragment);
        fails(access("public", null, null, "keycloak", null, null, null), fragment);
        fails(access("public", null, null, null, "microsoft", null, null), fragment);
        fails(access("public", null, true, null, null, "abc-api", null), fragment);
        fails(access("public", null, true, null, "microsoft", null, PEDIDOS), fragment);
        passes(access("public", null, false, null, null, null, null));
    }

    @Test
    void unknownChannelFails() {
        fails(access(null, null, null, "saml", null, null, null), "access.channel must be keycloak or ldap");
    }

    @Test
    void ldapChannelHasNoToken() {
        String fragment = "access.channel=ldap has no token";
        fails(access(null, null, true, "ldap", null, null, null), fragment);
        fails(access(null, null, null, "ldap", "microsoft", null, null), fragment);
        fails(access(null, null, true, "ldap", null, "abc-api", null), fragment);
        fails(access(null, null, true, "ldap", "microsoft", null, PEDIDOS), fragment);
        passes(access(null, null, false, "ldap", null, null, null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "micro soft", "a/b", "ação"})
    void identityProviderMustBeAnAlias(String alias) {
        fails(access(null, null, null, null, alias, null, null), "access.identityProvider must be");
    }

    @Test
    void identityProviderAliasHasAtMost64Characters() {
        passes(access(null, null, null, null, "a".repeat(64), null, null));
        fails(access(null, null, null, null, "a".repeat(65), null, null), "access.identityProvider must be");
    }

    @Test
    void audienceMustNotBeBlankOrTooLong() {
        fails(access(null, null, true, null, null, " ", null), "access.audience must be a Keycloak client id");
        fails(access(null, null, true, null, null, "a".repeat(256), null), "access.audience must be a Keycloak client id");
    }

    @Test
    void audienceAndOboNeedForwardToken() {
        String fragment = "they need access.forwardToken: true";
        fails(access(null, null, null, null, null, "abc-api", null), fragment);
        fails(access(null, null, false, null, null, "abc-api", null), fragment);
        fails(access(null, null, null, null, "microsoft", null, PEDIDOS), fragment);
    }

    @Test
    void audienceAndOboCannotBeCombined() {
        fails(access(null, null, true, null, "microsoft", "abc-api", PEDIDOS),
                "access.audience and access.obo cannot be combined: each picks");
    }

    @Test
    void oboNeedsTheIdentityProvider() {
        fails(access(null, null, true, null, null, null, PEDIDOS), "access.obo needs access.identityProvider");
    }

    @Test
    void oboScopesAreOneToSixteenTokens() {
        String count = "access.obo.scopes must list 1 to 16 scopes";
        fails(obo(null), count);
        fails(obo(List.of()), count);
        fails(obo(IntStream.range(0, 17).mapToObj(i -> "scope-" + i).toList()), count);
        passes(obo(IntStream.range(0, 16).mapToObj(i -> "scope-" + i).toList()));
        String invalid = "access.obo.scopes contains invalid scope";
        fails(obo(List.of(" ")), invalid);
        fails(obo(List.of("a b")), invalid);
        fails(obo(Collections.singletonList(null)), invalid);
        fails(obo(List.of("s".repeat(257))), invalid);
    }

    @Test
    void audienceAndOboCannotGoToAnAggregate() {
        RouteAggregate aggregate = new RouteAggregate(List.of(new RouteBackend("http://a", "a", null),
                new RouteBackend("http://b", "b", null)), "2s");
        String fragment = "cannot be combined with aggregate";
        fails(aggregated(access(null, null, true, null, null, "abc-api", null), aggregate), fragment);
        fails(aggregated(access(null, null, true, null, "microsoft", null, PEDIDOS), aggregate), fragment);
        assertThat(RoutePolicyRules.validate(aggregated(access(null, null, true, null, null, null, null), aggregate),
                RoutePolicyRules.DEFAULT_IDENTITY_HEADER_PREFIX)).isEmpty();
    }

    @Test
    void oboScopesKeepTheirOrderAndCannotChange() {
        List<String> scopes = new ArrayList<>(List.of("b", "a"));
        RouteObo obo = new RouteObo(scopes);
        scopes.add("c");

        assertThat(obo.scopes()).containsExactly("b", "a");
        assertThat(obo.scopes()).isUnmodifiable();
    }

    private static RouteAccess obo(List<String> scopes) {
        return access(null, null, true, null, "microsoft", null, new RouteObo(scopes));
    }

    private static RouteAccess access(String mode, List<String> anyOf, Boolean forwardToken, String channel,
                                      String identityProvider, String audience, RouteObo obo) {
        return new RouteAccess(mode, anyOf, forwardToken, channel, identityProvider, audience, obo);
    }

    private static void fails(RouteAccess access, String fragment) {
        fails(proxied(access), fragment);
    }

    private static void fails(RouteConfigEntry route, String fragment) {
        assertThat(RoutePolicyRules.validate(route, RoutePolicyRules.DEFAULT_IDENTITY_HEADER_PREFIX))
                .hasValueSatisfying(problem -> assertThat(problem).contains(fragment));
    }

    private static void passes(RouteAccess access) {
        assertThat(RoutePolicyRules.validate(proxied(access), RoutePolicyRules.DEFAULT_IDENTITY_HEADER_PREFIX))
                .isEmpty();
    }

    private static RouteConfigEntry proxied(RouteAccess access) {
        return new RouteConfigEntry("r", "/r/**", "http://r", 1, null, true, null, null, access,
                null, null, null, null, null, null);
    }

    private static RouteConfigEntry aggregated(RouteAccess access, RouteAggregate aggregate) {
        return new RouteConfigEntry("r", "/r/**", null, 1, null, true, null, null, access,
                null, null, null, null, aggregate, null);
    }
}

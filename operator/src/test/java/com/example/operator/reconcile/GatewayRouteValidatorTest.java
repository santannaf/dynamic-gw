package com.example.operator.reconcile;

import com.example.operator.crd.GatewayRoute;
import com.example.operator.crd.GatewayRouteAccess;
import com.example.operator.crd.GatewayRouteSpec;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayRouteValidatorTest {

    private final GatewayRouteValidator validator = new GatewayRouteValidator();

    @Test
    void validFullSpecPasses() {
        GatewayRoute r = route("ok", "/api/**", "http://x", 1, List.of("GET", "POST"), true);
        assertThat(validator.validate(r)).isEmpty();
    }

    @Test
    void validMinimalSpecPasses() {
        GatewayRoute r = route("ok", "/api/**", "http://x", null, null, null);
        assertThat(validator.validate(r)).isEmpty();
    }

    @Test
    void emptyPathFails() {
        GatewayRoute r = route("bad", "", "http://x", 1, null, true);
        assertThat(validator.validate(r))
                .map(s -> s.contains("path"))
                .contains(true);
    }

    @Test
    void emptyTargetUriFails() {
        GatewayRoute r = route("bad", "/api/**", "", 1, null, true);
        assertThat(validator.validate(r))
                .map(s -> s.contains("targetUri"))
                .contains(true);
    }

    @Test
    void negativeStripPrefixFails() {
        GatewayRoute r = route("bad", "/api/**", "http://x", -1, null, true);
        assertThat(validator.validate(r))
                .map(s -> s.contains("stripPrefix"))
                .contains(true);
    }

    @Test
    void lowercaseMethodFails() {
        GatewayRoute r = route("bad", "/api/**", "http://x", 1, List.of("get"), true);
        Optional<String> err = validator.validate(r);
        assertThat(err).isPresent();
        assertThat(err.get()).contains("methods");
    }

    @Test
    void missingMetadataNameFails() {
        GatewayRoute r = new GatewayRoute();
        r.setSpec(new GatewayRouteSpec());
        assertThat(validator.validate(r))
                .map(s -> s.contains("metadata.name"))
                .contains(true);
    }

    @Test
    void missingSpecFails() {
        GatewayRoute r = new GatewayRoute();
        r.setMetadata(new ObjectMetaBuilder().withName("noSpec").build());
        assertThat(validator.validate(r))
                .map(s -> s.contains("spec"))
                .contains(true);
    }

    @Test
    void reservedGatewayPathFails() {
        GatewayRoute r = route("bad", "/_gateway/**", "http://x", 1, null, true);
        assertThat(validator.validate(r))
                .map(s -> s.contains("reserved prefix"))
                .contains(true);
    }

    @Test
    void validAccessPasses() {
        GatewayRoute r = withAccess(route("ok", "/api/**", "http://x", 1, null, true),
                "authenticated", List.of("group:tools-httpbin", "role:gateway-admin"), true);
        assertThat(validator.validate(r)).isEmpty();
    }

    @Test
    void accessWithOnlyAnyOfPasses() {
        GatewayRoute r = withAccess(route("ok", "/api/**", "http://x", 1, null, true),
                null, List.of("group:tools-httpbin"), null);
        assertThat(validator.validate(r)).isEmpty();
    }

    @Test
    void unknownAccessModeFails() {
        GatewayRoute r = withAccess(route("bad", "/api/**", "http://x", 1, null, true),
                "everyone", null, null);
        assertThat(validator.validate(r))
                .map(s -> s.contains("spec.access.mode"))
                .contains(true);
    }

    @Test
    void authorityWithoutPrefixFails() {
        GatewayRoute r = withAccess(route("bad", "/api/**", "http://x", 1, null, true),
                null, List.of("admins"), null);
        assertThat(validator.validate(r))
                .map(s -> s.contains("'admins'"))
                .contains(true);
    }

    @Test
    void authorityOfAnyTypePasses() {
        GatewayRoute r = withAccess(route("ok", "/api/**", "http://x", 1, null, true),
                null, List.of("scope:pagamentos.read", "permission:conta-ler", "saca:extrato", "tipo-2:x"), null);
        assertThat(validator.validate(r)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {":semtipo", "scope:", "Scope:x", "2fa:x", "tipo_a:x"})
    void malformedAuthorityFails(String authority) {
        GatewayRoute r = withAccess(route("bad", "/api/**", "http://x", 1, null, true),
                null, List.of(authority), null);
        assertThat(validator.validate(r))
                .map(s -> s.contains("'" + authority + "'") && s.contains("<type>:<name>"))
                .contains(true);
    }

    @Test
    void publicWithAnyOfFails() {
        GatewayRoute r = withAccess(route("bad", "/api/**", "http://x", 1, null, true),
                "public", List.of("group:tools-httpbin"), null);
        assertThat(validator.validate(r))
                .map(s -> s.contains("mode=public"))
                .contains(true);
    }

    static GatewayRoute withAccess(GatewayRoute route, String mode, List<String> anyOf, Boolean forwardToken) {
        GatewayRouteAccess access = new GatewayRouteAccess();
        access.setMode(mode);
        access.setAnyOf(anyOf);
        access.setForwardToken(forwardToken);
        route.getSpec().setAccess(access);
        return route;
    }

    static GatewayRoute route(String name, String path, String targetUri,
                              Integer stripPrefix, List<String> methods, Boolean enabled) {
        GatewayRoute r = new GatewayRoute();
        r.setMetadata(new ObjectMetaBuilder().withName(name).withNamespace("platform").build());
        GatewayRouteSpec spec = new GatewayRouteSpec();
        spec.setPath(path);
        spec.setTargetUri(targetUri);
        spec.setStripPrefix(stripPrefix);
        spec.setMethods(methods);
        spec.setEnabled(enabled);
        r.setSpec(spec);
        return r;
    }
}

package com.example.operator.reconcile;

import com.example.operator.crd.GatewayRoute;
import com.example.operator.crd.GatewayRouteSpec;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import org.junit.jupiter.api.Test;

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

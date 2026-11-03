package com.example.shared.routes;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RoutePolicyRulesTest {

    private static final String PREFIX = RoutePolicyRules.DEFAULT_IDENTITY_HEADER_PREFIX;

    @Test
    void routeWithEveryPolicyIsValid() {
        passes(proxied(new RouteRateLimit(100, "1s", 20, "ip"),
                new RouteThrottle(50, 100, "200ms"),
                List.of(new RouteFilter("AddRequestHeader", Map.of("name", "X-Source", "value", "gateway")),
                        new RouteFilter("RewritePath", Map.of("regexp", "/a/(?<rest>.*)", "replacement", "/${rest}")),
                        new RouteFilter("RequestSize", Map.of("maxSize", "5MB"))),
                new RouteTransform(List.of("id"), null, Map.of("id", "codigo"), "data", "pedido")));
    }

    @Test
    void routeNeedsTargetUriOrAggregateButNotBoth() {
        fails(entry(null, null, List.of("GET")), "targetUri is required");
        fails(entry("http://a", aggregate("http://b", "http://c"), null), "either targetUri or aggregate");
        passes(entry(null, aggregate("http://b/{id}", "http://c"), null));
    }

    @Test
    void aggregateOnlyAnswersGetAndNeedsTwoDistinctBackends() {
        fails(entry(null, aggregate("http://b", "http://c"), List.of("POST")), "only answers GET");
        fails(entry(null, new RouteAggregate(List.of(new RouteBackend("http://b", null, null)), null), null),
                "between 2 and 10");
        fails(entry(null, new RouteAggregate(List.of(
                new RouteBackend("http://b", "x", null), new RouteBackend("http://c", "x", null)), null), null),
                "unique");
        fails(entry(null, new RouteAggregate(List.of(
                new RouteBackend("ftp://b", null, null), new RouteBackend("http://c", null, null)), null), null),
                "absolute http(s) URL");
        fails(entry(null, new RouteAggregate(List.of(
                new RouteBackend("http://b", null, null), new RouteBackend("http://c", null, null)), "0s"), null),
                "aggregate.timeout");
    }

    @Test
    void rateLimitNeedsPositiveRequestsAPeriodAndAKnownKey() {
        fails(proxied(new RouteRateLimit(0, "1s", null, null), null, null, null), "rateLimit.requests");
        fails(proxied(new RouteRateLimit(10, "1 second", null, null), null, null, null), "rateLimit.period");
        fails(proxied(new RouteRateLimit(10, "1s", 0, null), null, null, null), "rateLimit.burst");
        fails(proxied(new RouteRateLimit(10, "1s", null, "cookie"), null, null, null), "rateLimit.key");
        passes(proxied(new RouteRateLimit(10, "1s", null, "header:X-Api-Key"), null, null, null));
    }

    @Test
    void throttleNeedsAConcurrencyAndAValidWait() {
        fails(proxied(null, new RouteThrottle(0, null, null), null, null), "throttle.maxConcurrent");
        fails(proxied(null, new RouteThrottle(5, -1, null), null, null), "throttle.maxQueue");
        fails(proxied(null, new RouteThrottle(5, 10, "soon"), null, null), "throttle.maxWait");
        passes(proxied(null, new RouteThrottle(5, null, null), null, null));
    }

    @Test
    void filtersComeFromTheCatalogWithTheirArguments() {
        fails(filters(new RouteFilter("Retry", Map.of("retries", "3"))), "filters[0].name");
        fails(filters(new RouteFilter("AddRequestHeader", Map.of("name", "X-A"))), "needs the argument 'value'");
        fails(filters(new RouteFilter("RemoveRequestHeader", Map.of("name", "X-A", "value", "b"))),
                "does not take the argument 'value'");
        fails(filters(new RouteFilter("RewritePath", Map.of("regexp", "(", "replacement", "/"))), "invalid regexp");
        fails(filters(new RouteFilter("PrefixPath", Map.of("prefix", "api"))), "must start with /");
        fails(filters(new RouteFilter("RequestSize", Map.of("maxSize", "lots"))), "maxSize");
        fails(filters(new RouteFilter("AddResponseHeader", Map.of("name", "bad header", "value", "x"))),
                "invalid header name");
    }

    @ParameterizedTest
    @ValueSource(strings = {"AddRequestHeader", "SetRequestHeader", "RemoveRequestHeader"})
    void noFilterMayTouchTheIdentityHeaders(String filter) {
        Map<String, String> args = filter.startsWith("Remove")
                ? Map.of("name", "x-auth-request-user")
                : Map.of("name", "X-Auth-Request-Groups", "value", "admins");

        fails(filters(new RouteFilter(filter, args)), "identity header");
    }

    @Test
    void identityHeaderCheckFollowsTheGatewayPrefix() {
        RouteConfigEntry route = filters(new RouteFilter("AddRequestHeader", Map.of("name", "X-Id-User", "value", "x")));

        assertThat(RoutePolicyRules.validate(route, "X-Id-"))
                .hasValueSatisfying(problem -> assertThat(problem).contains("identity header"));
        passes(route);
    }

    @Test
    void responseHeadersWithTheIdentityPrefixAreFine() {
        passes(filters(new RouteFilter("AddResponseHeader", Map.of("name", "X-Auth-Request-Note", "value", "x"))));
    }

    @Test
    void transformTakesAllowOrDenyAndNonBlankNames() {
        fails(proxied(null, null, null, new RouteTransform(List.of("a"), List.of("b"), null, null, null)),
                "allow or deny");
        fails(proxied(null, null, null, new RouteTransform(null, null, Map.of("a", " "), null, null)), "mapping");
        fails(proxied(null, null, null, new RouteTransform(null, null, null, "", null)), "transform.target");
        fails(entry(null, new RouteAggregate(List.of(
                new RouteBackend("http://b", null, new RouteTransform(null, null, null, null, " ")),
                new RouteBackend("http://c", null, null)), null), null),
                "aggregate.backends[0].transform.group");
    }

    @Test
    void durationsUseMillisecondsSecondsMinutesOrHours() {
        assertThat(RoutePolicyRules.parseDuration("250ms")).isEqualTo(Duration.ofMillis(250));
        assertThat(RoutePolicyRules.parseDuration("2s")).isEqualTo(Duration.ofSeconds(2));
        assertThat(RoutePolicyRules.parseDuration("5m")).isEqualTo(Duration.ofMinutes(5));
        assertThat(RoutePolicyRules.parseDuration("1h")).isEqualTo(Duration.ofHours(1));
        assertThat(RoutePolicyRules.parseDuration("PT1S")).isNull();
        assertThat(RoutePolicyRules.parseDuration("1d")).isNull();
        assertThat(RoutePolicyRules.parseDuration(null)).isNull();
    }

    private static void fails(RouteConfigEntry route, String fragment) {
        assertThat(RoutePolicyRules.validate(route, PREFIX))
                .hasValueSatisfying(problem -> assertThat(problem).contains(fragment));
    }

    private static void passes(RouteConfigEntry route) {
        assertThat(RoutePolicyRules.validate(route, PREFIX)).isEmpty();
    }

    private static RouteAggregate aggregate(String first, String second) {
        return new RouteAggregate(List.of(new RouteBackend(first, "a", null), new RouteBackend(second, "b", null)), "2s");
    }

    private static RouteConfigEntry entry(String targetUri, RouteAggregate aggregate, List<String> methods) {
        return new RouteConfigEntry("r", "/r/**", targetUri, 1, methods, true, null, null, null,
                null, null, null, null, aggregate);
    }

    private static RouteConfigEntry filters(RouteFilter... filters) {
        return proxied(null, null, List.of(filters), null);
    }

    private static RouteConfigEntry proxied(RouteRateLimit rateLimit, RouteThrottle throttle,
                                            List<RouteFilter> filters, RouteTransform transform) {
        return new RouteConfigEntry("r", "/r/**", "http://r", 1, null, true, null, null, null,
                rateLimit, throttle, filters, transform, null);
    }
}

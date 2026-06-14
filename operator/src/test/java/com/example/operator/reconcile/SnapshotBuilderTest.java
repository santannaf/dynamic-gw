package com.example.operator.reconcile;

import com.example.shared.routes.RouteConfigEntry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static com.example.operator.reconcile.GatewayRouteValidatorTest.route;
import static org.assertj.core.api.Assertions.assertThat;

class SnapshotBuilderTest {

    private final SnapshotBuilder builder = new SnapshotBuilder(new GatewayRouteValidator());
    private final Clock fixedClock = Clock.fixed(Instant.parse("2026-06-13T10:00:00Z"), ZoneOffset.UTC);

    @Test
    void routesAreSortedByIdAscending() {
        SnapshotBuilder.Result result = builder.build(List.of(
                route("zebra-route", "/z/**", "http://z", 1, null, true),
                route("alpha-route", "/a/**", "http://a", 1, null, true)
        ), fixedClock);

        assertThat(result.snapshot().routes())
                .extracting(RouteConfigEntry::id)
                .containsExactly("alpha-route", "zebra-route");
    }

    @Test
    void disabledAndInvalidAreDropped() {
        SnapshotBuilder.Result result = builder.build(List.of(
                route("ok", "/ok/**", "http://ok", 1, null, true),
                route("disabled", "/d/**", "http://d", 1, null, false),
                route("invalid", "/x/**", "", 1, null, true)
        ), fixedClock);

        assertThat(result.snapshot().routes())
                .extracting(RouteConfigEntry::id)
                .containsExactly("ok");
        assertThat(result.total()).isEqualTo(3);
        assertThat(result.valid()).isEqualTo(1);
        assertThat(result.disabled()).isEqualTo(1);
        assertThat(result.invalid()).isEqualTo(1);
    }

    @Test
    void emptyInputProducesEmptySnapshotWithStampedMetadata() {
        SnapshotBuilder.Result result = builder.build(List.of(), fixedClock);

        assertThat(result.snapshot().routes()).isEmpty();
        assertThat(result.snapshot().version()).isEqualTo("2026-06-13T10:00:00Z");
        assertThat(result.snapshot().generatedAt()).isEqualTo(Instant.parse("2026-06-13T10:00:00Z"));
    }

    @Test
    void enabledDefaultsToTrueWhenSpecOmitsIt() {
        SnapshotBuilder.Result result = builder.build(List.of(
                route("ok", "/ok/**", "http://ok", 1, null, null)
        ), fixedClock);

        assertThat(result.snapshot().routes()).hasSize(1);
        assertThat(result.snapshot().routes().get(0).enabled()).isTrue();
    }

    @Test
    void singleInvalidDoesNotAbortPipeline() {
        SnapshotBuilder.Result result = builder.build(List.of(
                route("invalid", "/x/**", "", 1, null, true),
                route("ok1", "/a/**", "http://a", 1, null, true),
                route("ok2", "/b/**", "http://b", 1, null, true)
        ), fixedClock);

        assertThat(result.snapshot().routes())
                .extracting(RouteConfigEntry::id)
                .containsExactly("ok1", "ok2");
    }
}

package com.example.gateway.routing;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteDefinition;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryDynamicRouteLocatorTest {

    private final InMemoryDynamicRouteLocator locator = new InMemoryDynamicRouteLocator();

    @Test
    void startsEmpty() {
        assertThat(locator.size()).isZero();
        assertThat(locator.currentRoutes()).isEmpty();
        assertThat(locator.currentDefinitions()).isEmpty();
        StepVerifier.create(locator.getRoutes()).verifyComplete();
    }

    @Test
    void replaceAllPublishesNewSnapshot() {
        RouteDefinition rdA = rd("A");
        RouteDefinition rdB = rd("B");

        locator.replaceAll(List.of(route(rdA), route(rdB)), List.of(rdA, rdB));

        assertThat(locator.size()).isEqualTo(2);
        assertThat(locator.currentRoutes()).extracting(Route::getId).containsExactly("A", "B");
        assertThat(locator.currentDefinitions()).extracting(RouteDefinition::getId).containsExactly("A", "B");
        StepVerifier.create(locator.getRoutes().map(Route::getId))
                .expectNext("A", "B")
                .verifyComplete();
    }

    @Test
    void replaceAllDropsStaleEntries() {
        RouteDefinition a = rd("A");
        RouteDefinition b = rd("B");
        locator.replaceAll(List.of(route(a), route(b)), List.of(a, b));

        RouteDefinition c = rd("C");
        locator.replaceAll(List.of(route(c)), List.of(c));

        assertThat(locator.currentRoutes()).extracting(Route::getId).containsExactly("C");
        assertThat(locator.currentDefinitions()).extracting(RouteDefinition::getId).containsExactly("C");
    }

    @Test
    void replaceAllStoresImmutableSnapshots() {
        RouteDefinition a = rd("A");
        java.util.ArrayList<Route> routes = new java.util.ArrayList<>(List.of(route(a)));
        java.util.ArrayList<RouteDefinition> defs = new java.util.ArrayList<>(List.of(a));

        locator.replaceAll(routes, defs);
        routes.clear();
        defs.clear();

        assertThat(locator.currentRoutes())
                .as("internal list must not reflect mutations on the caller's list")
                .extracting(Route::getId).containsExactly("A");
        assertThat(locator.currentDefinitions())
                .extracting(RouteDefinition::getId).containsExactly("A");
    }

    @RepeatedTest(5)
    void replaceAllIsAtomicFromReaderPerspective() throws InterruptedException {
        RouteDefinition a = rd("A");
        RouteDefinition b = rd("B");
        RouteDefinition c = rd("C");
        RouteDefinition d = rd("D");
        locator.replaceAll(List.of(route(a), route(b), route(c)), List.of(a, b, c));

        AtomicBoolean partialObserved = new AtomicBoolean(false);
        AtomicBoolean stop = new AtomicBoolean(false);
        int readers = 4;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(readers + 1);
        ExecutorService pool = Executors.newFixedThreadPool(readers + 1);

        for (int i = 0; i < readers; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    while (!stop.get()) {
                        Set<String> ids = locator.getRoutes()
                                .map(Route::getId)
                                .collect(Collectors.toSet())
                                .block();
                        if (ids == null || ids.isEmpty()) {
                            partialObserved.set(true);
                        } else if (!ids.equals(Set.of("A", "B", "C")) && !ids.equals(Set.of("A", "D"))) {
                            partialObserved.set(true);
                        }
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        pool.submit(() -> {
            try {
                start.await();
                for (int i = 0; i < 200; i++) {
                    locator.replaceAll(List.of(route(a), route(d)), List.of(a, d));
                    locator.replaceAll(List.of(route(a), route(b), route(c)), List.of(a, b, c));
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } finally {
                stop.set(true);
                done.countDown();
            }
        });

        start.countDown();
        done.await(10, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertThat(partialObserved.get())
                .as("no reader should observe an empty or partial route set")
                .isFalse();
    }

    private static RouteDefinition rd(String id) {
        RouteDefinition r = new RouteDefinition();
        r.setId(id);
        r.setUri(URI.create("http://" + id.toLowerCase()));
        return r;
    }

    private static Route route(RouteDefinition rd) {
        return Route.async(rd).asyncPredicate(exchange -> Mono.just(true)).build();
    }
}

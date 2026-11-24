package com.example.gateway.routing;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.route.RouteDefinition;
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

class InMemoryDynamicRouteDefinitionRepositoryTest {

    private final InMemoryDynamicRouteDefinitionRepository repo = new InMemoryDynamicRouteDefinitionRepository();

    @Test
    void replaceAllRemovesStaleRoutes() {
        repo.replaceAll(List.of(rd("A"), rd("B"), rd("C")));
        assertThat(idsIn(repo)).containsExactlyInAnyOrder("A", "B", "C");

        repo.replaceAll(List.of(rd("A"), rd("D")));

        assertThat(idsIn(repo)).containsExactlyInAnyOrder("A", "D");
    }

    @Test
    void getRouteDefinitionsExposesCurrentState() {
        repo.replaceAll(List.of(rd("X"), rd("Y")));

        StepVerifier.create(repo.getRouteDefinitions().map(RouteDefinition::getId).sort())
                .expectNext("X", "Y")
                .verifyComplete();
    }

    @Test
    void saveUpsertsById() {
        repo.replaceAll(List.of(rd("A")));
        RouteDefinition updated = rd("A");
        updated.setUri(URI.create("http://changed"));

        StepVerifier.create(repo.save(reactor.core.publisher.Mono.just(updated))).verifyComplete();

        StepVerifier.create(repo.getRouteDefinitions())
                .assertNext(rd -> assertThat(rd.getUri()).hasToString("http://changed"))
                .verifyComplete();
    }

    @Test
    void deleteRemovesById() {
        repo.replaceAll(List.of(rd("A"), rd("B")));

        StepVerifier.create(repo.delete(reactor.core.publisher.Mono.just("A"))).verifyComplete();

        assertThat(idsIn(repo)).containsExactly("B");
    }

    @RepeatedTest(5)
    void replaceAllIsAtomicFromReaderPerspective() throws InterruptedException {
        repo.replaceAll(List.of(rd("A"), rd("B"), rd("C")));

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
                        Set<String> ids = repo.getRouteDefinitions()
                                .map(RouteDefinition::getId)
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
                    repo.replaceAll(List.of(rd("A"), rd("D")));
                    repo.replaceAll(List.of(rd("A"), rd("B"), rd("C")));
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

    private static List<String> idsIn(InMemoryDynamicRouteDefinitionRepository r) {
        return r.getRouteDefinitions().map(RouteDefinition::getId).collectList().block();
    }
}

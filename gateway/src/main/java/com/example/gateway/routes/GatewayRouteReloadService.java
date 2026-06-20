package com.example.gateway.routes;

import com.example.gateway.routing.InMemoryDynamicRouteLocator;
import com.example.gateway.routing.RouteCompiler;
import com.example.gateway.routing.RouteDefinitionMapper;
import com.example.gateway.routes.store.RouteConfigProvider;
import com.example.shared.routes.RouteConfigEntry;
import com.example.shared.routes.RouteConfigSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.event.RefreshRoutesEvent;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

@Service
public class GatewayRouteReloadService {

    private static final Logger log = LoggerFactory.getLogger(GatewayRouteReloadService.class);

    private final RouteConfigProvider provider;
    private final RouteDefinitionMapper mapper;
    private final InMemoryDynamicRouteLocator routeLocator;
    private final RouteCompiler routeCompiler;
    private final ApplicationEventPublisher events;
    // Mantemos só o hash SHA-256 do último snapshot, não a lista inteira de
    // RouteConfigEntry. Para 60k rotas, segurar a List<RouteConfigEntry>
    // ocupava ~50 MiB de heap continuamente só para suportar o early-return
    // de "snapshot inalterado"; 64 chars hex (128 bytes) substituem isso
    // sem perda de corretude — colisão SHA-256 é praticamente impossível.
    private volatile String lastLoadedHash = "";
    private volatile List<String> lastLoadedIds = List.of();

    public GatewayRouteReloadService(RouteConfigProvider provider,
                                     RouteDefinitionMapper mapper,
                                     InMemoryDynamicRouteLocator routeLocator,
                                     RouteCompiler routeCompiler,
                                     ApplicationEventPublisher events) {
        this.provider = provider;
        this.mapper = mapper;
        this.routeLocator = routeLocator;
        this.routeCompiler = routeCompiler;
        this.events = events;
    }

    public List<String> reloadFromStore() {
        long startNs = System.nanoTime();
        log.info("Loading routes from route config store");

        RouteConfigSnapshot snapshot = provider.load();
        long loadMs = elapsedMs(startNs);
        int snapshotRoutes = snapshot.routes().size();

        String snapshotHash = hashEntries(snapshot.routes());
        if (snapshotHash.equals(lastLoadedHash)) {
            log.info("Snapshot routes unchanged (version={} snapshotRoutes={} hash={}); skipping reload elapsedMs={}",
                    snapshot.version(), snapshotRoutes, snapshotHash, loadMs);
            return lastLoadedIds;
        }

        log.info("Route config snapshot loaded version={} snapshotRoutes={} elapsedMs={}",
                snapshot.version(), snapshotRoutes, loadMs);

        List<RouteDefinition> definitions = snapshot.routes().stream()
                .map(this::mapAndLog)
                .toList();
        long mapEndMs = elapsedMs(startNs);

        List<Route> routes = routeCompiler.compile(definitions);
        int activeRoutes = routes.size();
        long convertEndMs = elapsedMs(startNs);

        // Mapper e compiler são 1:1, então snapshotRoutes deveria sempre bater
        // com activeRoutes. Logamos um WARN se divergir – isso só pode acontecer
        // se algum estágio futuramente filtrar (ex: validação que dropa rotas
        // inválidas em vez de explodir), e nesse caso queremos que apareça no
        // log de produção, não que vire bug silencioso.
        if (activeRoutes != snapshotRoutes) {
            log.warn("Route count mismatch detected snapshotRoutes={} activeRoutes={} dropped={}",
                    snapshotRoutes, activeRoutes, snapshotRoutes - activeRoutes);
        }

        routeLocator.replaceAll(routes, definitions);
        long replaceEndMs = elapsedMs(startNs);
        log.info("Routes replaced in memory activeRoutes={} elapsedMs={}",
                activeRoutes, replaceEndMs);

        events.publishEvent(new RefreshRoutesEvent(this));
        long totalMs = elapsedMs(startNs);

        List<String> ids = routes.stream()
                .map(Route::getId)
                .sorted()
                .toList();

        lastLoadedHash = snapshotHash;
        lastLoadedIds = ids;

        log.info("Reload completed snapshotRoutes={} activeRoutes={} totalMs={} (loadMs={} mapMs={} convertMs={} replaceMs={} publishMs={})",
                snapshotRoutes, activeRoutes, totalMs,
                loadMs,
                mapEndMs - loadMs,
                convertEndMs - mapEndMs,
                replaceEndMs - convertEndMs,
                totalMs - replaceEndMs);

        return ids;
    }

    private RouteDefinition mapAndLog(RouteConfigEntry entry) {
        if (log.isDebugEnabled()) {
            log.debug("Route loaded id={} path={} targetUri={}",
                    entry.id(), entry.path(), entry.targetUri());
        }
        return mapper.toDefinition(entry);
    }

    /**
     * SHA-256 do conteúdo do snapshot, em streaming (sem materializar Strings
     * intermediárias). Hashamos cada campo do RouteConfigEntry separado por
     * um byte 0x00 pra evitar ambiguidade entre, por exemplo, id="ab" path="c"
     * e id="a" path="bc". Suficiente pra detectar "esse snapshot é o mesmo da
     * última vez" — colisão é da ordem de 2^-256.
     * Custo: ~12 MiB de alocação temporária pra 60k entries (uns ~200 bytes
     * de String->bytes por entry, GC-eligible logo em seguida). Em troca,
     * dispensamos segurar a List<RouteConfigEntry> em campo (~50 MiB residentes
     * pra 60k entries).
     */
    private static String hashEntries(List<RouteConfigEntry> entries) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (RouteConfigEntry e : entries) {
                updateUtf8(md, e.id());
                updateUtf8(md, e.path());
                updateUtf8(md, e.targetUri());
                md.update((byte) (e.stripPrefix() == null ? -1 : e.stripPrefix()));
                md.update((byte) 0);
                if (e.methods() != null) {
                    for (String m : e.methods()) {
                        updateUtf8(md, m);
                    }
                }
                md.update((byte) 0);
                md.update((byte) (Boolean.TRUE.equals(e.enabled()) ? 1 : 0));
                // team e description são metadados (não afetam matching), mas
                // mudá-los precisa disparar reload pra atualizar a resposta de
                // /internal/routes. Por isso entram no hash.
                updateUtf8(md, e.team());
                updateUtf8(md, e.description());
                md.update((byte) '\n');
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 é obrigatório em qualquer JVM/SubstrateVM compatível com a spec.
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }

    private static void updateUtf8(MessageDigest md, String s) {
        if (s != null) {
            md.update(s.getBytes(StandardCharsets.UTF_8));
        }
        md.update((byte) 0);
    }

    private static long elapsedMs(long startNs) { return (System.nanoTime() - startNs) / 1_000_000; }
}
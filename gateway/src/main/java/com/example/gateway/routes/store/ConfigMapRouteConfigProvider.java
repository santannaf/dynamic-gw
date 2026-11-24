package com.example.gateway.routes.store;

import com.example.shared.routes.RouteConfigSnapshot;
import com.example.shared.routes.SnapshotCodec;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

public class ConfigMapRouteConfigProvider implements RouteConfigProvider {

    private static final Logger log = LoggerFactory.getLogger(ConfigMapRouteConfigProvider.class);

    private final KubernetesClient client;
    private final SnapshotCodec codec;
    private final RouteStoreProperties.ConfigMap props;

    public ConfigMapRouteConfigProvider(KubernetesClient client,
                                        SnapshotCodec codec,
                                        RouteStoreProperties properties) {
        this.client = client;
        this.codec = codec;
        this.props = properties.getConfigmap();
    }

    @Override
    public RouteConfigSnapshot load() {
        ConfigMap cm = client.configMaps()
                .inNamespace(props.getNamespace())
                .withName(props.getConfigMapName())
                .get();
        if (cm == null) {
            log.warn("ConfigMap {}/{} not found; returning empty snapshot",
                    props.getNamespace(), props.getConfigMapName());
            return new RouteConfigSnapshot("empty", Instant.now(), List.of());
        }

        Map<String, String> binaryData = cm.getBinaryData();
        if (binaryData != null) {
            String b64 = binaryData.get(props.getGzipKey());
            if (b64 != null && !b64.isBlank()) {
                byte[] gz = Base64.getDecoder().decode(b64);
                RouteConfigSnapshot snapshot = codec.readYamlGz(gz);
                log.info("Loaded gzipped route config snapshot from {}/{} binaryData[{}] gzippedBytes={} version={} routes={}",
                        props.getNamespace(), props.getConfigMapName(),
                        props.getGzipKey(), gz.length,
                        snapshot.version(), snapshot.routes().size());
                return snapshot;
            }
        }

        Map<String, String> data = cm.getData();
        String yaml = data == null ? null : data.get(props.getKey());
        if (yaml == null || yaml.isBlank()) {
            log.warn("ConfigMap {}/{} has no data under key '{}' (and no binaryData under '{}'); returning empty snapshot",
                    props.getNamespace(), props.getConfigMapName(),
                    props.getKey(), props.getGzipKey());
            return new RouteConfigSnapshot("empty", Instant.now(), List.of());
        }

        RouteConfigSnapshot snapshot = codec.readYaml(yaml);
        log.info("Loaded plain-text route config snapshot from {}/{} data[{}] yamlBytes={} version={} routes={}",
                props.getNamespace(), props.getConfigMapName(),
                props.getKey(), yaml.length(),
                snapshot.version(), snapshot.routes().size());
        return snapshot;
    }
}
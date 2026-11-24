package com.example.gateway.routes.store;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

public class ConfigMapRouteConfigChangeListener implements RouteConfigChangeListener {

    private static final Logger log = LoggerFactory.getLogger(ConfigMapRouteConfigChangeListener.class);

    private final KubernetesClient client;
    private final RouteStoreProperties.ConfigMap props;

    private volatile Runnable onChange;
    private volatile SharedIndexInformer<ConfigMap> informer;
    private volatile String lastResourceVersion;
    private volatile boolean running;

    public ConfigMapRouteConfigChangeListener(KubernetesClient client,
                                              RouteStoreProperties properties) {
        this.client = client;
        this.props = properties.getConfigmap();
    }

    @Override
    public synchronized void start(Runnable onChange) {
        if (running) return;
        this.onChange = Objects.requireNonNull(onChange, "onChange");
        this.running = true;

        try {
            ConfigMap initial = client.configMaps()
                    .inNamespace(props.getNamespace())
                    .withName(props.getConfigMapName())
                    .get();
            lastResourceVersion = initial != null && initial.getMetadata() != null
                    ? initial.getMetadata().getResourceVersion()
                    : null;
        } catch (Exception e) {
            log.warn("Failed to read initial resourceVersion for ConfigMap {}/{}: {}",
                    props.getNamespace(), props.getConfigMapName(), e.getMessage());
            lastResourceVersion = null;
        }

        informer = client.configMaps()
                .inNamespace(props.getNamespace())
                .withName(props.getConfigMapName())
                .runnableInformer(0)
                .exceptionHandler((isStarted, t) -> {
                    log.warn("ConfigMap informer error (started={}): {}",
                            isStarted, t.getMessage());
                    return true;
                })
                .addEventHandler(new ResourceEventHandler<>() {
                    @Override
                    public void onAdd(ConfigMap obj) { handleEvent("ADDED", obj); }

                    @Override
                    public void onUpdate(ConfigMap oldObj, ConfigMap newObj) { handleEvent("UPDATED", newObj); }

                    @Override
                    public void onDelete(ConfigMap obj, boolean unknownFinalState) { handleEvent("DELETED", obj); }
                });

        informer.start().whenComplete((_, ex) -> {
            if (ex != null) {
                log.error("ConfigMap informer failed to start namespace={} name={}: {}",
                        props.getNamespace(), props.getConfigMapName(), ex.getMessage(), ex);
                return;
            }
            log.info("ConfigMap route informer synced namespace={} name={} initialResourceVersion={}",
                    props.getNamespace(), props.getConfigMapName(), lastResourceVersion);
        });
    }

    @Override
    public synchronized void stop() {
        running = false;
        if (informer != null) {
            try {
                informer.stop();
            } catch (Exception e) {
                log.warn("Failed to stop ConfigMap informer: {}", e.getMessage());
            }
            informer = null;
        }
        log.info("ConfigMap route informer stopped namespace={} name={}",
                props.getNamespace(), props.getConfigMapName());
    }

    private void handleEvent(String action, ConfigMap cm) {
        if (!running) return;

        String version = cm != null && cm.getMetadata() != null
                ? cm.getMetadata().getResourceVersion()
                : null;
        String previous = lastResourceVersion;

        if (Objects.equals(version, previous)) return;

        lastResourceVersion = version;

        log.info("ConfigMap {}/{} changed action={} previousResourceVersion={} resourceVersion={}; triggering reload",
                props.getNamespace(), props.getConfigMapName(), action, previous, version);
        try {
            onChange.run();
        } catch (RuntimeException e) {
            log.warn("Reload triggered by ConfigMap {} event failed: {}",
                    action, e.getMessage(), e);
        }
    }
}
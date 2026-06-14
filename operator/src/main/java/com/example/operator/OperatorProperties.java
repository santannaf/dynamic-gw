package com.example.operator;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "operator")
public class OperatorProperties {

    private String namespace = "platform";
    private Gateway gateway = new Gateway();
    private Reconcile reconcile = new Reconcile();

    public String getNamespace() {
        return namespace;
    }

    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }

    public Gateway getGateway() {
        return gateway;
    }

    public void setGateway(Gateway gateway) {
        this.gateway = gateway;
    }

    public Reconcile getReconcile() {
        return reconcile;
    }

    public void setReconcile(Reconcile reconcile) {
        this.reconcile = reconcile;
    }

    public static class Gateway {
        private String reloadUrl = "http://dynamic-gateway.platform.svc.cluster.local:8080/internal/routes/reload";

        public String getReloadUrl() {
            return reloadUrl;
        }

        public void setReloadUrl(String reloadUrl) {
            this.reloadUrl = reloadUrl;
        }
    }

    public static class Reconcile {
        private Duration debounce = Duration.ofMillis(200);

        public Duration getDebounce() {
            return debounce;
        }

        public void setDebounce(Duration debounce) {
            this.debounce = debounce;
        }
    }
}

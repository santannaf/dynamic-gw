package com.example.gateway.routes.store;

import com.example.shared.routes.RouteConfigEntry;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "gateway.routes.store")
public class RouteStoreProperties {

    private String type = "configmap";
    private ConfigMap configmap = new ConfigMap();
    private S3 s3 = new S3();
    private Properties properties = new Properties();

    public String getType() { return type; }

    public void setType(String type) { this.type = type; }

    public ConfigMap getConfigmap() { return configmap; }

    public void setConfigmap(ConfigMap configmap) { this.configmap = configmap; }

    public S3 getS3() { return s3; }

    public void setS3(S3 s3) { this.s3 = s3; }

    public Properties getProperties() { return properties; }

    public void setProperties(Properties properties) { this.properties = properties; }

    public static class ConfigMap {
        private String namespace = "platform";
        private String configMapName = "gateway-routes";
        private String key = "routes.yaml";
        private boolean gzip = true;

        public String getNamespace() { return namespace; }

        public void setNamespace(String namespace) { this.namespace = namespace; }

        public String getConfigMapName() { return configMapName; }

        public void setConfigMapName(String configMapName) { this.configMapName = configMapName; }

        public String getKey() { return key; }

        public void setKey(String key) { this.key = key; }

        public boolean isGzip() { return gzip; }

        public void setGzip(boolean gzip) { this.gzip = gzip; }

        public String getGzipKey() { return key + ".gz"; }
    }

    public static class Properties {
        private String version = "static";
        private List<RouteConfigEntry> routes = new ArrayList<>();

        public String getVersion() { return version; }

        public void setVersion(String version) { this.version = version; }

        public List<RouteConfigEntry> getRoutes() { return routes; }

        public void setRoutes(List<RouteConfigEntry> routes) { this.routes = routes; }
    }

    public static class S3 {
        private String bucket;
        private String key;
        private String region = "us-east-1";
        private String endpoint;
        private boolean pathStyleAccess;
        private Duration pollInterval = Duration.ofSeconds(15);

        public String getBucket() { return bucket; }

        public void setBucket(String bucket) { this.bucket = bucket; }

        public String getKey() { return key; }

        public void setKey(String key) { this.key = key; }

        public String getRegion() { return region; }

        public void setRegion(String region) { this.region = region; }

        public String getEndpoint() { return endpoint; }

        public void setEndpoint(String endpoint) { this.endpoint = endpoint; }

        public boolean isPathStyleAccess() { return pathStyleAccess; }

        public void setPathStyleAccess(boolean pathStyleAccess) { this.pathStyleAccess = pathStyleAccess; }

        public Duration getPollInterval() { return pollInterval; }

        public void setPollInterval(Duration pollInterval) { this.pollInterval = pollInterval; }
    }
}
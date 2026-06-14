package com.example.operator.store;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "operator.routes.store")
public class RouteStoreProperties {

    private String type = "configmap";
    private ConfigMap configmap = new ConfigMap();
    private S3 s3 = new S3();

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public ConfigMap getConfigmap() {
        return configmap;
    }

    public void setConfigmap(ConfigMap configmap) {
        this.configmap = configmap;
    }

    public S3 getS3() {
        return s3;
    }

    public void setS3(S3 s3) {
        this.s3 = s3;
    }

    public static class ConfigMap {
        private String namespace = "platform";
        private String configMapName = "gateway-routes";
        private String key = "routes.yaml";

        public String getNamespace() {
            return namespace;
        }

        public void setNamespace(String namespace) {
            this.namespace = namespace;
        }

        public String getConfigMapName() {
            return configMapName;
        }

        public void setConfigMapName(String configMapName) {
            this.configMapName = configMapName;
        }

        public String getKey() {
            return key;
        }

        public void setKey(String key) {
            this.key = key;
        }
    }

    public static class S3 {
        private String bucket;
        private String key;
        private String region;

        public String getBucket() {
            return bucket;
        }

        public void setBucket(String bucket) {
            this.bucket = bucket;
        }

        public String getKey() {
            return key;
        }

        public void setKey(String key) {
            this.key = key;
        }

        public String getRegion() {
            return region;
        }

        public void setRegion(String region) {
            this.region = region;
        }
    }
}

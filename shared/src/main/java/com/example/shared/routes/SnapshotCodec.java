package com.example.shared.routes;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.yaml.YAMLFactory;
import tools.jackson.dataformat.yaml.YAMLMapper;
import tools.jackson.dataformat.yaml.YAMLWriteFeature;

public final class SnapshotCodec {

    private final YAMLMapper yamlMapper;
    private final JsonMapper jsonMapper;

    public SnapshotCodec() {
        YAMLFactory yaml = YAMLFactory.builder()
                .disable(YAMLWriteFeature.WRITE_DOC_START_MARKER)
                .build();
        this.yamlMapper = YAMLMapper.builder(yaml)
                .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
        this.jsonMapper = JsonMapper.builder()
                .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
    }

    public String writeYaml(RouteConfigSnapshot snapshot) {
        try {
            return yamlMapper.writeValueAsString(snapshot);
        } catch (Exception e) {
            throw new SnapshotCodecException("Failed to serialize snapshot to YAML", e);
        }
    }

    public RouteConfigSnapshot readYaml(String yaml) {
        try {
            return yamlMapper.readValue(yaml, RouteConfigSnapshot.class);
        } catch (Exception e) {
            throw new SnapshotCodecException("Failed to deserialize snapshot from YAML", e);
        }
    }

    public String writeJson(RouteConfigSnapshot snapshot) {
        try {
            return jsonMapper.writeValueAsString(snapshot);
        } catch (Exception e) {
            throw new SnapshotCodecException("Failed to serialize snapshot to JSON", e);
        }
    }

    public static final class SnapshotCodecException extends RuntimeException {
        public SnapshotCodecException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

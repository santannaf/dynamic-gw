package com.example.shared.routes;

import org.snakeyaml.engine.v2.api.LoadSettings;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.yaml.YAMLFactory;
import tools.jackson.dataformat.yaml.YAMLMapper;
import tools.jackson.dataformat.yaml.YAMLWriteFeature;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public final class SnapshotCodec {

    /**
     * Limite de code points (efetivamente, bytes UTF-8) que o snakeyaml-engine
     * aceita por documento durante o parse. O default da biblioteca é 3 MiB
     * ({@code 3 * 1024 * 1024}) — o que estoura ao descomprimir snapshots
     * grandes (ex.: 30k rotas ≈ 5,35 MiB, 50k ≈ 8,92 MiB). Subimos para
     * 64 MiB com folga: comporta ~350k rotas no modelo atual, e o custo
     * de subir o limite é nulo (a validação é só uma checagem, sem
     * pré-alocação).
     */
    public static final int DEFAULT_YAML_CODE_POINT_LIMIT = 64 * 1024 * 1024;

    private final YAMLMapper yamlMapper;
    private final JsonMapper jsonMapper;

    public SnapshotCodec() {
        this(DEFAULT_YAML_CODE_POINT_LIMIT);
    }

    public SnapshotCodec(int yamlCodePointLimit) {
        LoadSettings loadSettings = LoadSettings.builder()
                .setCodePointLimit(yamlCodePointLimit)
                .build();
        YAMLFactory yaml = YAMLFactory.builder()
                .disable(YAMLWriteFeature.WRITE_DOC_START_MARKER)
                .loadSettings(loadSettings)
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

    @SuppressWarnings("unused")
    public String writeJson(RouteConfigSnapshot snapshot) {
        try {
            return jsonMapper.writeValueAsString(snapshot);
        } catch (Exception e) {
            throw new SnapshotCodecException("Failed to serialize snapshot to JSON", e);
        }
    }

    /**
     * YAML serialization compressed with gzip. Used to fit large snapshots
     * into a Kubernetes ConfigMap (1 MiB hard limit on the object): with
     * highly redundant content like our route table, gzip routinely
     * reduces size by ~95%. The result is binary and must be stored under
     * {@code ConfigMap.binaryData} (not {@code data}).
     */
    public byte[] writeYamlGz(RouteConfigSnapshot snapshot) {
        byte[] yaml = writeYaml(snapshot).getBytes(StandardCharsets.UTF_8);
        try (ByteArrayOutputStream buffer = new ByteArrayOutputStream(yaml.length / 8);
             GZIPOutputStream gz = new GZIPOutputStream(buffer)) {
            gz.write(yaml);
            gz.finish();
            return buffer.toByteArray();
        } catch (IOException e) {
            throw new SnapshotCodecException("Failed to gzip-serialize snapshot", e);
        }
    }

    /**
     * Inverse of {@link #writeYamlGz}: decompress and parse the YAML.
     */
    public RouteConfigSnapshot readYamlGz(byte[] gzipped) {
        try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(gzipped))) {
            String yaml = new String(gz.readAllBytes(), StandardCharsets.UTF_8);
            return readYaml(yaml);
        } catch (IOException e) {
            throw new SnapshotCodecException("Failed to gunzip snapshot bytes", e);
        }
    }

    public static final class SnapshotCodecException extends RuntimeException {
        public SnapshotCodecException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
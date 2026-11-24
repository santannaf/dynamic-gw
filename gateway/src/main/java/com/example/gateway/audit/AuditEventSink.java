package com.example.gateway.audit;

/**
 * Where audit events go. The default writes JSON lines to the {@code AUDIT}
 * logger; a sink that ships straight to the SOC (Kafka, HTTP) can replace it
 * without touching the filters.
 */
public interface AuditEventSink {

    void emit(AuditEvent event);
}

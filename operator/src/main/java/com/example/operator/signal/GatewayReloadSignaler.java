package com.example.operator.signal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class GatewayReloadSignaler {

    private static final Logger log = LoggerFactory.getLogger(GatewayReloadSignaler.class);

    private final HttpClient client;
    private final URI url;
    private final Duration requestTimeout;

    public GatewayReloadSignaler(String url) {
        this(url, Duration.ofSeconds(2), Duration.ofSeconds(10));
    }

    public GatewayReloadSignaler(String url, Duration connectTimeout, Duration requestTimeout) {
        this.client = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .build();
        this.url = URI.create(url);
        this.requestTimeout = requestTimeout;
    }

    public void signal() {
        HttpRequest request = HttpRequest.newBuilder(url)
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                log.info("Reload signal accepted url={} status={}", url, response.statusCode());
            } else {
                log.warn("Reload signal rejected url={} status={} body={}",
                        url, response.statusCode(), response.body());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Reload signal interrupted url={} message={}", url, e.getMessage());
        } catch (Exception e) {
            log.warn("Reload signal failed url={} exception={} message={}",
                    url, e.getClass().getSimpleName(), e.getMessage());
        }
    }
}

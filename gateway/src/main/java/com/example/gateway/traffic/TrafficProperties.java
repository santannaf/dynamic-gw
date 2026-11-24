package com.example.gateway.traffic;

import com.example.shared.routes.RouteRateLimit;
import com.example.shared.routes.RouteThrottle;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

/**
 * {@code gateway.traffic.*}: the ceilings that hold for every route together,
 * and the limits of transformation and aggregation. Route policies live on the
 * routes themselves. Every count is per gateway replica.
 */
@ConfigurationProperties(prefix = "gateway.traffic")
public class TrafficProperties {

    /**
     * Proxies in front of the gateway that append the caller to
     * X-Forwarded-For (nginx-lb is one). The rate limit key {@code ip} reads
     * the entry the outermost trusted proxy added, never one the caller sent.
     */
    private int trustedProxies = 1;
    private final RateLimit rateLimit = new RateLimit();
    private final Throttle throttle = new Throttle();
    private final Transform transform = new Transform();
    private final Aggregate aggregate = new Aggregate();

    public int getTrustedProxies() { return trustedProxies; }

    public void setTrustedProxies(int trustedProxies) { this.trustedProxies = trustedProxies; }

    public RateLimit getRateLimit() { return rateLimit; }

    public Throttle getThrottle() { return throttle; }

    public Transform getTransform() { return transform; }

    public Aggregate getAggregate() { return aggregate; }

    /** Global requests per period, summed over all routes. Off while {@code requests} is unset. */
    public static class RateLimit {
        private Integer requests;
        private String period = "1s";
        private Integer burst;
        private String key;
        /** Most per-caller budgets kept in memory; beyond it new callers are not limited per caller. */
        private int maxKeys = 100_000;

        public Integer getRequests() { return requests; }

        public void setRequests(Integer requests) { this.requests = requests; }

        public String getPeriod() { return period; }

        public void setPeriod(String period) { this.period = period; }

        public Integer getBurst() { return burst; }

        public void setBurst(Integer burst) { this.burst = burst; }

        public String getKey() { return key; }

        public void setKey(String key) { this.key = key; }

        public int getMaxKeys() { return maxKeys; }

        public void setMaxKeys(int maxKeys) { this.maxKeys = maxKeys; }

        public RouteRateLimit toPolicy() {
            return requests == null ? null : new RouteRateLimit(requests, period, burst, key);
        }
    }

    /** Global concurrency cap, over all routes. Off while {@code max-concurrent} is unset. */
    public static class Throttle {
        private Integer maxConcurrent;
        private Integer maxQueue;
        private String maxWait;

        public Integer getMaxConcurrent() { return maxConcurrent; }

        public void setMaxConcurrent(Integer maxConcurrent) { this.maxConcurrent = maxConcurrent; }

        public Integer getMaxQueue() { return maxQueue; }

        public void setMaxQueue(Integer maxQueue) { this.maxQueue = maxQueue; }

        public String getMaxWait() { return maxWait; }

        public void setMaxWait(String maxWait) { this.maxWait = maxWait; }

        public RouteThrottle toPolicy() {
            return maxConcurrent == null ? null : new RouteThrottle(maxConcurrent, maxQueue, maxWait);
        }
    }

    public static class Transform {
        /** Largest response the gateway buffers to transform; a larger one passes untouched. */
        private DataSize maxBodySize = DataSize.ofMegabytes(1);

        public DataSize getMaxBodySize() { return maxBodySize; }

        public void setMaxBodySize(DataSize maxBodySize) { this.maxBodySize = maxBodySize; }
    }

    public static class Aggregate {
        /** Time limit of an aggregated route that does not declare its own. */
        private Duration timeout = Duration.ofSeconds(5);
        /** Largest response read from each backend; a larger one counts as a failed backend. */
        private DataSize maxBodySize = DataSize.ofMegabytes(1);

        public Duration getTimeout() { return timeout; }

        public void setTimeout(Duration timeout) { this.timeout = timeout; }

        public DataSize getMaxBodySize() { return maxBodySize; }

        public void setMaxBodySize(DataSize maxBodySize) { this.maxBodySize = maxBodySize; }
    }
}

package com.example.gateway.traffic;

import com.example.gateway.access.UpstreamTokenFilter;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UpstreamTokenFilterOrderTest {

    @Test
    void theRouteTokenIsGotAfterTheTrafficPoliciesAndBeforeTheAggregation() {
        assertThat(UpstreamTokenFilter.ORDER).isGreaterThan(RateLimitFilter.ORDER).isGreaterThan(ThrottleFilter.ORDER)
                .isLessThan(AggregationFilter.ORDER);
    }
}

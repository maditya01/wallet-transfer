package com.example.wallet_transfer.observability;

import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes the Prometheus scrape output at /metrics.
 *
 * Boot 4 ships the PrometheusMeterRegistry bean (via micrometer-registry-prometheus)
 * but the actuator scrape endpoint module isn't auto-wired here, so we surface the
 * registry's own scrape() directly. This is the exact text format Prometheus expects.
 */
@RestController
public class MetricsController {

    private final PrometheusMeterRegistry registry;

    public MetricsController(PrometheusMeterRegistry registry) {
        this.registry = registry;
    }

    @GetMapping(value = "/metrics", produces = MediaType.TEXT_PLAIN_VALUE)
    public String scrape() {
        return registry.scrape();   // full Prometheus exposition text (includes our transfers.* counters)
    }
}

package dev.aparikh.searchemail.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * Typed configuration properties for Solr connection.
 */
@Validated
@ConfigurationProperties(prefix = "solr")
class SolrConfigurationProperties {

    @NotBlank
    private String baseUrl;

    @NotBlank
    private String core;

    @PositiveOrZero
    private int commitWithinMs = 0; // 0 = explicit commit

    String getBaseUrl() {
        return baseUrl;
    }

    void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    String getCore() {
        return core;
    }

    void setCore(String core) {
        this.core = core;
    }

    int getCommitWithinMs() {
        return commitWithinMs;
    }

    void setCommitWithinMs(int commitWithinMs) {
        this.commitWithinMs = commitWithinMs;
    }
}

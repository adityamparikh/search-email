package dev.aparikh.searchemail.config;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.impl.HttpSolrClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(SolrConfigurationProperties.class)
class SolrConfig {

    private final SolrConfigurationProperties properties;

    SolrConfig(SolrConfigurationProperties properties) {
        this.properties = properties;
    }

    @Bean
    SolrClient solrClient() {
        // Normalize base URL and core without trailing slash to avoid path issues
        String baseUrl = properties.getBaseUrl();
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        String core = properties.getCore();
        if (core.startsWith("/")) {
            core = core.substring(1);
        }
        String baseCoreUrl = baseUrl + "/" + core; // e.g., http://host:8983/solr/emails

        // Use default BinaryResponseParser for SolrJ 9
        return new HttpSolrClient.Builder(baseCoreUrl).build();
    }
}

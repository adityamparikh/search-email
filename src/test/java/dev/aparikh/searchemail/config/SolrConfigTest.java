package dev.aparikh.searchemail.config;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.impl.HttpSolrClient;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SolrConfigTest {

    @Test
    void solrClientCreatesHttpSolrClientWithCorrectUrl() {
        SolrConfigurationProperties properties = new SolrConfigurationProperties();
        properties.setBaseUrl("http://localhost:8983/solr");
        properties.setCore("test-core");
        
        SolrConfig config = new SolrConfig(properties);
        SolrClient client = config.solrClient();
        
        assertThat(client).isInstanceOf(HttpSolrClient.class);
        HttpSolrClient httpClient = (HttpSolrClient) client;
        assertThat(httpClient.getBaseURL()).isEqualTo("http://localhost:8983/solr/test-core");
    }

    @Test
    void solrClientNormalizesUrlsWithTrailingSlashes() {
        SolrConfigurationProperties properties = new SolrConfigurationProperties();
        properties.setBaseUrl("http://localhost:8983/solr/");
        properties.setCore("test-core");
        
        SolrConfig config = new SolrConfig(properties);
        SolrClient client = config.solrClient();
        
        HttpSolrClient httpClient = (HttpSolrClient) client;
        assertThat(httpClient.getBaseURL()).isEqualTo("http://localhost:8983/solr/test-core");
    }

    @Test
    void solrClientNormalizesCoreWithLeadingSlash() {
        SolrConfigurationProperties properties = new SolrConfigurationProperties();
        properties.setBaseUrl("http://localhost:8983/solr");
        properties.setCore("/test-core");
        
        SolrConfig config = new SolrConfig(properties);
        SolrClient client = config.solrClient();
        
        HttpSolrClient httpClient = (HttpSolrClient) client;
        assertThat(httpClient.getBaseURL()).isEqualTo("http://localhost:8983/solr/test-core");
    }

    @Test
    void solrClientHandlesBothTrailingAndLeadingSlashes() {
        SolrConfigurationProperties properties = new SolrConfigurationProperties();
        properties.setBaseUrl("http://localhost:8983/solr/");
        properties.setCore("/test-core");
        
        SolrConfig config = new SolrConfig(properties);
        SolrClient client = config.solrClient();
        
        HttpSolrClient httpClient = (HttpSolrClient) client;
        assertThat(httpClient.getBaseURL()).isEqualTo("http://localhost:8983/solr/test-core");
    }
}
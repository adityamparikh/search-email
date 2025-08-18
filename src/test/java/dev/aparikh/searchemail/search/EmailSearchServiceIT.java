package dev.aparikh.searchemail.search;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.impl.HttpSolrClient;
import org.apache.solr.client.solrj.request.schema.SchemaRequest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.SolrContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EmailSearchServiceIT {

    private static String solrBaseUrl() {
        return "http://" + SOLR.getHost() + ":" + SOLR.getMappedPort(8983) + "/solr";
    }

    private static final String CORE = "emails";

    @Container
    static final SolrContainer SOLR = new SolrContainer(DockerImageName.parse("solr:9.6.1"));

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("app.solr.base-url", EmailSearchServiceIT::solrBaseUrl);
        registry.add("app.solr.core", () -> CORE);
    }

    @BeforeAll
    static void createCoreAndSchema() throws Exception {
        System.out.println("[DEBUG_LOG] Solr base URL: " + solrBaseUrl());
        System.out.println("[DEBUG_LOG] Solr core: " + CORE);
        
        // Wait for Solr to be available and create the collection (Solr is running in SolrCloud mode)
        var result = SOLR.execInContainer("solr", "create_collection", "-c", CORE, "-shards", "1", "-replicationFactor", "1");
        System.out.println("[DEBUG_LOG] Core creation result: " + result.getStdout());
        if (!result.getStdout().contains("Created collection") && !result.getStdout().contains("already exists")) {
            System.out.println("[DEBUG_LOG] Core creation stderr: " + result.getStderr());
        }
        
        // Wait for the core to be ready and verify it exists
        Thread.sleep(2000);
        
        // Test if core is accessible
        boolean coreReady = false;
        for (int i = 0; i < 10; i++) {
            try (SolrClient testClient = new HttpSolrClient.Builder(solrBaseUrl() + "/" + CORE).build()) {
                SolrQuery testQuery = new SolrQuery("*:*");
                testQuery.setRows(0);
                testClient.query(testQuery);
                coreReady = true;
                System.out.println("[DEBUG_LOG] Core is accessible!");
                break;
            } catch (Exception e) {
                System.out.println("[DEBUG_LOG] Core not ready yet, attempt " + (i + 1) + ": " + e.getMessage());
                Thread.sleep(1000);
            }
        }
        
        if (!coreReady) {
            throw new RuntimeException("Core " + CORE + " not accessible after 10 attempts");
        }
        
        // Now configure the schema
        try (SolrClient core = new HttpSolrClient.Builder(solrBaseUrl() + "/" + CORE).build()) {
            addField(core, "id", Map.of("type", "string", "stored", true, "indexed", true, "required", true));
            addField(core, "subject", Map.of("type", "text_general", "stored", true, "indexed", true));
            addField(core, "body", Map.of("type", "text_general", "stored", true, "indexed", true));
            addField(core, "from_addr", Map.of("type", "string", "stored", true, "indexed", true));
            addField(core, "to_addr", Map.of("type", "string", "stored", true, "indexed", true, "multiValued", true));
            addField(core, "cc_addr", Map.of("type", "string", "stored", true, "indexed", true, "multiValued", true));
            addField(core, "bcc_addr", Map.of("type", "string", "stored", true, "indexed", true, "multiValued", true));
            addField(core, "sent_at", Map.of("type", "pdate", "stored", true, "indexed", true));
        }
    }

    private static void addField(SolrClient core, String name, Map<String, Object> props) {
        Map<String, Object> field = new HashMap<>(props);
        field.put("name", name);
        try {
            new SchemaRequest.AddField(field).process(core);
        } catch (Exception ignore) {
            // ignore errors if field exists to keep test idempotent
        }
    }

    @Autowired
    private EmailSearchService service;

    @Autowired
    private SolrClient solrClient;

    @BeforeEach
    void cleanIndex() throws Exception {
        System.out.println("[DEBUG_LOG] SolrClient type: " + solrClient.getClass());
        if (solrClient instanceof HttpSolrClient http) {
            System.out.println("[DEBUG_LOG] HttpSolrClient base URL: " + http.getBaseURL());
        }
        // Core should be ready by now, but add a small retry logic for cleanup
        for (int i = 0; i < 5; i++) {
            try {
                solrClient.deleteByQuery("*:*");
                solrClient.commit();
                break;
            } catch (Exception e) {
                if (i == 4) throw e; // Last attempt failed
                Thread.sleep(1000);
            }
        }
    }

    @Test
    void bccVisibleOnlyWhenAdminFirmMatchesParticipantDomain() {
        Instant now = Instant.parse("2025-01-01T10:15:30Z");
        EmailDocument bccAcme = new EmailDocument(
                "1","s","b","sender@corp.com",
                List.of(), List.of(), List.of("alice@acme.com"), now
        );
        EmailDocument bccOther = new EmailDocument(
                "2","s","b","sender@corp.com",
                List.of(), List.of(), List.of("bob@other.com"), now
        );
        service.indexAll(List.of(bccAcme, bccOther));

        SearchQuery q1 = new SearchQuery(now.minusSeconds(3600), now.plusSeconds(3600), "alice@acme.com", "acme.com");
        List<EmailDocument> r1 = service.search(q1);
        assertThat(r1).extracting(EmailDocument::id).contains("1");

        SearchQuery q2 = new SearchQuery(now.minusSeconds(3600), now.plusSeconds(3600), "bob@other.com", "acme.com");
        List<EmailDocument> r2 = service.search(q2);
        assertThat(r2).extracting(EmailDocument::id).doesNotContain("2");
    }

    @Test
    void toAndCcAlwaysVisibleRegardlessOfAdminFirm() {
        Instant now = Instant.parse("2025-01-01T10:15:30Z");
        EmailDocument toDoc = new EmailDocument(
                "3","s","b","sender@corp.com",
                List.of("bob@other.com"), List.of(), List.of(), now
        );
        EmailDocument ccDoc = new EmailDocument(
                "4","s","b","sender@corp.com",
                List.of(), List.of("bob@other.com"), List.of(), now
        );
        service.indexAll(List.of(toDoc, ccDoc));

        SearchQuery q = new SearchQuery(now.minusSeconds(3600), now.plusSeconds(3600), "bob@other.com", "acme.com");
        List<EmailDocument> r = service.search(q);
        assertThat(r).extracting(EmailDocument::id).contains("3", "4");
    }

    @Test
    void dateRangeIsMandatoryAndFiltersResults() {
        Instant now = Instant.parse("2025-01-01T10:15:30Z");
        EmailDocument d = new EmailDocument(
                "5","s","b","sender@corp.com",
                List.of("x@y.com"), List.of(), List.of(), now
        );
        service.index(d);

        SearchQuery q = new SearchQuery(now.plusSeconds(3600), now.plusSeconds(7200), "x@y.com", "y.com");
        List<EmailDocument> r = service.search(q);
        assertThat(r).isEmpty();
    }
}

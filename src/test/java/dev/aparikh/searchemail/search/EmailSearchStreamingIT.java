package dev.aparikh.searchemail.search;

import dev.aparikh.searchemail.api.StreamSearchRequest;
import dev.aparikh.searchemail.indexing.EmailIndexService;
import dev.aparikh.searchemail.model.EmailDocument;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.impl.HttpSolrClient;
import org.apache.solr.client.solrj.request.schema.SchemaRequest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.SolrContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EmailSearchStreamingIT {

    @Container
    static final SolrContainer SOLR = new SolrContainer(DockerImageName.parse("solr:9.6.1"));
    private static final String CORE = "emails";
    @Autowired
    private WebTestClient webTestClient;
    @Autowired
    private EmailIndexService indexService;
    @Autowired
    private SolrClient solrClient;
    @Autowired
    private EmailSearchService searchService;

    private static String solrBaseUrl() {
        return "http://" + SOLR.getHost() + ":" + SOLR.getMappedPort(8983) + "/solr";
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("solr.base-url", EmailSearchStreamingIT::solrBaseUrl);
        registry.add("solr.core", () -> CORE);
    }

    @BeforeAll
    static void createCoreAndSchema() throws Exception {
        // Create collection
        var result = SOLR.execInContainer("solr", "create_collection", "-c", CORE, "-shards", "1", "-replicationFactor", "1");
        if (!result.getStdout().contains("Created collection") && !result.getStdout().contains("already exists")) {
            System.out.println("Core creation stderr: " + result.getStderr());
        }

        Thread.sleep(2000);

        // Wait for core to be ready
        boolean coreReady = false;
        for (int i = 0; i < 10; i++) {
            try (SolrClient testClient = new HttpSolrClient.Builder(solrBaseUrl() + "/" + CORE).build()) {
                SolrQuery testQuery = new SolrQuery("*:*");
                testQuery.setRows(0);
                testClient.query(testQuery);
                coreReady = true;
                break;
            } catch (Exception e) {
                Thread.sleep(1000);
            }
        }

        if (!coreReady) {
            throw new RuntimeException("Core " + CORE + " not accessible after 10 attempts");
        }

        // Configure schema
        try (SolrClient core = new HttpSolrClient.Builder(solrBaseUrl() + "/" + CORE).build()) {
            addField(core, EmailDocument.FIELD_ID, Map.of("type", "string", "stored", true, "indexed", true, "required", true));
            addField(core, EmailDocument.FIELD_SUBJECT, Map.of("type", "text_general", "stored", true, "indexed", true));
            addField(core, EmailDocument.FIELD_BODY, Map.of("type", "text_general", "stored", true, "indexed", true));
            addField(core, EmailDocument.FIELD_FROM, Map.of("type", "string", "stored", true, "indexed", true));
            addField(core, EmailDocument.FIELD_TO, Map.of("type", "string", "stored", true, "indexed", true, "multiValued", true));
            addField(core, EmailDocument.FIELD_CC, Map.of("type", "string", "stored", true, "indexed", true, "multiValued", true));
            addField(core, EmailDocument.FIELD_BCC, Map.of("type", "string", "stored", true, "indexed", true, "multiValued", true));
            addField(core, EmailDocument.FIELD_SENT_AT, Map.of("type", "pdate", "stored", true, "indexed", true));
        }
    }

    private static void addField(SolrClient core, String name, Map<String, Object> props) {
        Map<String, Object> field = new HashMap<>(props);
        field.put("name", name);
        try {
            new SchemaRequest.AddField(field).process(core);
        } catch (Exception ignore) {
            // ignore errors if field exists
        }
    }

    @BeforeEach
    void cleanIndex() throws Exception {
        for (int i = 0; i < 5; i++) {
            try {
                solrClient.deleteByQuery("*:*");
                solrClient.commit();
                break;
            } catch (Exception e) {
                if (i == 4) throw e;
                Thread.sleep(1000);
            }
        }
    }

    @Test
    void streamingSearchReturnsEmailsInBatches() {
        Instant now = Instant.parse("2025-01-01T10:00:00Z");

        // Index 5 emails
        List<EmailDocument> emails = List.of(
                new EmailDocument("1", "Email 1", "Content 1", "sender@test.com", List.of("user@test.com"), List.of(), List.of(), now),
                new EmailDocument("2", "Email 2", "Content 2", "sender@test.com", List.of("user@test.com"), List.of(), List.of(), now.plusSeconds(60)),
                new EmailDocument("3", "Email 3", "Content 3", "sender@test.com", List.of("user@test.com"), List.of(), List.of(), now.plusSeconds(120)),
                new EmailDocument("4", "Email 4", "Content 4", "sender@test.com", List.of("user@test.com"), List.of(), List.of(), now.plusSeconds(180)),
                new EmailDocument("5", "Email 5", "Content 5", "sender@test.com", List.of("user@test.com"), List.of(), List.of(), now.plusSeconds(240))
        );
        indexService.indexAll(emails);

        // Test streaming with small batch size
        SearchQuery streamQuery = new SearchQuery(
                now.minusSeconds(3600),
                now.plusSeconds(3600),
                null,
                List.of("user@test.com"),
                "test.com",
                0,
                1000,
                null
        );

        StepVerifier.create(searchService.searchStream(streamQuery, 2))
                .expectNextCount(5)
                .verifyComplete();
    }

    @Test
    void streamingEndpointWorksViaHttpApi() {
        Instant now = Instant.parse("2025-01-01T10:00:00Z");

        // Index some test emails
        List<EmailDocument> emails = List.of(
                new EmailDocument("1", "Test 1", "Body 1", "sender@test.com", List.of("recipient@test.com"), List.of(), List.of(), now),
                new EmailDocument("2", "Test 2", "Body 2", "sender@test.com", List.of("recipient@test.com"), List.of(), List.of(), now.plusSeconds(60))
        );
        indexService.indexAll(emails);

        StreamSearchRequest request = new StreamSearchRequest(
                now.minusSeconds(3600),
                now.plusSeconds(3600),
                null,
                List.of("recipient@test.com"),
                "test.com",
                100
        );

        webTestClient.post()
                .uri("/api/emails/stream")
                .bodyValue(request)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType("text/event-stream")
                .expectBody()
                .consumeWith(result -> {
                    String body = new String(result.getResponseBody());
                    // Basic verification that we get SSE format with email data
                    assert body.contains("data:");
                    assert body.contains("\"id\":\"1\"") || body.contains("\"id\":\"2\"");
                });
    }
}
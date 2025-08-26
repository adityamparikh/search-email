package dev.aparikh.searchemail.search;

import dev.aparikh.searchemail.api.HitCountResponse;
import dev.aparikh.searchemail.api.SearchRequest;
import dev.aparikh.searchemail.api.SearchResponse;
import dev.aparikh.searchemail.indexing.EmailIndexService;
import dev.aparikh.searchemail.model.EmailDocument;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.impl.HttpSolrClient;
import org.apache.solr.client.solrj.request.schema.SchemaRequest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

/**
 * Integration tests to verify hit count accuracy across different scenarios and endpoints.
 * Tests the consistency between different ways of getting counts:
 * 1. /api/emails/count endpoint
 * 2. /api/emails/search totalCount field (without faceting - uses separate getHitCount call)
 * 3. /api/emails/search totalCount field (with faceting - uses single query)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class HitCountAccuracyIT {

    @Container
    static final SolrContainer solrContainer = new SolrContainer(DockerImageName.parse("solr:9.6.1"));

    private static final String CORE = "emails";

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private EmailIndexService indexService;

    @Autowired
    private EmailSearchService searchService;

    @Autowired
    private SolrClient solrClient;

    static String solrBaseUrl() {
        return "http://" + solrContainer.getHost() + ":" + solrContainer.getMappedPort(8983) + "/solr";
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("solr.base-url", HitCountAccuracyIT::solrBaseUrl);
        registry.add("solr.core", () -> CORE);
    }

    @BeforeAll
    static void createCoreAndSchema() throws Exception {
        var result = solrContainer.execInContainer("solr", "create_collection", "-c", CORE, "-shards", "1", "-replicationFactor", "1");
        if (!result.getStdout().contains("Created collection") && !result.getStdout().contains("already exists")) {
            System.out.println("Core creation stderr: " + result.getStderr());
        }

        Thread.sleep(2000);

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
            // ignore errors if field exists to keep test idempotent
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
    void hitCountConsistencyBetweenEndpoints() {
        System.out.println("[DEBUG_LOG] Testing hit count consistency between /count and /search endpoints");

        Instant now = Instant.parse("2025-01-01T10:00:00Z");

        // Create test data with various scenarios
        List<EmailDocument> testEmails = List.of(
                new EmailDocument("1", "Meeting", "Team meeting", "alice@acme.com",
                        List.of("bob@acme.com"), List.of(), List.of(), now),
                new EmailDocument("2", "Update", "Status update", "charlie@acme.com",
                        List.of("alice@acme.com"), List.of(), List.of(), now.plusSeconds(60)),
                new EmailDocument("3", "Report", "Monthly report", "dave@other.com",
                        List.of("alice@acme.com"), List.of("bob@acme.com"), List.of(), now.plusSeconds(120)),
                new EmailDocument("4", "BCC Test", "BCC email", "sender@corp.com",
                        List.of("recipient@corp.com"), List.of(), List.of("alice@acme.com"), now.plusSeconds(180)),
                new EmailDocument("5", "External", "External communication", "external@other.com",
                        List.of("other@other.com"), List.of(), List.of(), now.plusSeconds(240))
        );

        indexService.indexAll(testEmails);

        SearchRequest request = new SearchRequest(
                now.minusSeconds(3600),
                now.plusSeconds(3600),
                null, // No query filter
                List.of("alice@acme.com"), // Search for Alice
                "acme.com",
                0, 100,
                null, // No faceting
                null,
                null
        );

        // Test 1: Get count from /count endpoint
        ResponseEntity<HitCountResponse> countResponse = restTemplate.postForEntity(
                "http://localhost:" + port + "/api/emails/count", request, HitCountResponse.class);

        assertThat(countResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        long countEndpointResult = countResponse.getBody().count();
        System.out.println("[DEBUG_LOG] Count endpoint result: " + countEndpointResult);

        // Test 2: Get count from /search endpoint (without faceting - uses separate getHitCount call)
        ResponseEntity<SearchResponse> searchResponse = restTemplate.postForEntity(
                "http://localhost:" + port + "/api/emails/search", request, SearchResponse.class);

        assertThat(searchResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        long searchEndpointResult = searchResponse.getBody().totalCount();
        System.out.println("[DEBUG_LOG] Search endpoint result (no faceting): " + searchEndpointResult);
        System.out.println("[DEBUG_LOG] Actual emails returned: " + searchResponse.getBody().emails().size());

        // Test 3: Get count from /search endpoint with faceting (uses single query)
        SearchRequest facetedRequest = new SearchRequest(
                now.minusSeconds(3600),
                now.plusSeconds(3600),
                null,
                List.of("alice@acme.com"),
                "acme.com",
                0, 100,
                List.of("from_addr"), // Enable faceting
                null,
                null
        );

        ResponseEntity<SearchResponse> facetedSearchResponse = restTemplate.postForEntity(
                "http://localhost:" + port + "/api/emails/search", facetedRequest, SearchResponse.class);

        assertThat(facetedSearchResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        long facetedSearchEndpointResult = facetedSearchResponse.getBody().totalCount();
        System.out.println("[DEBUG_LOG] Search endpoint result (with faceting): " + facetedSearchEndpointResult);

        // Test 4: Direct service call for comparison
        SearchQuery serviceQuery = new SearchQuery(
                now.minusSeconds(3600),
                now.plusSeconds(3600),
                null,
                List.of("alice@acme.com"),
                "acme.com",
                0, 100,
                null,
                null
        );

        long serviceResult = searchService.getHitCount(serviceQuery);
        System.out.println("[DEBUG_LOG] Direct service call result: " + serviceResult);

        // All counts should be identical
        assertThat(countEndpointResult).isEqualTo(searchEndpointResult);
        assertThat(countEndpointResult).isEqualTo(facetedSearchEndpointResult);
        assertThat(countEndpointResult).isEqualTo(serviceResult);

        // Expected count should be 4 (emails 1, 2, 3, 4 involve alice@acme.com)
        assertThat(countEndpointResult).isEqualTo(4);

        System.out.println("[DEBUG_LOG] ✅ Hit count consistency test passed - all endpoints return same count");
    }

    @Test
    void hitCountAccuracyInCrossFirmBccScenarios() {
        System.out.println("[DEBUG_LOG] Testing hit count accuracy in cross-firm BCC scenarios");

        Instant now = Instant.parse("2025-01-01T10:00:00Z");

        List<EmailDocument> testEmails = List.of(
                // Email 1: Alice in FROM - should be visible
                new EmailDocument("1", "From Alice", "Email from Alice", "alice@acme.com",
                        List.of("bob@other.com"), List.of(), List.of(), now),

                // Email 2: Alice in TO - should be visible  
                new EmailDocument("2", "To Alice", "Email to Alice", "sender@other.com",
                        List.of("alice@acme.com"), List.of(), List.of(), now.plusSeconds(60)),

                // Email 3: Alice in CC - should be visible
                new EmailDocument("3", "CC Alice", "Email CC Alice", "sender@other.com",
                        List.of("recipient@other.com"), List.of("alice@acme.com"), List.of(), now.plusSeconds(120)),

                // Email 4: Alice in BCC (same domain as admin) - should be visible
                new EmailDocument("4", "BCC Alice Same Domain", "Email BCC Alice", "sender@other.com",
                        List.of("recipient@other.com"), List.of(), List.of("alice@acme.com"), now.plusSeconds(180)),

                // Email 5: Bob from other firm in BCC, Alice not involved - should NOT be visible to acme.com admin
                new EmailDocument("5", "BCC Other Domain", "Email BCC other", "sender@corp.com",
                        List.of("recipient@corp.com"), List.of(), List.of("bob@other.com"), now.plusSeconds(240)),

                // Email 6: Mixed BCC - both Alice (acme.com) and Bob (other.com) in BCC
                new EmailDocument("6", "Mixed BCC", "Mixed BCC email", "sender@corp.com",
                        List.of("recipient@corp.com"), List.of(), List.of("alice@acme.com", "bob@other.com"), now.plusSeconds(300))
        );

        indexService.indexAll(testEmails);

        // Test searching for Alice from acme.com admin perspective
        SearchRequest aliceRequest = new SearchRequest(
                now.minusSeconds(3600),
                now.plusSeconds(3600),
                null,
                List.of("alice@acme.com"),
                "acme.com", // Admin from acme.com
                0, 100,
                null,
                null,
                null
        );

        // Get counts from different methods
        ResponseEntity<HitCountResponse> aliceCountResponse = restTemplate.postForEntity(
                "http://localhost:" + port + "/api/emails/count", aliceRequest, HitCountResponse.class);

        ResponseEntity<SearchResponse> aliceSearchResponse = restTemplate.postForEntity(
                "http://localhost:" + port + "/api/emails/search", aliceRequest, SearchResponse.class);

        long aliceCount = aliceCountResponse.getBody().count();
        long aliceSearchTotalCount = aliceSearchResponse.getBody().totalCount();
        int aliceActualResults = aliceSearchResponse.getBody().emails().size();

        System.out.println("[DEBUG_LOG] Alice search - Count endpoint: " + aliceCount);
        System.out.println("[DEBUG_LOG] Alice search - Search totalCount: " + aliceSearchTotalCount);
        System.out.println("[DEBUG_LOG] Alice search - Actual results: " + aliceActualResults);

        // Should find emails 1, 2, 3, 4, 6 (Alice involved in all these)
        assertThat(aliceCount).isEqualTo(aliceSearchTotalCount);
        assertThat(aliceCount).isEqualTo(5);

        // Test searching for Bob from acme.com admin perspective
        SearchRequest bobRequest = new SearchRequest(
                now.minusSeconds(3600),
                now.plusSeconds(3600),
                null,
                List.of("bob@other.com"),
                "acme.com", // Admin from acme.com
                0, 100,
                null,
                null,
                null
        );

        ResponseEntity<HitCountResponse> bobCountResponse = restTemplate.postForEntity(
                "http://localhost:" + port + "/api/emails/count", bobRequest, HitCountResponse.class);

        ResponseEntity<SearchResponse> bobSearchResponse = restTemplate.postForEntity(
                "http://localhost:" + port + "/api/emails/search", bobRequest, SearchResponse.class);

        long bobCount = bobCountResponse.getBody().count();
        long bobSearchTotalCount = bobSearchResponse.getBody().totalCount();
        int bobActualResults = bobSearchResponse.getBody().emails().size();

        System.out.println("[DEBUG_LOG] Bob search - Count endpoint: " + bobCount);
        System.out.println("[DEBUG_LOG] Bob search - Search totalCount: " + bobSearchTotalCount);
        System.out.println("[DEBUG_LOG] Bob search - Actual results: " + bobActualResults);

        // Bob is in TO field of email 1, BCC of email 5, and BCC of email 6
        // But email 5 should NOT be visible because Bob's domain != acme.com and he's only in BCC
        // Only email 1 should be visible (Bob in TO field)
        assertThat(bobCount).isEqualTo(bobSearchTotalCount);
        assertThat(bobCount).isEqualTo(1);

        System.out.println("[DEBUG_LOG] ✅ Cross-firm BCC hit count accuracy test passed");
    }

    @Test
    void hitCountAccuracyWithQueryFiltering() {
        System.out.println("[DEBUG_LOG] Testing hit count accuracy with query filtering");

        Instant now = Instant.parse("2025-01-01T10:00:00Z");

        List<EmailDocument> testEmails = List.of(
                new EmailDocument("1", "Important Meeting", "Urgent meeting discussion", "alice@acme.com",
                        List.of("bob@acme.com"), List.of(), List.of(), now),
                new EmailDocument("2", "Meeting Update", "Meeting status update", "charlie@acme.com",
                        List.of("alice@acme.com"), List.of(), List.of(), now.plusSeconds(60)),
                new EmailDocument("3", "Status Report", "Weekly status report", "alice@acme.com",
                        List.of("manager@acme.com"), List.of(), List.of(), now.plusSeconds(120)),
                new EmailDocument("4", "Important Notice", "Important company notice", "hr@acme.com",
                        List.of("alice@acme.com", "bob@acme.com"), List.of(), List.of(), now.plusSeconds(180))
        );

        indexService.indexAll(testEmails);

        // Test with query filter for "meeting"
        SearchRequest meetingRequest = new SearchRequest(
                now.minusSeconds(3600),
                now.plusSeconds(3600),
                "subject:Meeting", // Query filter using proper Solr syntax
                List.of("alice@acme.com"),
                "acme.com",
                0, 100,
                null,
                null,
                null
        );

        ResponseEntity<HitCountResponse> meetingCountResponse = restTemplate.postForEntity(
                "http://localhost:" + port + "/api/emails/count", meetingRequest, HitCountResponse.class);

        ResponseEntity<SearchResponse> meetingSearchResponse = restTemplate.postForEntity(
                "http://localhost:" + port + "/api/emails/search", meetingRequest, SearchResponse.class);

        long meetingCount = meetingCountResponse.getBody().count();
        long meetingSearchTotalCount = meetingSearchResponse.getBody().totalCount();

        System.out.println("[DEBUG_LOG] Meeting query - Count endpoint: " + meetingCount);
        System.out.println("[DEBUG_LOG] Meeting query - Search totalCount: " + meetingSearchTotalCount);

        // Should find emails 1 and 2 (both contain "meeting" and involve Alice)
        assertThat(meetingCount).isEqualTo(meetingSearchTotalCount);
        assertThat(meetingCount).isEqualTo(2);

        // Test with query filter for "important"
        SearchRequest importantRequest = new SearchRequest(
                now.minusSeconds(3600),
                now.plusSeconds(3600),
                "subject:Important", // Query filter using proper Solr syntax
                List.of("alice@acme.com"),
                "acme.com",
                0, 100,
                null,
                null,
                null
        );

        ResponseEntity<HitCountResponse> importantCountResponse = restTemplate.postForEntity(
                "http://localhost:" + port + "/api/emails/count", importantRequest, HitCountResponse.class);

        ResponseEntity<SearchResponse> importantSearchResponse = restTemplate.postForEntity(
                "http://localhost:" + port + "/api/emails/search", importantRequest, SearchResponse.class);

        long importantCount = importantCountResponse.getBody().count();
        long importantSearchTotalCount = importantSearchResponse.getBody().totalCount();

        System.out.println("[DEBUG_LOG] Important query - Count endpoint: " + importantCount);
        System.out.println("[DEBUG_LOG] Important query - Search totalCount: " + importantSearchTotalCount);

        // Should find emails 1 and 4 (both contain "important" and involve Alice)
        assertThat(importantCount).isEqualTo(importantSearchTotalCount);
        assertThat(importantCount).isEqualTo(2);

        System.out.println("[DEBUG_LOG] ✅ Query filtering hit count accuracy test passed");
    }

    @Test
    void hitCountAccuracyWithFacetingVsNonFaceting() {
        System.out.println("[DEBUG_LOG] Testing hit count accuracy between faceted and non-faceted searches");

        Instant now = Instant.parse("2025-01-01T10:00:00Z");

        List<EmailDocument> testEmails = List.of(
                new EmailDocument("1", "Test1", "Content1", "alice@acme.com",
                        List.of("bob@acme.com"), List.of(), List.of(), now),
                new EmailDocument("2", "Test2", "Content2", "charlie@acme.com",
                        List.of("alice@acme.com"), List.of(), List.of(), now.plusSeconds(60)),
                new EmailDocument("3", "Test3", "Content3", "dave@acme.com",
                        List.of("alice@acme.com", "bob@acme.com"), List.of(), List.of(), now.plusSeconds(120))
        );

        indexService.indexAll(testEmails);

        SearchRequest baseRequest = new SearchRequest(
                now.minusSeconds(3600),
                now.plusSeconds(3600),
                null,
                List.of("alice@acme.com"),
                "acme.com",
                0, 100,
                null, // No faceting
                null,
                null
        );

        // Non-faceted search (uses separate getHitCount call)
        ResponseEntity<SearchResponse> nonFacetedResponse = restTemplate.postForEntity(
                "http://localhost:" + port + "/api/emails/search", baseRequest, SearchResponse.class);

        // Faceted search (uses single query with count from same response)
        SearchRequest facetedRequest = new SearchRequest(
                now.minusSeconds(3600),
                now.plusSeconds(3600),
                null,
                List.of("alice@acme.com"),
                "acme.com",
                0, 100,
                List.of("from_addr"), // Enable faceting
                null,
                null
        );

        ResponseEntity<SearchResponse> facetedResponse = restTemplate.postForEntity(
                "http://localhost:" + port + "/api/emails/search", facetedRequest, SearchResponse.class);

        long nonFacetedCount = nonFacetedResponse.getBody().totalCount();
        long facetedCount = facetedResponse.getBody().totalCount();

        System.out.println("[DEBUG_LOG] Non-faceted search count: " + nonFacetedCount);
        System.out.println("[DEBUG_LOG] Faceted search count: " + facetedCount);

        // Both should return the same count
        assertThat(nonFacetedCount).isEqualTo(facetedCount);
        assertThat(nonFacetedCount).isEqualTo(3); // All 3 emails involve Alice

        // Verify faceting data is present in faceted response
        assertThat(facetedResponse.getBody().facets()).isNotNull();
        assertThat(facetedResponse.getBody().facets()).containsKey("from_addr");

        System.out.println("[DEBUG_LOG] ✅ Faceted vs non-faceted hit count consistency test passed");
    }
}
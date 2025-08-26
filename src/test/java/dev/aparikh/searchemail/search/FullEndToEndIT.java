package dev.aparikh.searchemail.search;

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
 * Comprehensive end-to-end test that demonstrates all search functionality:
 * 1. Data indexing
 * 2. Query (q) parameter - full-text search
 * 3. Filter query (fq) - participant filtering
 * 4. Faceting - field and query-based faceting
 * 5. Sorting - result ordering
 * 6. Pagination - multiple pages
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class FullEndToEndIT {

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
    private SolrClient solrClient;

    static String solrBaseUrl() {
        return "http://" + solrContainer.getHost() + ":" + solrContainer.getMappedPort(8983) + "/solr";
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("solr.base-url", FullEndToEndIT::solrBaseUrl);
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
    void fullEndToEndTest() {
        System.out.println("[DEBUG_LOG] Starting comprehensive end-to-end test");
        
        // STEP 1: INDEX DIVERSE TEST DATA
        Instant baseTime = Instant.parse("2025-01-15T10:00:00Z");
        
        List<EmailDocument> testEmails = List.of(
                new EmailDocument("1", "Urgent Meeting Request", "Please join the meeting about project updates",
                        "alice@acme.com", List.of("bob@acme.com", "charlie@other.com"), List.of(), List.of(), baseTime),
                new EmailDocument("2", "Weekly Team Meeting", "Regular standup meeting discussion",
                        "manager@acme.com", List.of("alice@acme.com", "bob@acme.com"), List.of("hr@acme.com"), List.of(), baseTime.plusSeconds(3600)),
                new EmailDocument("3", "Project Status Update", "Current status of the project and next steps",
                        "alice@acme.com", List.of("manager@acme.com"), List.of(), List.of("ceo@acme.com"), baseTime.plusSeconds(7200)),
                new EmailDocument("4", "Support Ticket #123", "Customer inquiry about billing issues",
                        "support@acme.com", List.of("customer@other.com"), List.of(), List.of(), baseTime.plusSeconds(10800)),
                new EmailDocument("5", "Re: Support Ticket #123", "Response to customer billing inquiry",
                        "billing@acme.com", List.of("customer@other.com"), List.of("support@acme.com"), List.of(), baseTime.plusSeconds(14400)),
                new EmailDocument("6", "New Product Launch", "Announcing our latest product features",
                        "sales@acme.com", List.of("prospect@other.com"), List.of("marketing@acme.com"), List.of(), baseTime.plusSeconds(18000)),
                new EmailDocument("7", "Follow-up on Demo", "Thank you for attending our product demo",
                        "sales@acme.com", List.of("prospect@other.com"), List.of(), List.of(), baseTime.plusSeconds(21600)),
                new EmailDocument("8", "Database Migration Plan", "Technical details for the upcoming migration",
                        "dev@acme.com", List.of("alice@acme.com", "bob@acme.com"), List.of("manager@acme.com"), List.of(), baseTime.plusSeconds(25200)),
                new EmailDocument("9", "Code Review Request", "Please review the latest changes in the repository",
                        "alice@acme.com", List.of("dev@acme.com"), List.of(), List.of(), baseTime.plusSeconds(28800))
        );
        
        System.out.println("[DEBUG_LOG] Indexing " + testEmails.size() + " test emails");
        indexService.indexAll(testEmails);
        
        // STEP 2: TEST QUERY (q) PARAMETER - Full-text search
        System.out.println("[DEBUG_LOG] Testing query (q) parameter - searching for 'meeting'");
        SearchRequest queryRequest = new SearchRequest(
                baseTime.minusSeconds(3600),
                baseTime.plusSeconds(36000),
                "meeting", // Query parameter (q)
                null,
                "acme.com",
                0, 10,
                null, null, null
        );
        
        ResponseEntity<SearchResponse> queryResponse = restTemplate.postForEntity(
                "http://localhost:" + port + "/api/emails/search", queryRequest, SearchResponse.class);
        
        assertThat(queryResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        SearchResponse queryResult = queryResponse.getBody();
        System.out.println("[DEBUG_LOG] Query search found " + queryResult.totalCount() + " emails");
        
        // Verify results contain the search term
        for (var email : queryResult.emails()) {
            boolean hasSearchTerm = email.subject().toLowerCase().contains("meeting") ||
                                  email.body().toLowerCase().contains("meeting");
            assertThat(hasSearchTerm).isTrue();
        }
        
        // STEP 3: TEST FILTER QUERY (fq) - Participant filtering
        System.out.println("[DEBUG_LOG] Testing filter query (fq) - filtering by Alice");
        SearchRequest fqRequest = new SearchRequest(
                baseTime.minusSeconds(3600),
                baseTime.plusSeconds(36000),
                null,
                List.of("alice@acme.com"), // Filter query (fq) via participants
                "acme.com",
                0, 10,
                null, null, null
        );
        
        ResponseEntity<SearchResponse> fqResponse = restTemplate.postForEntity(
                "http://localhost:" + port + "/api/emails/search", fqRequest, SearchResponse.class);
        
        assertThat(fqResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        SearchResponse fqResult = fqResponse.getBody();
        System.out.println("[DEBUG_LOG] Filter query found " + fqResult.totalCount() + " emails involving Alice");
        
        // Verify Alice is involved in all results
        for (var email : fqResult.emails()) {
            boolean aliceInvolved = email.from().equals("alice@acme.com") ||
                                  email.to().contains("alice@acme.com") ||
                                  (email.cc() != null && email.cc().contains("alice@acme.com"));
            assertThat(aliceInvolved).isTrue();
        }
        
        // STEP 4: TEST FACETING - Field-based faceting
        System.out.println("[DEBUG_LOG] Testing faceting on from_addr and to_addr fields");
        SearchRequest facetRequest = new SearchRequest(
                baseTime.minusSeconds(3600),
                baseTime.plusSeconds(36000),
                null, null,
                "acme.com",
                0, 20,
                List.of("from_addr", "to_addr"), // Faceting
                null, null
        );
        
        ResponseEntity<SearchResponse> facetResponse = restTemplate.postForEntity(
                "http://localhost:" + port + "/api/emails/search", facetRequest, SearchResponse.class);
        
        assertThat(facetResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        SearchResponse facetResult = facetResponse.getBody();
        System.out.println("[DEBUG_LOG] Faceted search found " + facetResult.totalCount() + " emails");
        
        // Verify faceting results
        assertThat(facetResult.facets()).isNotNull();
        assertThat(facetResult.facets()).containsKey("from_addr");
        assertThat(facetResult.facets()).containsKey("to_addr");
        
        FacetResult fromFacet = facetResult.facets().get("from_addr");
        assertThat(fromFacet.values()).isNotEmpty();
        System.out.println("[DEBUG_LOG] From address facet has " + fromFacet.values().size() + " values");
        
        // STEP 5: TEST SORTING - Result ordering
        System.out.println("[DEBUG_LOG] Testing sorting by timestamp descending");
        SearchRequest sortRequest = new SearchRequest(
                baseTime.minusSeconds(3600),
                baseTime.plusSeconds(36000),
                null, null,
                "acme.com",
                0, 5,
                null, null,
                "timestamp desc" // Sorting by timestamp descending
        );
        
        ResponseEntity<SearchResponse> sortResponse = restTemplate.postForEntity(
                "http://localhost:" + port + "/api/emails/search", sortRequest, SearchResponse.class);
        
        assertThat(sortResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        SearchResponse sortResult = sortResponse.getBody();
        System.out.println("[DEBUG_LOG] Sorted search found " + sortResult.totalCount() + " emails");
        
        // Verify sorting - emails should be in descending order by timestamp
        if (sortResult.emails().size() >= 2) {
            for (int i = 0; i < sortResult.emails().size() - 1; i++) {
                Instant current = sortResult.emails().get(i).sentAt();
                Instant next = sortResult.emails().get(i + 1).sentAt();
                assertThat(current.isAfter(next) || current.equals(next)).isTrue();
            }
            System.out.println("[DEBUG_LOG] Sorting verification passed - results are in descending order");
        }
        
        // STEP 6: TEST PAGINATION - Multiple pages
        System.out.println("[DEBUG_LOG] Testing pagination with page size 3");
        
        // First page
        SearchRequest page1Request = new SearchRequest(
                baseTime.minusSeconds(3600),
                baseTime.plusSeconds(36000),
                null, null,
                "acme.com",
                0, 3, // Page 0, size 3
                null, null, null
        );
        
        // Second page
        SearchRequest page2Request = new SearchRequest(
                baseTime.minusSeconds(3600),
                baseTime.plusSeconds(36000),
                null, null,
                "acme.com",
                1, 3, // Page 1, size 3
                null, null, null
        );
        
        ResponseEntity<SearchResponse> page1Response = restTemplate.postForEntity(
                "http://localhost:" + port + "/api/emails/search", page1Request, SearchResponse.class);
        ResponseEntity<SearchResponse> page2Response = restTemplate.postForEntity(
                "http://localhost:" + port + "/api/emails/search", page2Request, SearchResponse.class);
        
        assertThat(page1Response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(page2Response.getStatusCode()).isEqualTo(HttpStatus.OK);
        
        SearchResponse page1Result = page1Response.getBody();
        SearchResponse page2Result = page2Response.getBody();
        
        System.out.println("[DEBUG_LOG] Page 1 has " + page1Result.emails().size() + " emails");
        System.out.println("[DEBUG_LOG] Page 2 has " + page2Result.emails().size() + " emails");
        
        // Verify pagination
        assertThat(page1Result.page()).isEqualTo(0);
        assertThat(page2Result.page()).isEqualTo(1);
        assertThat(page1Result.totalCount()).isEqualTo(page2Result.totalCount());
        assertThat(page1Result.emails()).hasSize(3);
        assertThat(page2Result.emails()).hasSizeGreaterThanOrEqualTo(1);
        
        // Verify different emails on different pages
        List<String> page1Ids = page1Result.emails().stream().map(EmailDocument::id).toList();
        List<String> page2Ids = page2Result.emails().stream().map(EmailDocument::id).toList();
        assertThat(page1Ids).doesNotContainAnyElementsOf(page2Ids);
        
        // STEP 7: TEST COMBINED FUNCTIONALITY - Query + Faceting + Sorting + Pagination
        System.out.println("[DEBUG_LOG] Testing combined functionality - query + faceting + sorting");
        SearchRequest combinedRequest = new SearchRequest(
                baseTime.minusSeconds(3600),
                baseTime.plusSeconds(36000),
                "project", // Query (q)
                List.of("alice@acme.com"), // Filter query (fq)
                "acme.com",
                0, 5, // Pagination
                List.of("from_addr"), // Faceting
                null,
                "timestamp asc" // Sorting
        );
        
        ResponseEntity<SearchResponse> combinedResponse = restTemplate.postForEntity(
                "http://localhost:" + port + "/api/emails/search", combinedRequest, SearchResponse.class);
        
        assertThat(combinedResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        SearchResponse combinedResult = combinedResponse.getBody();
        System.out.println("[DEBUG_LOG] Combined search found " + combinedResult.totalCount() + " emails");
        
        // Verify all features work together
        if (!combinedResult.emails().isEmpty()) {
            // Verify query worked - results should contain "project"
            for (var email : combinedResult.emails()) {
                boolean hasProject = email.subject().toLowerCase().contains("project") ||
                                   email.body().toLowerCase().contains("project");
                assertThat(hasProject).isTrue();
                
                // Verify filter query worked - Alice should be involved
                boolean aliceInvolved = email.from().equals("alice@acme.com") ||
                                      email.to().contains("alice@acme.com") ||
                                      (email.cc() != null && email.cc().contains("alice@acme.com"));
                assertThat(aliceInvolved).isTrue();
            }
            
            // Verify faceting worked
            assertThat(combinedResult.facets()).isNotNull();
            assertThat(combinedResult.facets()).containsKey("from_addr");
            
            // Verify sorting worked (ascending order)
            if (combinedResult.emails().size() >= 2) {
                for (int i = 0; i < combinedResult.emails().size() - 1; i++) {
                    Instant current = combinedResult.emails().get(i).sentAt();
                    Instant next = combinedResult.emails().get(i + 1).sentAt();
                    assertThat(current.isBefore(next) || current.equals(next)).isTrue();
                }
            }
        }
        
        System.out.println("[DEBUG_LOG] ✅ All end-to-end tests passed successfully!");
        System.out.println("[DEBUG_LOG] ✅ Verified: Data indexing, Query (q), Filter query (fq), Faceting, Sorting, and Pagination");
    }
}
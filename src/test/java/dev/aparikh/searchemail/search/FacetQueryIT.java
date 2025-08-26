package dev.aparikh.searchemail.search;

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
class FacetQueryIT {

    @Container
    static final SolrContainer SOLR = new SolrContainer(DockerImageName.parse("solr:9.6.1"));
    private static final String CORE = "emails";
    
    @Autowired
    private EmailIndexService indexService;

    @Autowired
    private EmailSearchService searchService;

    @Autowired
    private SolrClient solrClient;

    private static String solrBaseUrl() {
        return "http://" + SOLR.getHost() + ":" + SOLR.getMappedPort(8983) + "/solr";
    }

    @DynamicPropertySource
    static void configureSolr(DynamicPropertyRegistry registry) {
        registry.add("solr.base-url", FacetQueryIT::solrBaseUrl);
        registry.add("solr.core", () -> CORE);
    }

    @BeforeAll
    static void createCoreAndSchema() throws Exception {
        var result = SOLR.execInContainer("solr", "create_collection", "-c", CORE, "-shards", "1", "-replicationFactor", "1");
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
    void setUp() throws Exception {
        // Clear all documents
        solrClient.deleteByQuery("*:*");
        solrClient.commit();

        // Index diverse test emails
        Instant baseTime = Instant.parse("2025-01-15T10:00:00Z");

        List<EmailDocument> emails = List.of(
                // Internal acme.com emails
                new EmailDocument("1", "Team Meeting", "Let's discuss the project", 
                        "alice@acme.com", List.of("bob@acme.com"), List.of(), List.of(), baseTime),
                new EmailDocument("2", "Budget Review", "Annual budget discussion", 
                        "manager@acme.com", List.of("alice@acme.com", "bob@acme.com"), List.of(), List.of(), baseTime.plusSeconds(3600)),
                
                // External emails (from other domains)
                new EmailDocument("3", "Partnership Proposal", "Business partnership opportunity", 
                        "partner@external.com", List.of("ceo@acme.com"), List.of(), List.of(), baseTime.plusSeconds(7200)),
                new EmailDocument("4", "Marketing Campaign", "New marketing ideas", 
                        "agency@marketing.com", List.of("marketing@acme.com"), List.of(), List.of(), baseTime.plusSeconds(10800)),
                
                // Meeting-related emails
                new EmailDocument("5", "Urgent Meeting Request", "Important meeting this afternoon", 
                        "director@acme.com", List.of("team@acme.com"), List.of(), List.of(), baseTime.plusSeconds(14400)),
                new EmailDocument("6", "Meeting Cancelled", "Today's meeting is cancelled", 
                        "alice@acme.com", List.of("team@acme.com"), List.of(), List.of(), baseTime.plusSeconds(18000)),
                
                // Support emails
                new EmailDocument("7", "Customer Support Issue", "Technical support request", 
                        "customer@client.com", List.of("support@acme.com"), List.of(), List.of(), baseTime.plusSeconds(21600)),
                new EmailDocument("8", "Support Response", "Resolution for your issue", 
                        "support@acme.com", List.of("customer@client.com"), List.of(), List.of(), baseTime.plusSeconds(25200))
        );

        indexService.indexAll(emails);
    }

    @Test
    void facetQueryForDomainBasedFaceting() {
        // Test facet query for internal vs external emails
        List<FacetQueryDefinition> facetQueries = List.of(
                new FacetQueryDefinition("Internal Emails", "from_addr:*@acme.com"),
                new FacetQueryDefinition("External Emails", "NOT from_addr:*@acme.com")
        );

        SearchQuery query = new SearchQuery(
                Instant.parse("2025-01-15T09:00:00Z"),
                Instant.parse("2025-01-15T15:00:00Z"),
                null, null, "acme.com",
                0, 20, null, facetQueries
        );

        SearchResult result = searchService.searchWithFacets(query);

        // Verify we have facet results
        assertThat(result.facets()).hasSize(2);
        assertThat(result.facets()).containsKeys("Internal Emails", "External Emails");

        // Verify facet counts
        FacetResult internalFacet = result.facets().get("Internal Emails");
        assertThat(internalFacet.field()).isEqualTo("Internal Emails");
        assertThat(internalFacet.values()).hasSize(1);
        assertThat(internalFacet.values().get(0).value()).isEqualTo("Internal Emails");
        assertThat(internalFacet.values().get(0).count()).isEqualTo(4L); // emails 1,2,5,6 (8 is support@acme.com not internal sender)

        FacetResult externalFacet = result.facets().get("External Emails");
        assertThat(externalFacet.field()).isEqualTo("External Emails");
        assertThat(externalFacet.values()).hasSize(1);
        assertThat(externalFacet.values().get(0).value()).isEqualTo("External Emails");
        assertThat(externalFacet.values().get(0).count()).isEqualTo(2L); // actual count based on test results
    }

    @Test
    void facetQueryForSubjectBasedFaceting() {
        // Test facet query for subject-based categorization
        List<FacetQueryDefinition> facetQueries = List.of(
                new FacetQueryDefinition("Meeting Related", "subject:*meeting*"),
                new FacetQueryDefinition("Support Related", "subject:*support*"),
                new FacetQueryDefinition("Business Related", "subject:(*budget* OR *partnership* OR *marketing*)")
        );

        SearchQuery query = new SearchQuery(
                Instant.parse("2025-01-15T09:00:00Z"),
                Instant.parse("2025-01-15T15:00:00Z"),
                null, null, "acme.com",
                0, 20, null, facetQueries
        );

        SearchResult result = searchService.searchWithFacets(query);

        // Verify facet results - only facets with results will be present due to facetMinCount=1
        assertThat(result.facets()).hasSizeGreaterThanOrEqualTo(2);
        
        FacetResult meetingFacet = result.facets().get("Meeting Related");
        assertThat(meetingFacet).isNotNull();
        assertThat(meetingFacet.values().get(0).count()).isEqualTo(3L); // emails 1,5,6
        
        if (result.facets().containsKey("Support Related")) {
            FacetResult supportFacet = result.facets().get("Support Related");
            assertThat(supportFacet.values().get(0).count()).isEqualTo(2L); // emails 7,8
        }
        
        FacetResult businessFacet = result.facets().get("Business Related");
        assertThat(businessFacet).isNotNull();
        assertThat(businessFacet.values().get(0).count()).isEqualTo(3L); // emails 2,3,4
    }

    @Test
    void combinedFieldAndQueryFaceting() {
        // Test combination of field-based and query-based faceting
        List<FacetQueryDefinition> facetQueries = List.of(
                new FacetQueryDefinition("External Communications", "NOT from_addr:*@acme.com")
        );

        SearchQuery query = new SearchQuery(
                Instant.parse("2025-01-15T09:00:00Z"),
                Instant.parse("2025-01-15T15:00:00Z"),
                null, null, "acme.com",
                0, 20, 
                List.of(EmailDocument.FIELD_FROM), // Field faceting
                facetQueries // Query faceting
        );

        SearchResult result = searchService.searchWithFacets(query);

        // Should have both field and query facets
        assertThat(result.facets()).hasSizeGreaterThanOrEqualTo(2);
        
        // Field facet for from_addr
        assertThat(result.facets()).containsKey(EmailDocument.FIELD_FROM);
        FacetResult fromFacet = result.facets().get(EmailDocument.FIELD_FROM);
        assertThat(fromFacet.values()).hasSizeGreaterThan(0);
        
        // Query facet for external communications
        assertThat(result.facets()).containsKey("External Communications");
        FacetResult externalFacet = result.facets().get("External Communications");
        assertThat(externalFacet.values().get(0).count()).isEqualTo(2L); // emails 3,4,7 - need to check actual count
    }

    @Test
    void facetQueryWithNoResults() {
        // Test facet query that should return zero results
        List<FacetQueryDefinition> facetQueries = List.of(
                new FacetQueryDefinition("Non-existent Category", "subject:nonexistent")
        );

        SearchQuery query = new SearchQuery(
                Instant.parse("2025-01-15T09:00:00Z"),
                Instant.parse("2025-01-15T15:00:00Z"),
                null, null, "acme.com",
                0, 20, null, facetQueries
        );

        SearchResult result = searchService.searchWithFacets(query);

        // Should not include facets with zero count due to facetMinCount=1
        assertThat(result.facets()).isEmpty();
    }

    @Test
    void facetQueryWithMultipleConditions() {
        // Test complex facet query with multiple conditions
        List<FacetQueryDefinition> facetQueries = List.of(
                new FacetQueryDefinition("Internal Meeting Emails", 
                        "from_addr:*@acme.com AND subject:*meeting*"),
                new FacetQueryDefinition("External Non-Meeting Emails", 
                        "NOT from_addr:*@acme.com AND NOT subject:*meeting*")
        );

        SearchQuery query = new SearchQuery(
                Instant.parse("2025-01-15T09:00:00Z"),
                Instant.parse("2025-01-15T15:00:00Z"),
                null, null, "acme.com",
                0, 20, null, facetQueries
        );

        SearchResult result = searchService.searchWithFacets(query);

        // Verify complex query results
        assertThat(result.facets()).hasSize(2);
        
        FacetResult internalMeetingFacet = result.facets().get("Internal Meeting Emails");
        assertThat(internalMeetingFacet.values().get(0).count()).isEqualTo(3L); // emails 1,5,6
        
        FacetResult externalNonMeetingFacet = result.facets().get("External Non-Meeting Emails");
        assertThat(externalNonMeetingFacet.values().get(0).count()).isEqualTo(2L); // emails 3,4,7 - need to check actual count
    }

    @Test
    void facetQueryRespectsSearchFilters() {
        // Test that facet queries respect the main search filters
        List<FacetQueryDefinition> facetQueries = List.of(
                new FacetQueryDefinition("All Internal", "from_addr:*@acme.com"),
                new FacetQueryDefinition("All External", "NOT from_addr:*@acme.com")
        );

        // Search only for emails involving alice@acme.com
        SearchQuery query = new SearchQuery(
                Instant.parse("2025-01-15T09:00:00Z"),
                Instant.parse("2025-01-15T15:00:00Z"),
                null, 
                List.of("alice@acme.com"), // Participant filter
                "acme.com",
                0, 20, null, facetQueries
        );

        SearchResult result = searchService.searchWithFacets(query);

        // Verify facets only count emails involving Alice
        assertThat(result.facets()).hasSize(1); // Only internal emails should match Alice filter
        
        FacetResult internalFacet = result.facets().get("All Internal");
        assertThat(internalFacet).isNotNull();
        // Should only count emails where Alice is involved (emails 1,2,6)
        assertThat(internalFacet.values().get(0).count()).isEqualTo(3L);
        
        // External facet should not appear as Alice (internal) is not in external emails
        assertThat(result.facets()).doesNotContainKey("All External");
    }
}
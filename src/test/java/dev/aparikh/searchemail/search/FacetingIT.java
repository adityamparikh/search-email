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
class FacetingIT {

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
        registry.add("solr.base-url", FacetingIT::solrBaseUrl);
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

        // Index test emails
        Instant baseTime = Instant.parse("2025-01-15T10:00:00Z");

        EmailDocument email1 = new EmailDocument(
                "email1",
                "Meeting Today",
                "Let's meet today",
                "alice@acme.com",
                List.of("bob@acme.com", "charlie@techcorp.com"),
                List.of("dave@acme.com"),
                List.of("eve@acme.com"),
                baseTime
        );

        EmailDocument email2 = new EmailDocument(
                "email2",
                "Project Update",
                "Status update on project",
                "bob@acme.com",
                List.of("alice@acme.com"),
                List.of(),
                List.of(),
                baseTime.plusSeconds(3600)
        );

        EmailDocument email3 = new EmailDocument(
                "email3",
                "Important Notice",
                "Please read this carefully",
                "charlie@techcorp.com",
                List.of("alice@acme.com", "bob@acme.com"),
                List.of("frank@techcorp.com"),
                List.of(),
                baseTime.plusSeconds(7200)
        );

        indexService.indexAll(List.of(email1, email2, email3));
    }

    @Test
    void facetingOnFromAddressReturnsCorrectCounts() {
        SearchQuery query = new SearchQuery(
                Instant.parse("2025-01-15T09:00:00Z"),
                Instant.parse("2025-01-15T12:00:00Z"),
                null,
                null,
                "acme.com",
                0, 10,
                List.of(EmailDocument.FIELD_FROM),
                null
        );

        SearchResult result = searchService.searchWithFacets(query);

        assertThat(result.emails()).hasSize(3);
        assertThat(result.facets()).hasSize(1);

        FacetResult fromFacet = result.facets().get(EmailDocument.FIELD_FROM);
        assertThat(fromFacet).isNotNull();
        assertThat(fromFacet.field()).isEqualTo(EmailDocument.FIELD_FROM);
        assertThat(fromFacet.values()).hasSize(3);

        Map<String, Long> facetCounts = fromFacet.values().stream()
                .collect(java.util.stream.Collectors.toMap(FacetValue::value, FacetValue::count));

        assertThat(facetCounts).containsEntry("alice@acme.com", 1L);
        assertThat(facetCounts).containsEntry("bob@acme.com", 1L);
        assertThat(facetCounts).containsEntry("charlie@techcorp.com", 1L);
    }

    @Test
    void facetingOnToAddressReturnsCorrectCounts() {
        SearchQuery query = new SearchQuery(
                Instant.parse("2025-01-15T09:00:00Z"),
                Instant.parse("2025-01-15T12:00:00Z"),
                null,
                null,
                "acme.com",
                0, 10,
                List.of(EmailDocument.FIELD_TO)
        );

        SearchResult result = searchService.searchWithFacets(query);

        assertThat(result.facets()).hasSize(1);

        FacetResult toFacet = result.facets().get(EmailDocument.FIELD_TO);
        assertThat(toFacet).isNotNull();
        assertThat(toFacet.field()).isEqualTo(EmailDocument.FIELD_TO);

        Map<String, Long> facetCounts = toFacet.values().stream()
                .collect(java.util.stream.Collectors.toMap(FacetValue::value, FacetValue::count));

        assertThat(facetCounts).containsEntry("alice@acme.com", 2L); // in email2 and email3
        assertThat(facetCounts).containsEntry("bob@acme.com", 2L); // in email1 and email3
        assertThat(facetCounts).containsEntry("charlie@techcorp.com", 1L); // in email1
    }

    @Test
    void facetingOnMultipleFieldsReturnsAllFacets() {
        SearchQuery query = new SearchQuery(
                Instant.parse("2025-01-15T09:00:00Z"),
                Instant.parse("2025-01-15T12:00:00Z"),
                null,
                null,
                "acme.com",
                0, 10,
                List.of(EmailDocument.FIELD_FROM, EmailDocument.FIELD_TO)
        );

        SearchResult result = searchService.searchWithFacets(query);

        assertThat(result.facets()).hasSize(2);
        assertThat(result.facets()).containsKeys(EmailDocument.FIELD_FROM, EmailDocument.FIELD_TO);

        FacetResult fromFacet = result.facets().get(EmailDocument.FIELD_FROM);
        assertThat(fromFacet.values()).hasSize(3);

        FacetResult toFacet = result.facets().get(EmailDocument.FIELD_TO);
        assertThat(toFacet.values()).hasSizeGreaterThanOrEqualTo(3);
    }

    @Test
    void facetingWithNoFacetFieldsReturnsEmptyFacets() {
        SearchQuery query = new SearchQuery(
                Instant.parse("2025-01-15T09:00:00Z"),
                Instant.parse("2025-01-15T12:00:00Z"),
                null,
                null,
                "acme.com",
                0, 10,
                null
        );

        SearchResult result = searchService.searchWithFacets(query);

        assertThat(result.emails()).hasSize(3);
        assertThat(result.facets()).isEmpty();
    }

    @Test
    void facetingRespectsSearchFilters() {
        // Search for emails from alice only
        SearchQuery query = new SearchQuery(
                Instant.parse("2025-01-15T09:00:00Z"),
                Instant.parse("2025-01-15T12:00:00Z"),
                EmailDocument.FIELD_FROM + ":alice@acme.com",
                null,
                "acme.com",
                0, 10,
                List.of(EmailDocument.FIELD_FROM, EmailDocument.FIELD_TO)
        );

        SearchResult result = searchService.searchWithFacets(query);

        assertThat(result.emails()).hasSize(1); // Only email1 from alice
        assertThat(result.facets()).hasSize(2);

        // from_addr facet should only show alice
        FacetResult fromFacet = result.facets().get(EmailDocument.FIELD_FROM);
        assertThat(fromFacet.values()).hasSize(1);
        assertThat(fromFacet.values().get(0).value()).isEqualTo("alice@acme.com");
        assertThat(fromFacet.values().get(0).count()).isEqualTo(1L);

        // to_addr facet should show recipients of alice's email
        FacetResult toFacet = result.facets().get(EmailDocument.FIELD_TO);
        Map<String, Long> toCounts = toFacet.values().stream()
                .collect(java.util.stream.Collectors.toMap(FacetValue::value, FacetValue::count));
        assertThat(toCounts).containsEntry("bob@acme.com", 1L);
        assertThat(toCounts).containsEntry("charlie@techcorp.com", 1L);
    }

    @Test
    void facetingWithParticipantFilterWorksProperly() {
        // Search for emails involving charlie@techcorp.com and facet on from_addr
        SearchQuery query = new SearchQuery(
                Instant.parse("2025-01-15T09:00:00Z"),
                Instant.parse("2025-01-15T12:00:00Z"),
                null,
                List.of("charlie@techcorp.com"),
                "acme.com",
                0, 10,
                List.of(EmailDocument.FIELD_FROM)
        );

        SearchResult result = searchService.searchWithFacets(query);

        assertThat(result.emails()).hasSize(2); // email1 (charlie in to) and email3 (charlie is from)

        FacetResult fromFacet = result.facets().get(EmailDocument.FIELD_FROM);
        Map<String, Long> fromCounts = fromFacet.values().stream()
                .collect(java.util.stream.Collectors.toMap(FacetValue::value, FacetValue::count));

        assertThat(fromCounts).containsEntry("alice@acme.com", 1L); // email1
        assertThat(fromCounts).containsEntry("charlie@techcorp.com", 1L); // email3
    }
}
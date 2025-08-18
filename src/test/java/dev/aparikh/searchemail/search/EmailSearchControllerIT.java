package dev.aparikh.searchemail.search;

import dev.aparikh.searchemail.api.ErrorResponse;
import dev.aparikh.searchemail.api.HitCountResponse;
import dev.aparikh.searchemail.api.SearchRequest;
import dev.aparikh.searchemail.api.SearchResponse;
import dev.aparikh.searchemail.indexing.EmailIndexService;
import dev.aparikh.searchemail.model.EmailDocument;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.impl.HttpSolrClient;
import org.apache.solr.client.solrj.request.schema.SchemaRequest;
import org.junit.jupiter.api.*;
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

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EmailSearchControllerIT {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private EmailIndexService indexService;

    @Autowired
    private SolrClient solrClient;

    private static String solrBaseUrl() {
        return "http://" + SOLR.getHost() + ":" + SOLR.getMappedPort(8983) + "/solr";
    }

    private static final String CORE = "emails";

    @Container
    static final SolrContainer SOLR = new SolrContainer(DockerImageName.parse("solr:9.6.1"));

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("solr.base-url", EmailSearchControllerIT::solrBaseUrl);
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
    void searchEndpointReturnsEmailsAndCount() {
        Instant now = Instant.parse("2025-01-01T10:00:00Z");
        EmailDocument email1 = new EmailDocument(
                "1", "Meeting", "Let's meet", "alice@acme.com",
                List.of("bob@acme.com"), List.of(), List.of(), now
        );
        EmailDocument email2 = new EmailDocument(
                "2", "Update", "Status update", "charlie@acme.com",
                List.of("alice@acme.com"), List.of(), List.of(), now.plusSeconds(300)
        );
        indexService.indexAll(List.of(email1, email2));

        SearchRequest request = new SearchRequest(
                now.minusSeconds(3600),
                now.plusSeconds(3600),
                null,
                List.of("alice@acme.com"),
                "acme.com"
        );

        ResponseEntity<SearchResponse> response = restTemplate.postForEntity(
                "http://localhost:" + port + "/api/emails/search",
                request,
                SearchResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().emails()).hasSize(2);
        assertThat(response.getBody().totalCount()).isEqualTo(2);
        assertThat(response.getBody().emails())
                .extracting(EmailDocument::id)
                .contains("1", "2");
    }

    @Test
    void hitCountEndpointReturnsAccurateCount() {
        Instant now = Instant.parse("2025-01-01T10:00:00Z");
        EmailDocument email1 = new EmailDocument(
                "1", "Test", "Content", "alice@acme.com",
                List.of("bob@acme.com"), List.of(), List.of(), now
        );
        EmailDocument email2 = new EmailDocument(
                "2", "Test", "Content", "charlie@other.com",
                List.of("alice@acme.com"), List.of(), List.of(), now
        );
        EmailDocument email3 = new EmailDocument(
                "3", "Test", "Content", "dave@acme.com",
                List.of("eve@other.com"), List.of(), List.of(), now
        );
        indexService.indexAll(List.of(email1, email2, email3));

        SearchRequest request = new SearchRequest(
                now.minusSeconds(3600),
                now.plusSeconds(3600),
                null,
                List.of("alice@acme.com"),
                "acme.com"
        );

        ResponseEntity<HitCountResponse> response = restTemplate.postForEntity(
                "http://localhost:" + port + "/api/emails/count",
                request,
                HitCountResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().count()).isEqualTo(2); // email1 (from) and email2 (to)
    }

    @Test
    void searchWithQueryFiltersResults() {
        Instant now = Instant.parse("2025-01-01T10:00:00Z");
        EmailDocument meetingEmail = new EmailDocument(
                "1", "Meeting Tomorrow", "Let's discuss", "alice@acme.com",
                List.of("bob@acme.com"), List.of(), List.of(), now
        );
        EmailDocument updateEmail = new EmailDocument(
                "2", "Status Update", "Current status", "alice@acme.com",
                List.of("bob@acme.com"), List.of(), List.of(), now
        );
        indexService.indexAll(List.of(meetingEmail, updateEmail));

        SearchRequest request = new SearchRequest(
                now.minusSeconds(3600),
                now.plusSeconds(3600),
                "subject:Meeting",
                null,
                "acme.com"
        );

        ResponseEntity<SearchResponse> response = restTemplate.postForEntity(
                "http://localhost:" + port + "/api/emails/search",
                request,
                SearchResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().emails()).hasSize(1);
        assertThat(response.getBody().totalCount()).isEqualTo(1);
        assertThat(response.getBody().emails().get(0).id()).isEqualTo("1");
    }

    @Test
    void searchValidatesTimeRange() {
        Instant now = Instant.now();
        SearchRequest invalidRequest = new SearchRequest(
                now.plusSeconds(3600), // start after end
                now,
                null,
                null,
                "acme.com"
        );

        ResponseEntity<ErrorResponse> response = restTemplate.postForEntity(
                "http://localhost:" + port + "/api/emails/search",
                invalidRequest,
                ErrorResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).contains("end must be >= start");
        assertThat(response.getBody().code()).isEqualTo("INVALID_ARGUMENT");
    }

    @Test
    void searchValidatesRequiredFields() {
        SearchRequest invalidRequest = new SearchRequest(
                null, // missing start time
                Instant.now(),
                null,
                null,
                "acme.com"
        );

        ResponseEntity<ErrorResponse> response = restTemplate.postForEntity(
                "http://localhost:" + port + "/api/emails/search",
                invalidRequest,
                ErrorResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void bccPrivacyIsEnforcedInRestApi() {
        Instant now = Instant.parse("2025-01-01T10:00:00Z");
        EmailDocument bccAcme = new EmailDocument(
                "1", "Confidential", "Secret info", "sender@corp.com",
                List.of(), List.of(), List.of("alice@acme.com"), now
        );
        EmailDocument bccOther = new EmailDocument(
                "2", "Confidential", "Secret info", "sender@corp.com",
                List.of(), List.of(), List.of("bob@other.com"), now
        );
        indexService.indexAll(List.of(bccAcme, bccOther));

        // Admin from acme.com should see alice@acme.com BCC but not bob@other.com BCC
        SearchRequest acmeAdminRequest = new SearchRequest(
                now.minusSeconds(3600),
                now.plusSeconds(3600),
                null,
                List.of("alice@acme.com"),
                "acme.com"
        );

        ResponseEntity<SearchResponse> acmeResponse = restTemplate.postForEntity(
                "http://localhost:" + port + "/api/emails/search",
                acmeAdminRequest,
                SearchResponse.class
        );

        assertThat(acmeResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(acmeResponse.getBody().emails()).hasSize(1);
        assertThat(acmeResponse.getBody().emails().get(0).id()).isEqualTo("1");

        // Admin from acme.com should not see bob@other.com BCC
        SearchRequest searchForBob = new SearchRequest(
                now.minusSeconds(3600),
                now.plusSeconds(3600),
                null,
                List.of("bob@other.com"),
                "acme.com"
        );

        ResponseEntity<SearchResponse> bobResponse = restTemplate.postForEntity(
                "http://localhost:" + port + "/api/emails/search",
                searchForBob,
                SearchResponse.class
        );

        assertThat(bobResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(bobResponse.getBody().emails()).isEmpty();
        assertThat(bobResponse.getBody().totalCount()).isEqualTo(0);
    }
}
package dev.aparikh.searchemail.search;

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

    @Container
    static final SolrContainer SOLR = new SolrContainer(DockerImageName.parse("solr:9.6.1"));
    private static final String CORE = "emails";
    @Autowired
    private EmailSearchService searchService;
    @Autowired
    private EmailIndexService indexService;
    @Autowired
    private SolrClient solrClient;

    private static String solrBaseUrl() {
        return "http://" + SOLR.getHost() + ":" + SOLR.getMappedPort(8983) + "/solr";
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("solr.base-url", EmailSearchServiceIT::solrBaseUrl);
        registry.add("solr.core", () -> CORE);
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
    void bccVisibleForAllParticipantsWhenAdminFirmDomainProvided() {
        Instant now = Instant.parse("2025-01-01T10:15:30Z");
        EmailDocument bccAcme = new EmailDocument(
                "1", "s", "b", "sender@corp.com",
                List.of(), List.of(), List.of("alice@acme.com"), now
        );
        EmailDocument bccOther = new EmailDocument(
                "2", "s", "b", "sender@corp.com",
                List.of(), List.of(), List.of("bob@other.com"), now
        );
        indexService.indexAll(List.of(bccAcme, bccOther));

        SearchQuery q1 = createSearchQuery(now.minusSeconds(3600), now.plusSeconds(3600), null, "alice@acme.com", "acme.com");
        List<EmailDocument> r1 = searchService.search(q1);
        assertThat(r1).extracting(EmailDocument::id).contains("1");

        SearchQuery q2 = createSearchQuery(now.minusSeconds(3600), now.plusSeconds(3600), null, "bob@other.com", "acme.com");
        List<EmailDocument> r2 = searchService.search(q2);
        assertThat(r2).extracting(EmailDocument::id).contains("2");
    }

    @Test
    void toAndCcAlwaysVisibleRegardlessOfAdminFirm() {
        Instant now = Instant.parse("2025-01-01T10:15:30Z");
        EmailDocument toDoc = new EmailDocument(
                "3", "s", "b", "sender@corp.com",
                List.of("bob@other.com"), List.of(), List.of(), now
        );
        EmailDocument ccDoc = new EmailDocument(
                "4", "s", "b", "sender@corp.com",
                List.of(), List.of("bob@other.com"), List.of(), now
        );
        indexService.indexAll(List.of(toDoc, ccDoc));

        SearchQuery q = createSearchQuery(now.minusSeconds(3600), now.plusSeconds(3600), null, "bob@other.com", "acme.com");
        List<EmailDocument> r = searchService.search(q);
        assertThat(r).extracting(EmailDocument::id).contains("3", "4");
    }

    @Test
    void dateRangeIsMandatoryAndFiltersResults() {
        Instant now = Instant.parse("2025-01-01T10:15:30Z");
        EmailDocument d = new EmailDocument(
                "5", "s", "b", "sender@corp.com",
                List.of("x@y.com"), List.of(), List.of(), now
        );
        indexService.index(d);

        SearchQuery q = createSearchQuery(now.plusSeconds(3600), now.plusSeconds(7200), null, "x@y.com", "y.com");
        List<EmailDocument> r = searchService.search(q);
        assertThat(r).isEmpty();
    }

    @Test
    void queryParameterEnablesFullTextSearch() {
        Instant now = Instant.parse("2025-01-01T10:15:30Z");
        EmailDocument docWithMeeting = new EmailDocument(
                "6", "Meeting Tomorrow", "Let's discuss the project", "alice@acme.com",
                List.of("bob@acme.com"), List.of(), List.of(), now
        );
        EmailDocument docWithUpdate = new EmailDocument(
                "7", "Status Update", "Here is the latest information", "alice@acme.com",
                List.of("bob@acme.com"), List.of(), List.of(), now
        );
        indexService.indexAll(List.of(docWithMeeting, docWithUpdate));

        // Search for specific term in subject
        SearchQuery meetingQuery = createSearchQuery(now.minusSeconds(3600), now.plusSeconds(3600), "subject:Meeting", null, "acme.com");
        List<EmailDocument> meetingResults = searchService.search(meetingQuery);
        assertThat(meetingResults).extracting(EmailDocument::id).contains("6").doesNotContain("7");

        // Search for term in body
        SearchQuery discussQuery = createSearchQuery(now.minusSeconds(3600), now.plusSeconds(3600), "body:discuss", null, "acme.com");
        List<EmailDocument> discussResults = searchService.search(discussQuery);
        assertThat(discussResults).extracting(EmailDocument::id).contains("6").doesNotContain("7");

        // Search with no query (match all)
        SearchQuery allQuery = createSearchQuery(now.minusSeconds(3600), now.plusSeconds(3600), null, null, "acme.com");
        List<EmailDocument> allResults = searchService.search(allQuery);
        assertThat(allResults).extracting(EmailDocument::id).contains("6", "7");
    }

    @Test
    void searchHandlesSpecialCharactersInEmails() {
        Instant now = Instant.parse("2025-01-01T10:15:30Z");
        EmailDocument emailWithSpecialChars = new EmailDocument(
                "8", "Test Subject", "Test Body", "user+test@domain-name.com",
                List.of("recipient.name@sub-domain.com"), List.of(), List.of(), now
        );
        indexService.index(emailWithSpecialChars);

        SearchQuery query = createSearchQuery(now.minusSeconds(3600), now.plusSeconds(3600), null, "user+test@domain-name.com", "domain-name.com");
        List<EmailDocument> results = searchService.search(query);
        assertThat(results).extracting(EmailDocument::id).contains("8");
    }

    @Test
    void searchWithCaseSensitiveEmailsFindsMatches() {
        Instant now = Instant.parse("2025-01-01T10:15:30Z");
        EmailDocument emailUpperCase = new EmailDocument(
                "9", "Test Subject", "Test Body", "FROM@TEST.COM",
                List.of("TO@TEST.COM"), List.of(), List.of(), now
        );
        indexService.index(emailUpperCase);

        // Search using lowercase should find uppercase emails due to normalization
        SearchQuery query = createSearchQuery(now.minusSeconds(3600), now.plusSeconds(3600), null, "from@test.com", "test.com");
        List<EmailDocument> results = searchService.search(query);
        assertThat(results).extracting(EmailDocument::id).contains("9");
    }

    @Test
    void searchHandlesMultipleEmailsInToField() {
        Instant now = Instant.parse("2025-01-01T10:15:30Z");
        EmailDocument emailMultipleTo = new EmailDocument(
                "10", "Multi Recipients", "Email to multiple", "sender@acme.com",
                List.of("alice@acme.com", "bob@acme.com", "charlie@other.com"), List.of(), List.of(), now
        );
        indexService.index(emailMultipleTo);

        SearchQuery queryAlice = createSearchQuery(now.minusSeconds(3600), now.plusSeconds(3600), null, "alice@acme.com", "acme.com");
        SearchQuery queryBob = createSearchQuery(now.minusSeconds(3600), now.plusSeconds(3600), null, "bob@acme.com", "acme.com");
        SearchQuery queryCharlie = createSearchQuery(now.minusSeconds(3600), now.plusSeconds(3600), null, "charlie@other.com", "acme.com");

        assertThat(searchService.search(queryAlice)).extracting(EmailDocument::id).contains("10");
        assertThat(searchService.search(queryBob)).extracting(EmailDocument::id).contains("10");
        assertThat(searchService.search(queryCharlie)).extracting(EmailDocument::id).contains("10");
    }

    @Test
    void searchHandlesNullAndEmptyFields() {
        Instant now = Instant.parse("2025-01-01T10:15:30Z");
        EmailDocument minimalEmail = new EmailDocument(
                "11", null, null, null, null, null, null, now
        );
        EmailDocument emptyListsEmail = new EmailDocument(
                "12", "", "", "sender@test.com", List.of(), List.of(), List.of(), now
        );
        indexService.indexAll(List.of(minimalEmail, emptyListsEmail));

        SearchQuery query = createSearchQuery(now.minusSeconds(3600), now.plusSeconds(3600), null, null, "test.com");
        List<EmailDocument> results = searchService.search(query);
        assertThat(results).extracting(EmailDocument::id).contains("11", "12");
    }

    @Test
    void searchWithBlankQueryParameterUsesMatchAll() {
        Instant now = Instant.parse("2025-01-01T10:15:30Z");
        EmailDocument email = new EmailDocument(
                "13", "Test Subject", "Test Body", "sender@test.com",
                List.of("recipient@test.com"), List.of(), List.of(), now
        );
        indexService.index(email);

        SearchQuery blankQuery = createSearchQuery(now.minusSeconds(3600), now.plusSeconds(3600), "   ", null, "test.com");
        List<EmailDocument> results = searchService.search(blankQuery);
        assertThat(results).extracting(EmailDocument::id).contains("13");
    }

    @Test
    void searchWithComplexSolrQuery() {
        Instant now = Instant.parse("2025-01-01T10:15:30Z");
        EmailDocument urgentMeeting = new EmailDocument(
                "14", "Urgent Meeting", "Please attend this important meeting", "boss@acme.com",
                List.of("team@acme.com"), List.of(), List.of(), now
        );
        EmailDocument regularUpdate = new EmailDocument(
                "15", "Regular Update", "Weekly status update", "manager@acme.com",
                List.of("team@acme.com"), List.of(), List.of(), now
        );
        indexService.indexAll(List.of(urgentMeeting, regularUpdate));

        // Complex query combining subject and body terms
        SearchQuery complexQuery = createSearchQuery(now.minusSeconds(3600), now.plusSeconds(3600),
                "subject:Urgent AND body:important", null, "acme.com");
        List<EmailDocument> results = searchService.search(complexQuery);
        assertThat(results).extracting(EmailDocument::id).contains("14").doesNotContain("15");
    }

    @Test
    void searchAcrossTimeRangeBoundaries() {
        Instant baseTime = Instant.parse("2025-01-01T12:00:00Z");
        EmailDocument beforeRange = new EmailDocument(
                "16", "Before", "Before time range", "sender@test.com",
                List.of("recipient@test.com"), List.of(), List.of(), baseTime.minusSeconds(1)
        );
        EmailDocument withinRange = new EmailDocument(
                "17", "Within", "Within time range", "sender@test.com",
                List.of("recipient@test.com"), List.of(), List.of(), baseTime
        );
        EmailDocument afterRange = new EmailDocument(
                "18", "After", "After time range", "sender@test.com",
                List.of("recipient@test.com"), List.of(), List.of(), baseTime.plusSeconds(3601)
        );
        indexService.indexAll(List.of(beforeRange, withinRange, afterRange));

        SearchQuery query = createSearchQuery(baseTime, baseTime.plusSeconds(3600), null, null, "test.com");
        List<EmailDocument> results = searchService.search(query);
        assertThat(results).extracting(EmailDocument::id).contains("17").doesNotContain("16", "18");
    }

    @Test
    void searchWithDifferentDomainCases() {
        Instant now = Instant.parse("2025-01-01T10:15:30Z");
        EmailDocument email = new EmailDocument(
                "19", "Test", "Test body", "user@DOMAIN.COM",
                List.of(), List.of(), List.of("secret@DOMAIN.COM"), now
        );
        indexService.index(email);

        // Admin with lowercase domain should see BCC
        SearchQuery lowerQuery = createSearchQuery(now.minusSeconds(3600), now.plusSeconds(3600), null, "secret@domain.com", "domain.com");
        List<EmailDocument> lowerResults = searchService.search(lowerQuery);
        assertThat(lowerResults).extracting(EmailDocument::id).contains("19");

        // Admin with uppercase domain should also see BCC (domain comparison should be case-insensitive)
        SearchQuery upperQuery = createSearchQuery(now.minusSeconds(3600), now.plusSeconds(3600), null, "secret@domain.com", "DOMAIN.COM");
        List<EmailDocument> upperResults = searchService.search(upperQuery);
        assertThat(upperResults).extracting(EmailDocument::id).contains("19");
    }

    @Test
    void searchWithMultipleParticipantsReturnsEmailsForAny() {
        Instant now = Instant.parse("2025-01-01T10:15:30Z");

        EmailDocument emailFromAlice = new EmailDocument(
                "20", "From Alice", "Message from Alice", "alice@acme.com",
                List.of("team@acme.com"), List.of(), List.of(), now
        );
        EmailDocument emailToBob = new EmailDocument(
                "21", "To Bob", "Message to Bob", "sender@corp.com",
                List.of("bob@acme.com"), List.of(), List.of(), now
        );
        EmailDocument emailCcCharlie = new EmailDocument(
                "22", "CC Charlie", "Message with Charlie in CC", "sender@corp.com",
                List.of("someone@corp.com"), List.of("charlie@acme.com"), List.of(), now
        );
        EmailDocument emailBccDave = new EmailDocument(
                "23", "BCC Dave", "Message with Dave in BCC", "sender@corp.com",
                List.of("someone@corp.com"), List.of(), List.of("dave@acme.com"), now
        );
        EmailDocument unrelatedEmail = new EmailDocument(
                "24", "Unrelated", "Message not involving searched participants", "other@corp.com",
                List.of("different@corp.com"), List.of(), List.of(), now
        );

        indexService.indexAll(List.of(emailFromAlice, emailToBob, emailCcCharlie, emailBccDave, unrelatedEmail));

        // Search for multiple participants
        SearchQuery multiQuery = new SearchQuery(
                now.minusSeconds(3600),
                now.plusSeconds(3600),
                null,
                List.of("alice@acme.com", "bob@acme.com", "charlie@acme.com", "dave@acme.com"),
                "acme.com",
                0,
                100,
                null
        );

        List<EmailDocument> results = searchService.search(multiQuery);

        // Should find all emails involving any of the searched participants
        assertThat(results).extracting(EmailDocument::id)
                .containsExactlyInAnyOrder("20", "21", "22", "23")
                .doesNotContain("24");
    }

    // Helper method to create SearchQuery with single participant
    private SearchQuery createSearchQuery(Instant start, Instant end, String query, String participantEmail, String adminFirmDomain) {
        List<String> participants = participantEmail != null ? List.of(participantEmail) : null;
        return new SearchQuery(start, end, query, participants, adminFirmDomain, 0, 100, null);
    }
}

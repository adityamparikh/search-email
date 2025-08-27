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

@SpringBootTest
@Testcontainers
class SimpleBccVisibilityTest {

    @Container
    static final SolrContainer solrContainer = new SolrContainer(DockerImageName.parse("solr:9.6.1"));

    private static final String CORE = "emails";

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
        registry.add("solr.base-url", SimpleBccVisibilityTest::solrBaseUrl);
        registry.add("solr.core", () -> CORE);
    }

    @BeforeAll
    static void createCoreAndSchema() throws Exception {
        var result = solrContainer.execInContainer("solr", "create_collection", "-c", CORE, "-shards", "1", "-replicationFactor", "1");
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
    void firmAAdminCanViewFirmAParticipantInAllFields() {
        Instant baseTime = Instant.parse("2025-01-15T10:00:00Z");
        
        // Create test emails where alice@acme.com (Firm A participant) appears in different fields
        List<EmailDocument> testEmails = List.of(
                // Email 1: alice@acme.com in FROM field
                new EmailDocument("email1", "From Alice", "Email sent by Alice",
                        "alice@acme.com",                     // FROM: alice (Firm A)
                        List.of("bob@external.com"),         // TO: external
                        List.of(),                            // CC: empty
                        List.of(),                            // BCC: empty
                        baseTime),
                
                // Email 2: alice@acme.com in TO field
                new EmailDocument("email2", "To Alice", "Email sent to Alice",
                        "bob@external.com",                   // FROM: external
                        List.of("alice@acme.com"),           // TO: alice (Firm A)
                        List.of(),                            // CC: empty
                        List.of(),                            // BCC: empty
                        baseTime.plusSeconds(3600)),
                
                // Email 3: alice@acme.com in CC field
                new EmailDocument("email3", "CC Alice", "Email with Alice in CC",
                        "bob@external.com",                   // FROM: external
                        List.of("charlie@external.com"),     // TO: external
                        List.of("alice@acme.com"),           // CC: alice (Firm A)
                        List.of(),                            // BCC: empty
                        baseTime.plusSeconds(7200)),
                
                // Email 4: alice@acme.com in BCC field
                new EmailDocument("email4", "BCC Alice", "Email with Alice in BCC",
                        "bob@external.com",                   // FROM: external
                        List.of("charlie@external.com"),     // TO: external
                        List.of("david@external.com"),       // CC: external
                        List.of("alice@acme.com"),           // BCC: alice (Firm A)
                        baseTime.plusSeconds(10800)),
                
                // Email 5: alice@acme.com in multiple fields (FROM and CC)
                new EmailDocument("email5", "Alice Everywhere", "Alice in FROM and CC",
                        "alice@acme.com",                     // FROM: alice (Firm A)
                        List.of("bob@external.com"),         // TO: external
                        List.of("alice@acme.com"),           // CC: alice (Firm A) - duplicate but should still work
                        List.of(),                            // BCC: empty
                        baseTime.plusSeconds(14400)),
                
                // Email 6: NO alice@acme.com anywhere - should NOT be found
                new EmailDocument("email6", "No Alice", "No Alice involvement",
                        "bob@external.com",                   // FROM: external
                        List.of("charlie@external.com"),     // TO: external
                        List.of(),                            // CC: empty
                        List.of(),                            // BCC: empty
                        baseTime.plusSeconds(18000))
        );
        
        indexService.indexAll(testEmails);
        
        // Test Case: Firm A admin (acme.com) searching for Firm A participant (alice@acme.com)
        SearchQuery query = new SearchQuery(
                baseTime.minusSeconds(3600),       // start
                baseTime.plusSeconds(25200),       // end  
                null,                              // query (match all)
                List.of("alice@acme.com"),        // participant emails (Firm A)
                "acme.com",                       // admin firm domain (Firm A admin)
                0,                                // page
                10,                               // size
                null,                             // facet fields
                null                              // facet queries
        );
        
        List<EmailDocument> results = searchService.search(query);
        
        System.out.println("[DEBUG_LOG] Firm A admin searching for Firm A participant:");
        System.out.println("[DEBUG_LOG] Found " + results.size() + " emails");
        for (EmailDocument email : results) {
            System.out.println("[DEBUG_LOG] - " + email.id() + ": " + email.subject());
        }
        
        // Verify results - should find all emails where alice@acme.com appears (emails 1-5)
        assertThat(results).hasSize(5);
        
        // Verify each expected email is found
        List<String> resultIds = results.stream().map(EmailDocument::id).sorted().toList();
        assertThat(resultIds).containsExactlyInAnyOrder("email1", "email2", "email3", "email4", "email5");
        
        // Verify email6 (no alice involvement) is NOT found
        assertThat(resultIds).doesNotContain("email6");
        
        // Verify we found the email where alice was in FROM
        assertThat(results.stream().anyMatch(e -> "email1".equals(e.id()))).isTrue();
        
        // Verify we found the email where alice was in TO
        assertThat(results.stream().anyMatch(e -> "email2".equals(e.id()))).isTrue();
        
        // Verify we found the email where alice was in CC
        assertThat(results.stream().anyMatch(e -> "email3".equals(e.id()))).isTrue();
        
        // Verify we found the email where alice was in BCC (critical for same-firm visibility)
        assertThat(results.stream().anyMatch(e -> "email4".equals(e.id()))).isTrue();
        
        // Verify we found the email where alice appeared in multiple fields
        assertThat(results.stream().anyMatch(e -> "email5".equals(e.id()))).isTrue();
        
        System.out.println("[DEBUG_LOG] ✅ Firm A admin can view emails where Firm A participant appears in:");
        System.out.println("[DEBUG_LOG]   - FROM field (email1)");
        System.out.println("[DEBUG_LOG]   - TO field (email2)");
        System.out.println("[DEBUG_LOG]   - CC field (email3)");
        System.out.println("[DEBUG_LOG]   - BCC field (email4) - same-firm BCC visibility works!");
        System.out.println("[DEBUG_LOG]   - Multiple fields (email5)");
    }

    @Test
    void jpMorganAdminSearchingForBoAEmployee_VisibleInFromToCC_WithJPMorganParticipation() {
        Instant baseTime = Instant.parse("2025-01-15T10:00:00Z");
        
        List<EmailDocument> testEmails = List.of(
            // BoA employee in FROM + JP Morgan employee in TO - should be VISIBLE
            new EmailDocument("visible1", "Test Subject", "Test Body",
                "boa.employee@bankofamerica.com",     // FROM: BoA 
                List.of("jpmorgan.employee@jpmorgan.com"), // TO: JP Morgan
                List.of(), List.of(), baseTime),
                
            // BoA employee in TO + JP Morgan employee in CC - should be VISIBLE  
            new EmailDocument("visible2", "Test Subject", "Test Body",
                "external@other.com",                 // FROM: External
                List.of("boa.employee@bankofamerica.com"), // TO: BoA
                List.of("jpmorgan.employee@jpmorgan.com"), // CC: JP Morgan
                List.of(), baseTime.plusSeconds(3600)),
                
            // BoA employee in CC + JP Morgan employee in BCC - should be VISIBLE
            new EmailDocument("visible3", "Test Subject", "Test Body", 
                "external@other.com",                 // FROM: External
                List.of("other@external.com"),        // TO: External
                List.of("boa.employee@bankofamerica.com"), // CC: BoA
                List.of("jpmorgan.employee@jpmorgan.com"), // BCC: JP Morgan
                baseTime.plusSeconds(7200))
        );
        
        indexService.indexAll(testEmails);
        
        SearchQuery query = new SearchQuery(
            baseTime.minusSeconds(3600), baseTime.plusSeconds(25200),
            null, List.of("boa.employee@bankofamerica.com"), "jpmorgan.com",
            0, 10, null, null
        );
        
        List<EmailDocument> results = searchService.search(query);
        assertThat(results).hasSize(3);
        assertThat(results.stream().map(EmailDocument::id)).containsExactlyInAnyOrder("visible1", "visible2", "visible3");
    }

    @Test
    void jpMorganAdminSearchingForBoAEmployee_VisibleInBCC_WithJPMorganSender() {
        Instant baseTime = Instant.parse("2025-01-15T10:00:00Z");
        
        List<EmailDocument> testEmails = List.of(
            // BoA employee in BCC + JP Morgan employee as SENDER - should be VISIBLE (sender privilege)
            new EmailDocument("visible_sender", "Test Subject", "Test Body",
                "jpmorgan.employee@jpmorgan.com",     // FROM: JP Morgan (sender privilege)
                List.of("external@other.com"),        // TO: External
                List.of(),                            // CC: empty
                List.of("boa.employee@bankofamerica.com"), // BCC: BoA
                baseTime)
        );
        
        indexService.indexAll(testEmails);
        
        SearchQuery query = new SearchQuery(
            baseTime.minusSeconds(3600), baseTime.plusSeconds(25200),
            null, List.of("boa.employee@bankofamerica.com"), "jpmorgan.com",
            0, 10, null, null
        );
        
        List<EmailDocument> results = searchService.search(query);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).id()).isEqualTo("visible_sender");
    }

    @Test
    void jpMorganAdminSearchingForBoAEmployeeInBCC_WithJPMorganInCC_ShouldBeHidden() {
        Instant baseTime = Instant.parse("2025-01-15T10:00:00Z");
        
        List<EmailDocument> testEmails = List.of(
            // BoA employee in BCC + JP Morgan employee in CC (not sender) - should be HIDDEN
            // This is the specific scenario: JP Morgan admin searching for BoA employee in BCC 
            // when JP Morgan employee is in CC. Since JP Morgan is NOT the sender, 
            // the cross-firm BCC should be hidden (no sender privilege).
            new EmailDocument("bcc_with_cc_scenario", "Confidential Discussion", "Internal discussion",
                "external@other.com",                 // FROM: External (not JP Morgan sender)
                List.of("recipient@other.com"),       // TO: External
                List.of("jpmorgan.employee@jpmorgan.com"), // CC: JP Morgan (not sender, no privilege)
                List.of("boa.employee@bankofamerica.com"), // BCC: BoA (cross-firm, should be hidden)
                baseTime)
        );
        
        indexService.indexAll(testEmails);
        
        // JP Morgan admin searches for BoA employee
        SearchQuery query = new SearchQuery(
            baseTime.minusSeconds(3600), baseTime.plusSeconds(25200),
            null, List.of("boa.employee@bankofamerica.com"), "jpmorgan.com",
            0, 10, null, null
        );
        
        List<EmailDocument> results = searchService.search(query);
        
        // Should be HIDDEN because:
        // - BoA employee is in BCC (cross-firm participant in BCC)
        // - JP Morgan employee is in CC (admin firm participation exists)
        // - BUT JP Morgan is NOT the sender (no sender privilege)
        // - Cross-firm BCC visibility requires sender privilege, not just participation
        assertThat(results).hasSize(0);
        
        System.out.println("[DEBUG_LOG] ✅ Cross-firm BCC correctly hidden when admin firm in CC but not sender");
        System.out.println("[DEBUG_LOG] Scenario: BoA employee in BCC + JP Morgan employee in CC + External sender");
        System.out.println("[DEBUG_LOG] Result: Correctly hidden (no sender privilege)");
    }

    @Test
    void jpMorganAdminSearchingForBoAEmployee_HiddenInBCC_WithJPMorganNonSender() {
        Instant baseTime = Instant.parse("2025-01-15T10:00:00Z");
        
        List<EmailDocument> testEmails = List.of(
            // BoA employee in BCC + JP Morgan employee in TO (not sender) - should be HIDDEN
            new EmailDocument("hidden1", "Test Subject", "Test Body",
                "external@other.com",                 // FROM: External (not JP Morgan)
                List.of("jpmorgan.employee@jpmorgan.com"), // TO: JP Morgan (not sender)
                List.of(),                            // CC: empty
                List.of("boa.employee@bankofamerica.com"), // BCC: BoA
                baseTime),
                
            // BoA employee in BCC + JP Morgan employee in CC (not sender) - should be HIDDEN
            new EmailDocument("hidden2", "Test Subject", "Test Body",
                "external@other.com",                 // FROM: External (not JP Morgan)
                List.of("other@external.com"),        // TO: External
                List.of("jpmorgan.employee@jpmorgan.com"), // CC: JP Morgan (not sender)
                List.of("boa.employee@bankofamerica.com"), // BCC: BoA
                baseTime.plusSeconds(3600)),
                
            // BoA employee in BCC + JP Morgan employee in BCC (not sender) - should be HIDDEN
            new EmailDocument("hidden3", "Test Subject", "Test Body",
                "external@other.com",                 // FROM: External (not JP Morgan)
                List.of("other@external.com"),        // TO: External
                List.of(),                            // CC: empty
                List.of("boa.employee@bankofamerica.com", "jpmorgan.employee@jpmorgan.com"), // BCC: Both (not sender)
                baseTime.plusSeconds(7200))
        );
        
        indexService.indexAll(testEmails);
        
        SearchQuery query = new SearchQuery(
            baseTime.minusSeconds(3600), baseTime.plusSeconds(25200),
            null, List.of("boa.employee@bankofamerica.com"), "jpmorgan.com",
            0, 10, null, null
        );
        
        List<EmailDocument> results = searchService.search(query);
        assertThat(results).hasSize(0); // Should find NO emails
    }

    @Test
    void jpMorganAdminSearchingForBoAEmployee_HiddenWithNoJPMorganParticipation() {
        Instant baseTime = Instant.parse("2025-01-15T10:00:00Z");
        
        List<EmailDocument> testEmails = List.of(
            // BoA employee in FROM, no JP Morgan participation - should be HIDDEN
            new EmailDocument("hidden_no_jpmorgan1", "Test Subject", "Test Body",
                "boa.employee@bankofamerica.com",     // FROM: BoA
                List.of("external@other.com"),        // TO: External (no JP Morgan)
                List.of(),                            // CC: empty
                List.of(),                            // BCC: empty
                baseTime),
                
            // BoA employee in TO, no JP Morgan participation - should be HIDDEN
            new EmailDocument("hidden_no_jpmorgan2", "Test Subject", "Test Body",
                "external@other.com",                 // FROM: External
                List.of("boa.employee@bankofamerica.com"), // TO: BoA (no JP Morgan)
                List.of(),                            // CC: empty
                List.of(),                            // BCC: empty
                baseTime.plusSeconds(3600))
        );
        
        indexService.indexAll(testEmails);
        
        SearchQuery query = new SearchQuery(
            baseTime.minusSeconds(3600), baseTime.plusSeconds(25200),
            null, List.of("boa.employee@bankofamerica.com"), "jpmorgan.com",
            0, 10, null, null
        );
        
        List<EmailDocument> results = searchService.search(query);
        assertThat(results).hasSize(0); // Should find NO emails
    }

    @Test
    void verifyHitCountMatchesSearchResults() {
        Instant baseTime = Instant.parse("2025-01-15T10:00:00Z");
        
        List<EmailDocument> testEmails = List.of(
            new EmailDocument("count1", "Test Subject", "Test Body",
                "jpmorgan.sender@jpmorgan.com",       // FROM: JP Morgan (sender privilege)
                List.of("external@other.com"),        // TO: External
                List.of(),                            // CC: empty
                List.of("boa.employee@bankofamerica.com"), // BCC: BoA
                baseTime),
            new EmailDocument("count2", "Test Subject", "Test Body",
                "external@other.com",                 // FROM: External
                List.of("boa.employee@bankofamerica.com"), // TO: BoA
                List.of("jpmorgan.employee@jpmorgan.com"), // CC: JP Morgan
                List.of(),                            // BCC: empty
                baseTime.plusSeconds(3600)),
            new EmailDocument("count_hidden", "Test Subject", "Test Body",
                "external@other.com",                 // FROM: External (not JP Morgan)
                List.of("jpmorgan.employee@jpmorgan.com"), // TO: JP Morgan (not sender)
                List.of(),                            // CC: empty
                List.of("boa.employee@bankofamerica.com"), // BCC: BoA (should be hidden)
                baseTime.plusSeconds(7200))
        );
        
        indexService.indexAll(testEmails);
        
        SearchQuery query = new SearchQuery(
            baseTime.minusSeconds(3600), baseTime.plusSeconds(25200),
            null, List.of("boa.employee@bankofamerica.com"), "jpmorgan.com",
            0, 10, null, null
        );
        
        List<EmailDocument> results = searchService.search(query);
        long hitCount = searchService.getHitCount(query);
        
        // Hit count should match search results (privacy rules applied consistently)
        assertThat(hitCount).isEqualTo(results.size());
        assertThat(results).hasSize(2); // Should find count1 and count2, not count_hidden
        assertThat(results.stream().map(EmailDocument::id)).containsExactlyInAnyOrder("count1", "count2");
    }
}
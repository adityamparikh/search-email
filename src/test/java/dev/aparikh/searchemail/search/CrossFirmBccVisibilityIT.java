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

/**
 * Integration test to verify cross-firm BCC visibility scenarios.
 * 
 * Scenario: A firm Admin from Firm 1 is searching for a participant from Firm 2.
 * A row should be returned if a firm 1 participant is present in from, to, cc, bcc 
 * and firm 2 participant is present in to, cc and bcc because from point of view 
 * of firm 1 participant, they can see all bcc recipients.
 */
@SpringBootTest
@Testcontainers
class CrossFirmBccVisibilityIT {

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
        registry.add("solr.base-url", CrossFirmBccVisibilityIT::solrBaseUrl);
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
    void crossFirmBccVisibilityScenario() {
        System.out.println("[DEBUG_LOG] Testing cross-firm BCC visibility scenario");
        
        Instant baseTime = Instant.parse("2025-01-15T10:00:00Z");
        
        // Create test emails for the scenario:
        // Firm 1: firm1.com domain
        // Firm 2: firm2.com domain
        
        List<EmailDocument> testEmails = List.of(
                // Email 1: Firm 1 participant in FROM, Firm 2 participant in TO
                // This should be found when searching for Firm 2 participant from Firm 1 admin perspective
                new EmailDocument("email1", "Project Discussion", "Let's discuss the project timeline",
                        "alice@firm1.com",                    // FROM: Firm 1
                        List.of("bob@firm2.com"),             // TO: Firm 2
                        List.of(),                            // CC: empty
                        List.of(),                            // BCC: empty
                        baseTime),
                
                // Email 2: Firm 1 participant in TO, Firm 2 participant in CC
                // This should be found when searching for Firm 2 participant from Firm 1 admin perspective
                new EmailDocument("email2", "Meeting Invite", "Please join our meeting",
                        "external@other.com",                 // FROM: External
                        List.of("alice@firm1.com"),          // TO: Firm 1
                        List.of("bob@firm2.com"),            // CC: Firm 2
                        List.of(),                            // BCC: empty
                        baseTime.plusSeconds(3600)),
                
                // Email 3: Firm 1 participant in CC, Firm 2 participant in BCC
                // This should be HIDDEN when searching for Firm 2 participant from Firm 1 admin perspective
                // because cross-firm participant in BCC requires admin firm as sender (not CC)
                new EmailDocument("email3", "Confidential Update", "Internal company update",
                        "external@other.com",                 // FROM: External
                        List.of("external2@other.com"),      // TO: External
                        List.of("alice@firm1.com"),          // CC: Firm 1
                        List.of("bob@firm2.com"),            // BCC: Firm 2
                        baseTime.plusSeconds(7200)),
                
                // Email 4: Firm 1 participant in BCC, Firm 2 participant in TO
                // This should be found when searching for Firm 2 participant from Firm 1 admin perspective
                // because Firm 1 participant (even in BCC) can see all recipients
                new EmailDocument("email4", "Strategic Planning", "Future roadmap discussion",
                        "external@other.com",                 // FROM: External
                        List.of("bob@firm2.com"),            // TO: Firm 2
                        List.of(),                            // CC: empty
                        List.of("alice@firm1.com"),          // BCC: Firm 1
                        baseTime.plusSeconds(10800)),
                
                // Email 5: Only Firm 2 participants, no Firm 1 involvement
                // This should NOT be found when searching for Firm 2 participant from Firm 1 admin perspective
                new EmailDocument("email5", "Internal Firm 2 Discussion", "Private firm 2 matters",
                        "bob@firm2.com",                      // FROM: Firm 2
                        List.of("charlie@firm2.com"),        // TO: Firm 2
                        List.of(),                            // CC: empty
                        List.of(),                            // BCC: empty
                        baseTime.plusSeconds(14400)),
                
                // Email 6: Firm 1 participants only, no Firm 2 involvement
                // This should NOT be found when searching for Firm 2 participant
                new EmailDocument("email6", "Internal Firm 1 Discussion", "Private firm 1 matters",
                        "alice@firm1.com",                    // FROM: Firm 1
                        List.of("david@firm1.com"),          // TO: Firm 1
                        List.of(),                            // CC: empty
                        List.of(),                            // BCC: empty
                        baseTime.plusSeconds(18000))
        );
        
        System.out.println("[DEBUG_LOG] Indexing " + testEmails.size() + " test emails");
        indexService.indexAll(testEmails);
        
        // Test Case: Firm 1 admin searching for Firm 2 participant (bob@firm2.com)
        System.out.println("[DEBUG_LOG] Searching for Firm 2 participant from Firm 1 admin perspective");
        
        SearchQuery query = new SearchQuery(
                baseTime.minusSeconds(3600),       // start
                baseTime.plusSeconds(25200),       // end  
                null,                              // query (match all)
                List.of("bob@firm2.com"),         // participant emails (Firm 2)
                "firm1.com",                      // admin firm domain (Firm 1 admin)
                0,                                // page
                10,                               // size
                null,                             // facet fields
                null                              // facet queries
        );
        
        List<EmailDocument> results = searchService.search(query);
        
        System.out.println("[DEBUG_LOG] Found " + results.size() + " emails");
        for (EmailDocument email : results) {
            System.out.println("[DEBUG_LOG] - " + email.id() + ": " + email.subject());
        }
        
        // Verify results
        assertThat(results).hasSize(3); // Should find emails 1, 2, and 4 (email3 should be hidden)
        
        // Verify each expected email is found
        List<String> resultIds = results.stream().map(EmailDocument::id).toList();
        assertThat(resultIds).containsExactlyInAnyOrder("email1", "email2", "email4");
        
        // Verify email3 (cross-firm BCC without sender privilege) is NOT found
        assertThat(resultIds).doesNotContain("email3");
        
        // Verify email5 (no Firm 1 involvement) is NOT found
        assertThat(resultIds).doesNotContain("email5");
        
        // Verify email6 (no Firm 2 involvement) is NOT found
        assertThat(resultIds).doesNotContain("email6");
        
        System.out.println("[DEBUG_LOG] ✅ Cross-firm BCC visibility test passed!");
        System.out.println("[DEBUG_LOG] ✅ Firm 1 admin can see emails where:");
        System.out.println("[DEBUG_LOG]   - Firm 2 participant in FROM/TO/CC + Firm 1 participant anywhere");
        System.out.println("[DEBUG_LOG]   - Firm 2 participant in BCC + Firm 1 participant as sender");
        System.out.println("[DEBUG_LOG] ❌ Firm 1 admin CANNOT see emails where:");
        System.out.println("[DEBUG_LOG]   - Firm 2 participant in BCC + Firm 1 participant not sender (like email3)");
    }

    @Test
    void crossFirmBccVisibilityReverseScenario() {
        System.out.println("[DEBUG_LOG] Testing reverse cross-firm BCC visibility scenario");
        
        Instant baseTime = Instant.parse("2025-01-15T10:00:00Z");
        
        // Same emails as above but now test from Firm 2 admin perspective
        List<EmailDocument> testEmails = List.of(
                new EmailDocument("email1", "Project Discussion", "Let's discuss the project timeline",
                        "alice@firm1.com",                    // FROM: Firm 1
                        List.of("bob@firm2.com"),             // TO: Firm 2
                        List.of(),                            // CC: empty
                        List.of(),                            // BCC: empty
                        baseTime),
                
                new EmailDocument("email2", "Meeting Invite", "Please join our meeting",
                        "external@other.com",                 // FROM: External
                        List.of("alice@firm1.com"),          // TO: Firm 1
                        List.of("bob@firm2.com"),            // CC: Firm 2
                        List.of(),                            // BCC: empty
                        baseTime.plusSeconds(3600)),
                
                new EmailDocument("email3", "Confidential Update", "Internal company update",
                        "external@other.com",                 // FROM: External
                        List.of("external2@other.com"),      // TO: External
                        List.of("alice@firm1.com"),          // CC: Firm 1
                        List.of("bob@firm2.com"),            // BCC: Firm 2
                        baseTime.plusSeconds(7200))
        );
        
        indexService.indexAll(testEmails);
        
        // Test Case: Firm 2 admin searching for Firm 1 participant (alice@firm1.com)
        System.out.println("[DEBUG_LOG] Searching for Firm 1 participant from Firm 2 admin perspective");
        
        SearchQuery query = new SearchQuery(
                baseTime.minusSeconds(3600),       // start
                baseTime.plusSeconds(25200),       // end  
                null,                              // query (match all)
                List.of("alice@firm1.com"),       // participant emails (Firm 1)
                "firm2.com",                      // admin firm domain (Firm 2 admin)
                0,                                // page
                10,                               // size
                null,                             // facet fields
                null                              // facet queries
        );
        
        List<EmailDocument> results = searchService.search(query);
        
        System.out.println("[DEBUG_LOG] Found " + results.size() + " emails");
        for (EmailDocument email : results) {
            System.out.println("[DEBUG_LOG] - " + email.id() + ": " + email.subject());
        }
        
        // Should find emails where:
        // - Firm 2 participant is involved AND Firm 1 participant is involved
        // Based on new cross-firm logic:
        // - email1: alice@firm1.com is in FROM, bob@firm2.com in TO -> Should be found (Cross-firm participant in visible field + admin firm participation)
        // - email2: alice@firm1.com is in TO, bob@firm2.com in CC -> Should be found (Cross-firm participant in visible field + admin firm participation)  
        // - email3: alice@firm1.com is in CC, bob@firm2.com in BCC -> Should be found (Cross-firm participant in visible field + admin firm participation)
        assertThat(results).hasSize(3); // Should find all emails 1, 2, and 3
        
        List<String> resultIds = results.stream().map(EmailDocument::id).toList();
        assertThat(resultIds).containsExactlyInAnyOrder("email1", "email2", "email3");
        
        System.out.println("[DEBUG_LOG] ✅ Reverse cross-firm BCC visibility test passed!");
    }
}
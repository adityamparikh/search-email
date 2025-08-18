package dev.aparikh.searchemail.search;

import dev.aparikh.searchemail.model.EmailDocument;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmailSearchServiceTest {

    @Mock
    private SolrClient solrClient;

    @Mock
    private QueryResponse queryResponse;

    private EmailSearchService searchService;

    @BeforeEach
    void setUp() {
        searchService = new EmailSearchService(solrClient);
    }

    @Test
    void searchUsesProvidedQuery() throws Exception {
        setupMockResponse();
        Instant start = Instant.parse("2025-01-01T10:00:00Z");
        Instant end = Instant.parse("2025-01-01T11:00:00Z");
        
        SearchQuery query = createSearchQuery(start, end, "subject:meeting", null, "domain.com");
        searchService.search(query);
        
        ArgumentCaptor<SolrQuery> captor = ArgumentCaptor.forClass(SolrQuery.class);
        verify(solrClient).query(captor.capture());
        
        SolrQuery solrQuery = captor.getValue();
        assertThat(solrQuery.getQuery()).isEqualTo("subject:meeting");
    }

    @Test
    void searchUsesMatchAllWhenQueryIsNull() throws Exception {
        setupMockResponse();
        Instant start = Instant.parse("2025-01-01T10:00:00Z");
        Instant end = Instant.parse("2025-01-01T11:00:00Z");
        
        SearchQuery query = createSearchQuery(start, end, null, null, "domain.com");
        searchService.search(query);
        
        ArgumentCaptor<SolrQuery> captor = ArgumentCaptor.forClass(SolrQuery.class);
        verify(solrClient).query(captor.capture());
        
        SolrQuery solrQuery = captor.getValue();
        assertThat(solrQuery.getQuery()).isEqualTo("*:*");
    }

    @Test
    void searchAddsTimeRangeFilter() throws Exception {
        setupMockResponse();
        Instant start = Instant.parse("2025-01-01T10:00:00Z");
        Instant end = Instant.parse("2025-01-01T11:00:00Z");
        
        SearchQuery query = createSearchQuery(start, end, null, null, "domain.com");
        searchService.search(query);
        
        ArgumentCaptor<SolrQuery> captor = ArgumentCaptor.forClass(SolrQuery.class);
        verify(solrClient).query(captor.capture());
        
        SolrQuery solrQuery = captor.getValue();
        String[] filterQueries = solrQuery.getFilterQueries();
        assertThat(filterQueries).containsExactly("sent_at:[2025-01-01T10:00:00Z TO 2025-01-01T11:00:00Z]");
    }

    @Test
    void searchAddsParticipantFilterWithoutBccWhenDomainsDiffer() throws Exception {
        setupMockResponse();
        Instant start = Instant.parse("2025-01-01T10:00:00Z");
        Instant end = Instant.parse("2025-01-01T11:00:00Z");
        
        SearchQuery query = createSearchQuery(start, end, null, "alice@other.com", "domain.com");
        searchService.search(query);
        
        ArgumentCaptor<SolrQuery> captor = ArgumentCaptor.forClass(SolrQuery.class);
        verify(solrClient).query(captor.capture());
        
        SolrQuery solrQuery = captor.getValue();
        String[] filterQueries = solrQuery.getFilterQueries();
        assertThat(filterQueries).hasSize(2);
        assertThat(filterQueries[1]).isEqualTo("(from_addr:\"alice@other.com\" OR to_addr:\"alice@other.com\" OR cc_addr:\"alice@other.com\")");
    }

    @Test
    void searchAddsParticipantFilterWithBccWhenDomainsMatch() throws Exception {
        setupMockResponse();
        Instant start = Instant.parse("2025-01-01T10:00:00Z");
        Instant end = Instant.parse("2025-01-01T11:00:00Z");
        
        SearchQuery query = createSearchQuery(start, end, null, "alice@domain.com", "domain.com");
        searchService.search(query);
        
        ArgumentCaptor<SolrQuery> captor = ArgumentCaptor.forClass(SolrQuery.class);
        verify(solrClient).query(captor.capture());
        
        SolrQuery solrQuery = captor.getValue();
        String[] filterQueries = solrQuery.getFilterQueries();
        assertThat(filterQueries).hasSize(2);
        assertThat(filterQueries[1]).isEqualTo("(from_addr:\"alice@domain.com\" OR to_addr:\"alice@domain.com\" OR cc_addr:\"alice@domain.com\" OR bcc_addr:\"alice@domain.com\")");
    }

    @Test
    void searchDoesNotAddParticipantFilterWhenParticipantIsNull() throws Exception {
        setupMockResponse();
        Instant start = Instant.parse("2025-01-01T10:00:00Z");
        Instant end = Instant.parse("2025-01-01T11:00:00Z");
        
        SearchQuery query = createSearchQuery(start, end, null, null, "domain.com");
        searchService.search(query);
        
        ArgumentCaptor<SolrQuery> captor = ArgumentCaptor.forClass(SolrQuery.class);
        verify(solrClient).query(captor.capture());
        
        SolrQuery solrQuery = captor.getValue();
        String[] filterQueries = solrQuery.getFilterQueries();
        assertThat(filterQueries).hasSize(1); // Only time range filter
    }

    @Test
    void searchSetsRowLimit() throws Exception {
        setupMockResponse();
        Instant start = Instant.parse("2025-01-01T10:00:00Z");
        Instant end = Instant.parse("2025-01-01T11:00:00Z");
        
        SearchQuery query = createSearchQuery(start, end, null, null, "domain.com");
        searchService.search(query);
        
        ArgumentCaptor<SolrQuery> captor = ArgumentCaptor.forClass(SolrQuery.class);
        verify(solrClient).query(captor.capture());
        
        SolrQuery solrQuery = captor.getValue();
        assertThat(solrQuery.getRows()).isEqualTo(100);
    }

    @Test
    void searchConvertsDocumentsToEmailDocuments() throws Exception {
        SolrDocumentList docs = new SolrDocumentList();
        SolrDocument doc = new SolrDocument();
        doc.setField(EmailDocument.FIELD_ID, "test-id");
        doc.setField(EmailDocument.FIELD_SUBJECT, "Test Subject");
        doc.setField(EmailDocument.FIELD_BODY, "Test Body");
        doc.setField(EmailDocument.FIELD_FROM, "from@test.com");
        doc.setField(EmailDocument.FIELD_TO, List.of("to@test.com"));
        doc.setField(EmailDocument.FIELD_CC, List.of("cc@test.com"));
        doc.setField(EmailDocument.FIELD_BCC, List.of("bcc@test.com"));
        doc.setField(EmailDocument.FIELD_SENT_AT, Date.from(Instant.parse("2025-01-01T10:00:00Z")));
        docs.add(doc);
        
        when(queryResponse.getResults()).thenReturn(docs);
        when(solrClient.query(any(SolrQuery.class))).thenReturn(queryResponse);
        
        Instant start = Instant.parse("2025-01-01T10:00:00Z");
        Instant end = Instant.parse("2025-01-01T11:00:00Z");
        SearchQuery query = createSearchQuery(start, end, null, null, "domain.com");
        
        List<EmailDocument> results = searchService.search(query);
        
        assertThat(results).hasSize(1);
        EmailDocument email = results.get(0);
        assertThat(email.id()).isEqualTo("test-id");
        assertThat(email.subject()).isEqualTo("Test Subject");
        assertThat(email.body()).isEqualTo("Test Body");
        assertThat(email.from()).isEqualTo("from@test.com");
        assertThat(email.to()).containsExactly("to@test.com");
        assertThat(email.cc()).containsExactly("cc@test.com");
        assertThat(email.bcc()).containsExactly("bcc@test.com");
        assertThat(email.sentAt()).isEqualTo(Instant.parse("2025-01-01T10:00:00Z"));
    }

    @Test
    void searchHandlesArrayListFieldValues() throws Exception {
        SolrDocumentList docs = new SolrDocumentList();
        SolrDocument doc = new SolrDocument();
        doc.setField(EmailDocument.FIELD_ID, "test-id");
        doc.setField(EmailDocument.FIELD_SUBJECT, new ArrayList<>(List.of("Subject in ArrayList")));
        docs.add(doc);
        
        when(queryResponse.getResults()).thenReturn(docs);
        when(solrClient.query(any(SolrQuery.class))).thenReturn(queryResponse);
        
        Instant start = Instant.parse("2025-01-01T10:00:00Z");
        Instant end = Instant.parse("2025-01-01T11:00:00Z");
        SearchQuery query = createSearchQuery(start, end, null, null, "domain.com");
        
        List<EmailDocument> results = searchService.search(query);
        
        assertThat(results).hasSize(1);
        assertThat(results.get(0).subject()).isEqualTo("Subject in ArrayList");
    }

    @Test
    void searchHandlesInstantFromString() throws Exception {
        SolrDocumentList docs = new SolrDocumentList();
        SolrDocument doc = new SolrDocument();
        doc.setField(EmailDocument.FIELD_ID, "test-id");
        doc.setField(EmailDocument.FIELD_SENT_AT, "2025-01-01T10:00:00Z");
        docs.add(doc);
        
        when(queryResponse.getResults()).thenReturn(docs);
        when(solrClient.query(any(SolrQuery.class))).thenReturn(queryResponse);
        
        Instant start = Instant.parse("2025-01-01T10:00:00Z");
        Instant end = Instant.parse("2025-01-01T11:00:00Z");
        SearchQuery query = createSearchQuery(start, end, null, null, "domain.com");
        
        List<EmailDocument> results = searchService.search(query);
        
        assertThat(results).hasSize(1);
        assertThat(results.get(0).sentAt()).isEqualTo(Instant.parse("2025-01-01T10:00:00Z"));
    }

    @Test
    void searchWrapsExceptionFromSolr() throws Exception {
        when(solrClient.query(any(SolrQuery.class))).thenThrow(new RuntimeException("Solr error"));
        
        Instant start = Instant.parse("2025-01-01T10:00:00Z");
        Instant end = Instant.parse("2025-01-01T11:00:00Z");
        SearchQuery query = createSearchQuery(start, end, null, null, "domain.com");
        
        assertThatThrownBy(() -> searchService.search(query))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Search failed")
                .hasCauseInstanceOf(RuntimeException.class);
    }

    @Test
    void searchEscapesSpecialCharactersInParticipantEmail() throws Exception {
        setupMockResponse();
        Instant start = Instant.parse("2025-01-01T10:00:00Z");
        Instant end = Instant.parse("2025-01-01T11:00:00Z");
        
        SearchQuery query = createSearchQuery(start, end, null, "user+test@domain.com", "domain.com");
        searchService.search(query);
        
        ArgumentCaptor<SolrQuery> captor = ArgumentCaptor.forClass(SolrQuery.class);
        verify(solrClient).query(captor.capture());
        
        SolrQuery solrQuery = captor.getValue();
        String[] filterQueries = solrQuery.getFilterQueries();
        assertThat(filterQueries[1]).contains("\"user\\+test@domain.com\"");
    }

    @Test
    void searchHandlesMultipleParticipants() throws Exception {
        setupMockResponse();
        Instant start = Instant.parse("2025-01-01T10:00:00Z");
        Instant end = Instant.parse("2025-01-01T11:00:00Z");

        SearchQuery query = new SearchQuery(start, end, null, List.of("alice@acme.com", "bob@other.com", "charlie@acme.com"), "acme.com", 0, 100);
        searchService.search(query);
        
        ArgumentCaptor<SolrQuery> captor = ArgumentCaptor.forClass(SolrQuery.class);
        verify(solrClient).query(captor.capture());
        
        SolrQuery solrQuery = captor.getValue();
        String[] filterQueries = solrQuery.getFilterQueries();
        assertThat(filterQueries).hasSize(2);
        String participantFilter = filterQueries[1];
        
        // Should contain all three participants with appropriate field access
        assertThat(participantFilter).contains("alice@acme.com");
        assertThat(participantFilter).contains("bob@other.com"); 
        assertThat(participantFilter).contains("charlie@acme.com");
        
        // Should include BCC for acme.com participants (alice and charlie) but not bob@other.com
        assertThat(participantFilter).contains("bcc_addr:\"alice@acme.com\"");
        assertThat(participantFilter).contains("bcc_addr:\"charlie@acme.com\"");
        assertThat(participantFilter).doesNotContain("bcc_addr:\"bob@other.com\"");
        
        // Should combine participants with OR
        assertThat(participantFilter).contains(" OR ");
    }

    @Test
    void searchAppliesPaginationParameters() throws Exception {
        setupMockResponse();
        Instant start = Instant.parse("2025-01-01T10:00:00Z");
        Instant end = Instant.parse("2025-01-01T11:00:00Z");

        SearchQuery query = new SearchQuery(start, end, null, null, "domain.com", 2, 50); // page 2, size 50
        searchService.search(query);

        ArgumentCaptor<SolrQuery> captor = ArgumentCaptor.forClass(SolrQuery.class);
        verify(solrClient).query(captor.capture());

        SolrQuery solrQuery = captor.getValue();
        assertThat(solrQuery.getRows()).isEqualTo(50); // size
        assertThat(solrQuery.getStart()).isEqualTo(100); // page 2 * size 50 = start at 100
    }

    private void setupMockResponse() throws Exception {
        when(queryResponse.getResults()).thenReturn(new SolrDocumentList());
        when(solrClient.query(any(SolrQuery.class))).thenReturn(queryResponse);
    }

    // Helper method to create SearchQuery with single participant
    private SearchQuery createSearchQuery(Instant start, Instant end, String query, String participantEmail, String adminFirmDomain) {
        List<String> participants = participantEmail != null ? List.of(participantEmail) : null;
        return new SearchQuery(start, end, query, participants, adminFirmDomain, 0, 100);
    }
}
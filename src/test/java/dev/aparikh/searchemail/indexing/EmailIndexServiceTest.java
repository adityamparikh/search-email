package dev.aparikh.searchemail.indexing;

import dev.aparikh.searchemail.model.EmailDocument;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.common.SolrInputDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EmailIndexServiceTest {

    @Mock
    private SolrClient solrClient;

    private EmailIndexService indexService;

    @BeforeEach
    void setUp() {
        indexService = new EmailIndexService(solrClient);
    }

    @Test
    void indexSingleEmailCallsIndexAll() throws Exception {
        EmailDocument email = createTestEmail();
        
        indexService.index(email);
        
        ArgumentCaptor<List<SolrInputDocument>> captor = ArgumentCaptor.forClass(List.class);
        verify(solrClient).add(captor.capture());
        verify(solrClient).commit();
        
        List<SolrInputDocument> docs = captor.getValue();
        assertThat(docs).hasSize(1);
        verifyDocumentFields(docs.get(0), email);
    }

    @Test
    void indexAllWithNullListDoesNothing() throws Exception {
        indexService.indexAll(null);
        
        verify(solrClient, never()).add(anyList());
        verify(solrClient, never()).commit();
    }

    @Test
    void indexAllWithEmptyListDoesNothing() throws Exception {
        indexService.indexAll(List.of());
        
        verify(solrClient, never()).add(anyList());
        verify(solrClient, never()).commit();
    }

    @Test
    void indexAllWithMultipleEmailsProcessesAll() throws Exception {
        EmailDocument email1 = new EmailDocument("1", "Subject 1", "Body 1", "from1@test.com", 
                List.of("to1@test.com"), List.of(), List.of(), Instant.parse("2025-01-01T10:00:00Z"));
        EmailDocument email2 = new EmailDocument("2", "Subject 2", "Body 2", "from2@test.com", 
                List.of("to2@test.com"), List.of(), List.of(), Instant.parse("2025-01-01T11:00:00Z"));
        
        indexService.indexAll(List.of(email1, email2));
        
        ArgumentCaptor<List<SolrInputDocument>> captor = ArgumentCaptor.forClass(List.class);
        verify(solrClient).add(captor.capture());
        verify(solrClient).commit();
        
        List<SolrInputDocument> docs = captor.getValue();
        assertThat(docs).hasSize(2);
    }

    @Test
    void indexNormalizesEmailAddressesToLowercase() throws Exception {
        EmailDocument email = new EmailDocument("1", "Subject", "Body", "FROM@TEST.COM", 
                List.of("TO@TEST.COM"), List.of("CC@TEST.COM"), List.of("BCC@TEST.COM"), 
                Instant.parse("2025-01-01T10:00:00Z"));
        
        indexService.index(email);
        
        ArgumentCaptor<List<SolrInputDocument>> captor = ArgumentCaptor.forClass(List.class);
        verify(solrClient).add(captor.capture());
        
        SolrInputDocument doc = captor.getValue().get(0);
        assertThat(doc.getFieldValue("from_addr")).isEqualTo("from@test.com");
        assertThat(doc.getFieldValues("to_addr")).containsExactly("to@test.com");
        assertThat(doc.getFieldValues("cc_addr")).containsExactly("cc@test.com");
        assertThat(doc.getFieldValues("bcc_addr")).containsExactly("bcc@test.com");
    }

    @Test
    void indexHandlesNullFields() throws Exception {
        EmailDocument email = new EmailDocument("1", null, null, null, null, null, null, null);
        
        indexService.index(email);
        
        ArgumentCaptor<List<SolrInputDocument>> captor = ArgumentCaptor.forClass(List.class);
        verify(solrClient).add(captor.capture());
        
        SolrInputDocument doc = captor.getValue().get(0);
        assertThat(doc.getFieldValue("id")).isEqualTo("1");
        assertThat(doc.getFieldValue("subject")).isNull();
        assertThat(doc.getFieldValue("body")).isNull();
        assertThat(doc.getFieldValue("from_addr")).isNull();
        assertThat(doc.getFieldValues("to_addr")).isNull();
        assertThat(doc.getFieldValues("cc_addr")).isNull();
        assertThat(doc.getFieldValues("bcc_addr")).isNull();
        assertThat(doc.getFieldValue("sent_at")).isNull();
    }

    @Test
    void indexFiltersBlankEmailAddresses() throws Exception {
        EmailDocument email = new EmailDocument("1", "Subject", "Body", "from@test.com", 
                List.of("valid@test.com", "", "  ", "another@test.com"), 
                List.of(), List.of(), Instant.parse("2025-01-01T10:00:00Z"));
        
        indexService.index(email);
        
        ArgumentCaptor<List<SolrInputDocument>> captor = ArgumentCaptor.forClass(List.class);
        verify(solrClient).add(captor.capture());
        
        SolrInputDocument doc = captor.getValue().get(0);
        assertThat(doc.getFieldValues("to_addr")).containsExactly("valid@test.com", "another@test.com");
    }

    @Test
    void indexWrapsExceptionFromSolr() throws Exception {
        when(solrClient.add(any(List.class))).thenThrow(new RuntimeException("Solr error"));
        
        EmailDocument email = createTestEmail();
        
        assertThatThrownBy(() -> indexService.index(email))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Failed to index emails")
                .hasCauseInstanceOf(RuntimeException.class);
    }

    @Test
    void indexWrapsExceptionFromCommit() throws Exception {
        doThrow(new RuntimeException("Commit error")).when(solrClient).commit();
        
        EmailDocument email = createTestEmail();
        
        assertThatThrownBy(() -> indexService.index(email))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Failed to index emails")
                .hasCauseInstanceOf(RuntimeException.class);
    }

    private EmailDocument createTestEmail() {
        return new EmailDocument("test-id", "Test Subject", "Test Body", "from@test.com",
                List.of("to@test.com"), List.of("cc@test.com"), List.of("bcc@test.com"),
                Instant.parse("2025-01-01T10:00:00Z"));
    }

    private void verifyDocumentFields(SolrInputDocument doc, EmailDocument email) {
        assertThat(doc.getFieldValue("id")).isEqualTo(email.id());
        assertThat(doc.getFieldValue("subject")).isEqualTo(email.subject());
        assertThat(doc.getFieldValue("body")).isEqualTo(email.body());
        assertThat(doc.getFieldValue("from_addr")).isEqualTo(email.from().toLowerCase());
        if (email.sentAt() != null) {
            assertThat(doc.getFieldValue("sent_at")).isEqualTo(java.util.Date.from(email.sentAt()));
        }
    }
}
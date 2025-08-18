package dev.aparikh.searchemail.indexing;

import dev.aparikh.searchemail.model.EmailDocument;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.common.SolrInputDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

@Service
@ConditionalOnBean(SolrClient.class)
public class EmailIndexService {

    private static final Logger log = LoggerFactory.getLogger(EmailIndexService.class);

    private final SolrClient solr;

    public EmailIndexService(SolrClient solr) {
        this.solr = solr;
    }

    public void index(EmailDocument email) {
        indexAll(Collections.singletonList(email));
    }

    public void indexAll(List<EmailDocument> emails) {
        if (emails == null || emails.isEmpty()) return;
        try {
            List<SolrInputDocument> docs = emails.stream()
                    .map(this::toSolrDoc)
                    .toList();
            solr.add(docs);
            solr.commit();
        } catch (Exception e) {
            throw new RuntimeException("Failed to index emails", e);
        }
    }

    private SolrInputDocument toSolrDoc(EmailDocument e) {
        SolrInputDocument d = new SolrInputDocument();
        d.addField(EmailDocument.FIELD_ID, e.id());
        if (e.subject() != null) d.addField(EmailDocument.FIELD_SUBJECT, e.subject());
        if (e.body() != null) d.addField(EmailDocument.FIELD_BODY, e.body());
        if (e.from() != null) d.addField(EmailDocument.FIELD_FROM, lower(e.from()));
        addAll(d, EmailDocument.FIELD_TO, e.to());
        addAll(d, EmailDocument.FIELD_CC, e.cc());
        addAll(d, EmailDocument.FIELD_BCC, e.bcc());
        if (e.sentAt() != null) d.addField(EmailDocument.FIELD_SENT_AT, java.util.Date.from(e.sentAt()));
        return d;
    }

    private static void addAll(SolrInputDocument d, String field, List<String> values) {
        if (values == null) return;
        for (String v : values) {
            if (v != null && !v.isBlank()) d.addField(field, lower(v));
        }
    }

    private static String lower(String s) {
        return s == null ? null : s.toLowerCase(Locale.ROOT);
    }
}
package dev.aparikh.searchemail.search;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrInputDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.apache.solr.client.solrj.util.ClientUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@ConditionalOnBean(SolrClient.class)
class EmailSearchService {

    private static final Logger log = LoggerFactory.getLogger(EmailSearchService.class);

    // Field names in Solr schema
    static final String FIELD_ID = "id";
    static final String FIELD_SUBJECT = "subject";
    static final String FIELD_BODY = "body";
    static final String FIELD_FROM = "from_addr";
    static final String FIELD_TO = "to_addr";
    static final String FIELD_CC = "cc_addr";
    static final String FIELD_BCC = "bcc_addr";
    static final String FIELD_SENT_AT = "sent_at";

    private final SolrClient solr;

    EmailSearchService(SolrClient solr) {
        this.solr = solr;
    }

    void index(EmailDocument email) {
        indexAll(Collections.singletonList(email));
    }

    void indexAll(List<EmailDocument> emails) {
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

    List<EmailDocument> search(SearchQuery query) {
        try {
            SolrQuery q = new SolrQuery();
            Optional<String> participant = query.participantEmailOpt();

            // Base query: match all, narrow via filters
            q.setQuery("*:*");

            // Time range filter
            String start = formatInstant(query.start());
            String end = formatInstant(query.end());
            q.addFilterQuery(FIELD_SENT_AT + ":[" + start + " TO " + end + "]");

            // Participant filter across allowed fields
            if (participant.isPresent()) {
                String term = ClientUtils.escapeQueryChars(participant.get().toLowerCase(Locale.ROOT));
                List<String> fields = new ArrayList<>();
                fields.add(FIELD_FROM);
                fields.add(FIELD_TO);
                fields.add(FIELD_CC);
                // BCC allowed only if admin firm domain matches participant's domain
                if (sameDomain(participant.get(), query.adminFirmDomain())) {
                    fields.add(FIELD_BCC);
                }
                String orExpr = fields.stream()
                        .map(f -> f + ":\"" + term + "\"")
                        .collect(Collectors.joining(" OR "));
                q.addFilterQuery(orExpr);
            }

            q.setRows(100);
            QueryResponse resp = solr.query(q);
            return resp.getResults().stream().map(this::fromSolrDoc).toList();
        } catch (Exception e) {
            throw new RuntimeException("Search failed", e);
        }
    }

    private static String formatInstant(Instant instant) {
        return instant.toString(); // ISO-8601 with Z accepted by Solr
    }

    private static boolean sameDomain(String email, String adminFirmDomain) {
        if (email == null || adminFirmDomain == null) return false;
        String domain = emailDomain(email);
        return domain.equalsIgnoreCase(adminFirmDomain);
    }

    private static String emailDomain(String email) {
        int at = email.lastIndexOf('@');
        if (at == -1 || at == email.length() - 1) return "";
        return email.substring(at + 1).toLowerCase(Locale.ROOT);
    }

    private static String lower(String s) {
        return s == null ? null : s.toLowerCase(Locale.ROOT);
    }

    private SolrInputDocument toSolrDoc(EmailDocument e) {
        SolrInputDocument d = new SolrInputDocument();
        d.addField(FIELD_ID, e.id());
        if (e.subject() != null) d.addField(FIELD_SUBJECT, e.subject());
        if (e.body() != null) d.addField(FIELD_BODY, e.body());
        if (e.from() != null) d.addField(FIELD_FROM, lower(e.from()));
        addAll(d, FIELD_TO, e.to());
        addAll(d, FIELD_CC, e.cc());
        addAll(d, FIELD_BCC, e.bcc());
        if (e.sentAt() != null) d.addField(FIELD_SENT_AT, java.util.Date.from(e.sentAt()));
        return d;
    }

    private static void addAll(SolrInputDocument d, String field, List<String> values) {
        if (values == null) return;
        for (String v : values) {
            if (v != null && !v.isBlank()) d.addField(field, lower(v));
        }
    }

    @SuppressWarnings("unchecked")
    private EmailDocument fromSolrDoc(SolrDocument d) {
        String id = getFieldAsString(d, FIELD_ID);
        String subject = getFieldAsString(d, FIELD_SUBJECT);
        String body = getFieldAsString(d, FIELD_BODY);
        String from = getFieldAsString(d, FIELD_FROM);
        List<String> to = toList(d.getFieldValues(FIELD_TO));
        List<String> cc = toList(d.getFieldValues(FIELD_CC));
        List<String> bcc = toList(d.getFieldValues(FIELD_BCC));
        Object dateObj = d.getFieldValue(FIELD_SENT_AT);
        Instant sentAt = null;
        if (dateObj instanceof java.util.Date) {
            sentAt = ((java.util.Date) dateObj).toInstant();
        } else if (dateObj instanceof String s) {
            sentAt = Instant.parse(s);
        }
        return new EmailDocument(id, subject, body, from, to, cc, bcc, sentAt);
    }
    
    private static String getFieldAsString(SolrDocument d, String fieldName) {
        Object value = d.getFieldValue(fieldName);
        if (value == null) return null;
        if (value instanceof String) return (String) value;
        if (value instanceof Collection<?> collection && !collection.isEmpty()) {
            return String.valueOf(collection.iterator().next());
        }
        return String.valueOf(value);
    }

    private static List<String> toList(Collection<?> values) {
        if (values == null) return List.of();
        return values.stream()
                .filter(Objects::nonNull)
                .map(Object::toString)
                .collect(Collectors.toList());
    }
}

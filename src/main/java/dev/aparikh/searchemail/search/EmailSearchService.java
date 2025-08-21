package dev.aparikh.searchemail.search;

import dev.aparikh.searchemail.model.EmailDocument;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.response.FacetField;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.client.solrj.util.ClientUtils;
import org.apache.solr.common.SolrDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.*;

@Service
@ConditionalOnBean(SolrClient.class)
public class EmailSearchService {

    private static final Logger log = LoggerFactory.getLogger(EmailSearchService.class);

    private final SolrClient solr;

    EmailSearchService(SolrClient solr) {
        this.solr = solr;
    }


    private static List<String> toList(Collection<?> values) {
        if (values == null) return List.of();
        return values.stream()
                .filter(Objects::nonNull)
                .map(Object::toString)
                .toList();
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

    private static String getFieldAsString(SolrDocument d, String fieldName) {
        Object value = d.getFieldValue(fieldName);
        if (value == null) return null;
        if (value instanceof String) return (String) value;
        if (value instanceof Collection<?> collection && !collection.isEmpty()) {
            return String.valueOf(collection.iterator().next());
        }
        return String.valueOf(value);
    }

    public long getHitCount(SearchQuery query) {
        try {
            SolrQuery q = buildSolrQuery(query);
            q.setRows(0); // We only want the count, no documents
            QueryResponse resp = solr.query(q);
            return resp.getResults().getNumFound();
        } catch (Exception e) {
            throw new RuntimeException("Hit count failed", e);
        }
    }

    public List<EmailDocument> search(SearchQuery query) {
        try {
            SolrQuery q = buildSolrQuery(query);
            q.setRows(query.size());
            q.setStart(query.page() * query.size());
            QueryResponse resp = solr.query(q);
            return resp.getResults().stream().map(this::fromSolrDoc).toList();
        } catch (Exception e) {
            throw new RuntimeException("Search failed", e);
        }
    }

    public SearchResult searchWithFacets(SearchQuery query) {
        try {
            SolrQuery q = buildSolrQuery(query);
            q.setRows(query.size());
            q.setStart(query.page() * query.size());

            // Add faceting configuration
            if (query.facetFields() != null && !query.facetFields().isEmpty()) {
                q.setFacet(true);
                q.setFacetMinCount(1);
                q.setFacetLimit(100);

                for (String facetField : query.facetFields()) {
                    q.addFacetField(facetField);
                }
            }

            QueryResponse resp = solr.query(q);
            List<EmailDocument> emails = resp.getResults().stream().map(this::fromSolrDoc).toList();
            long totalCount = resp.getResults().getNumFound();
            int totalPages = (int) Math.ceil((double) totalCount / query.size());

            Map<String, FacetResult> facets = new HashMap<>();
            if (resp.getFacetFields() != null) {
                for (FacetField facetField : resp.getFacetFields()) {
                    if (facetField.getValues() != null) {
                        List<FacetValue> values = facetField.getValues().stream()
                                .map(count -> new FacetValue(count.getName(), count.getCount()))
                                .toList();
                        facets.put(facetField.getName(), new FacetResult(facetField.getName(), values));
                    }
                }
            }

            return new SearchResult(emails, totalCount, query.page(), query.size(), totalPages, facets);
        } catch (Exception e) {
            throw new RuntimeException("Search with facets failed", e);
        }
    }

    public Flux<EmailDocument> searchStream(SearchQuery query, int batchSize) {
        return Flux.defer(() -> {
            try {
                long totalCount = getHitCount(query);
                int totalPages = (int) Math.ceil((double) totalCount / batchSize);

                return Flux.range(0, Math.max(1, totalPages))
                        .flatMap(page -> {
                            try {
                                SearchQuery pageQuery = new SearchQuery(
                                        query.start(), query.end(), query.query(),
                                        query.participantEmails(), query.adminFirmDomain(),
                                        page, batchSize, query.facetFields()
                                );
                                List<EmailDocument> results = search(pageQuery);
                                return Flux.fromIterable(results);
                            } catch (Exception e) {
                                return Flux.error(new RuntimeException("Stream batch failed for page " + page, e));
                            }
                        });
            } catch (Exception e) {
                return Flux.error(new RuntimeException("Stream setup failed", e));
            }
        });
    }

    @SuppressWarnings("unchecked")
    private EmailDocument fromSolrDoc(SolrDocument d) {
        String id = getFieldAsString(d, EmailDocument.FIELD_ID);
        String subject = getFieldAsString(d, EmailDocument.FIELD_SUBJECT);
        String body = getFieldAsString(d, EmailDocument.FIELD_BODY);
        String from = getFieldAsString(d, EmailDocument.FIELD_FROM);
        List<String> to = toList(d.getFieldValues(EmailDocument.FIELD_TO));
        List<String> cc = toList(d.getFieldValues(EmailDocument.FIELD_CC));
        List<String> bcc = toList(d.getFieldValues(EmailDocument.FIELD_BCC));
        Object dateObj = d.getFieldValue(EmailDocument.FIELD_SENT_AT);
        Instant sentAt = null;
        if (dateObj instanceof java.util.Date) {
            sentAt = ((java.util.Date) dateObj).toInstant();
        } else if (dateObj instanceof String s) {
            sentAt = Instant.parse(s);
        }
        return new EmailDocument(id, subject, body, from, to, cc, bcc, sentAt);
    }

    private SolrQuery buildSolrQuery(SearchQuery query) {
        SolrQuery q = new SolrQuery();
        List<String> participants = query.participantEmailsNonEmpty();

        // Base query: use provided query or match all
        String baseQuery = query.queryOpt().orElse("*:*");
        q.setQuery(baseQuery);

        // Time range filter
        String start = formatInstant(query.start());
        String end = formatInstant(query.end());
        q.addFilterQuery(EmailDocument.FIELD_SENT_AT + ":[" + start + " TO " + end + "]");

        // Participant filter across allowed fields
        if (!participants.isEmpty()) {
            List<String> participantExpressions = new ArrayList<>();

            for (String participant : participants) {
                String term = ClientUtils.escapeQueryChars(participant.toLowerCase(Locale.ROOT));
                List<String> fields = new ArrayList<>();
                fields.add(EmailDocument.FIELD_FROM);
                fields.add(EmailDocument.FIELD_TO);
                fields.add(EmailDocument.FIELD_CC);
                // BCC allowed only if admin firm domain matches participant's domain
                if (sameDomain(participant, query.adminFirmDomain())) {
                    fields.add(EmailDocument.FIELD_BCC);
                }
                String participantExpr = String.join(" OR ", fields.stream()
                        .map(f -> f + ":\"" + term + "\"")
                        .toList());
                participantExpressions.add("(" + participantExpr + ")");
            }

            // Combine all participant expressions with OR
            String allParticipantsExpr = String.join(" OR ", participantExpressions);
            q.addFilterQuery(allParticipantsExpr);
        }

        return q;
    }
}

package dev.aparikh.searchemail.model;

import java.time.Instant;
import java.util.List;

/**
 * Email document to index and fetch from Solr.
 */
public record EmailDocument(
        String id,
        String subject,
        String body,
        String from,
        List<String> to,
        List<String> cc,
        List<String> bcc,
        Instant sentAt
) {
    // Solr field names - centralized constants for use across the application
    public static final String FIELD_ID = "id";
    public static final String FIELD_SUBJECT = "subject";
    public static final String FIELD_BODY = "body";
    public static final String FIELD_FROM = "from_addr";
    public static final String FIELD_TO = "to_addr";
    public static final String FIELD_CC = "cc_addr";
    public static final String FIELD_BCC = "bcc_addr";
    public static final String FIELD_SENT_AT = "sent_at";
}

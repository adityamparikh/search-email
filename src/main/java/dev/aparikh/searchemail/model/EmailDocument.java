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
) {}

package dev.aparikh.searchemail.search;

import dev.aparikh.searchemail.model.EmailDocument;

import java.util.List;
import java.util.Map;

/**
 * Search result containing both email documents and faceting information.
 */
public record SearchResult(
        List<EmailDocument> emails,
        long totalCount,
        int page,
        int size,
        int totalPages,
        Map<String, FacetResult> facets
) {
}
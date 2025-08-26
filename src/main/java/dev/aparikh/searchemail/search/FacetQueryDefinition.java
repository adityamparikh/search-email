package dev.aparikh.searchemail.search;

/**
 * Represents a facet query definition, which allows arbitrary query-based faceting.
 * Unlike field faceting, query faceting allows custom queries with human-readable labels.
 * For example: "External Emails" -> "NOT from_addr:*@acme.com"
 */
public record FacetQueryDefinition(
        String label,
        String query
) {
    public FacetQueryDefinition {
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("Facet query label cannot be null or blank");
        }
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("Facet query cannot be null or blank");
        }
    }
}
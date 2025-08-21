package dev.aparikh.searchemail.search;

/**
 * Represents a single facet value with its count.
 */
public record FacetValue(
        String value,
        long count
) {
}
package dev.aparikh.searchemail.search;

import java.util.List;

/**
 * Represents faceting results for a field, containing value counts.
 */
public record FacetResult(
        String field,
        List<FacetValue> values
) {
}
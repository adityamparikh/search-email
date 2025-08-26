package dev.aparikh.searchemail.api;

import com.fasterxml.jackson.annotation.JsonFormat;
import dev.aparikh.searchemail.search.FacetQueryDefinition;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;

/**
 * Request DTO for email search API.
 */
@Schema(description = "Email search request parameters")
public record SearchRequest(
        @NotNull
        @Schema(description = "Start of time range (inclusive)",
                example = "2025-01-01T00:00:00Z",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'", timezone = "UTC")
        Instant startTime,

        @NotNull
        @Schema(description = "End of time range (inclusive)",
                example = "2025-01-31T23:59:59Z",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'", timezone = "UTC")
        Instant endTime,

        @Schema(description = "Full-text search query (Solr syntax supported)",
                example = "subject:meeting AND body:urgent")
        String query,

        @Schema(description = "Filter by participant email addresses (emails involving any of these participants will be returned)",
                example = "[\"user1@company.com\", \"user2@company.com\"]")
        List<String> participantEmails,

        @NotNull
        @Schema(description = "Admin's firm domain for BCC privacy enforcement",
                example = "company.com",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String adminFirmDomain,

        @Schema(description = "Page number for pagination (0-based)",
                example = "0", defaultValue = "0")
        Integer page,

        @Schema(description = "Number of results per page",
                example = "100", defaultValue = "100")
        Integer size,

        @Schema(description = "Fields to facet on for aggregated counts",
                example = "[\"from_addr\", \"to_addr\"]")
        List<String> facetFields,

        @Schema(description = "Custom facet queries with labels for query-based faceting",
                example = "[{\"label\": \"External Emails\", \"query\": \"NOT from_addr:*@company.com\"}, {\"label\": \"Meeting Related\", \"query\": \"subject:*meeting*\"}]")
        List<FacetQueryDefinition> facetQueries,

        @Schema(description = "Sort criteria for results (Solr syntax)",
                example = "timestamp desc")
        String sort
) {
}
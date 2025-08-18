package dev.aparikh.searchemail.api;

import dev.aparikh.searchemail.model.EmailDocument;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Response DTO for email search API.
 */
@Schema(description = "Email search response")
public record SearchResponse(
        @Schema(description = "List of matching email documents")
        List<EmailDocument> emails,

        @Schema(description = "Total number of matching documents", example = "42")
        long totalCount
) {}
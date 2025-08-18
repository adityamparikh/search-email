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
        long totalCount,

        @Schema(description = "Current page number (0-based)", example = "0")
        int page,

        @Schema(description = "Number of results per page", example = "100")
        int size,

        @Schema(description = "Total number of pages", example = "5")
        int totalPages
) {}
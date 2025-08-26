package dev.aparikh.searchemail.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Response DTO for hit count API.
 */
@Schema(description = "Hit count response")
public record HitCountResponse(
        @Schema(description = "Total number of matching documents", example = "42")
        long count
) {
}
package dev.aparikh.searchemail.api;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * Standard error response for API endpoints.
 */
@Schema(description = "Error response")
public record ErrorResponse(
        @Schema(description = "Error message", example = "Invalid search parameters")
        String message,

        @Schema(description = "Error code", example = "VALIDATION_ERROR")
        String code,

        @Schema(description = "Timestamp of the error", example = "2025-01-01T10:00:00Z")
        Instant timestamp
) {
}
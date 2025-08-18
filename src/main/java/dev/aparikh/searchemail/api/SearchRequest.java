package dev.aparikh.searchemail.api;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

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

        @Schema(description = "Filter by participant email address", 
                example = "user@company.com")
        String participantEmail,

        @NotNull
        @Schema(description = "Admin's firm domain for BCC privacy enforcement", 
                example = "company.com",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String adminFirmDomain
) {}
package dev.aparikh.searchemail.search;

import dev.aparikh.searchemail.api.*;
import dev.aparikh.searchemail.model.EmailDocument;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * REST Controller for email search operations.
 */
@RestController
@RequestMapping("/api/emails")
@Tag(name = "Email Search", description = "Email search, pagination, streaming and hit count operations")
public class EmailSearchController {

    private final EmailSearchService emailSearchService;

    public EmailSearchController(EmailSearchService emailSearchService) {
        this.emailSearchService = emailSearchService;
    }

    @PostMapping(value = "/search", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Search emails with pagination",
            description = "Search for emails based on time range, participants, and optional full-text query. " +
                    "Returns emails involving any of the specified participants with pagination support. " +
                    "BCC visibility is enforced based on admin firm domain. " +
                    "Use 'page' and 'size' parameters for pagination (defaults: page=0, size=100)."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Search completed successfully",
                    content = @Content(schema = @Schema(implementation = SearchResponse.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Invalid search parameters",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "500",
                    description = "Internal server error during search",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    public ResponseEntity<SearchResponse> searchEmails(
            @Parameter(description = "Search request parameters", required = true)
            @Valid @RequestBody SearchRequest request) {
        
        SearchQuery query = toSearchQuery(request);
        List<EmailDocument> emails = emailSearchService.search(query);
        long totalCount = emailSearchService.getHitCount(query);

        int totalPages = (int) Math.ceil((double) totalCount / query.size());
        SearchResponse response = new SearchResponse(emails, totalCount, query.page(), query.size(), totalPages);
        return ResponseEntity.ok(response);
    }

    @PostMapping(value = "/count", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Get hit count",
            description = "Get the total count of emails matching the search criteria without returning the actual documents. " +
                    "This is more efficient when you only need the total count for pagination calculations."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Count retrieved successfully",
                    content = @Content(schema = @Schema(implementation = HitCountResponse.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Invalid search parameters",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "500",
                    description = "Internal server error during count",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    public ResponseEntity<HitCountResponse> getHitCount(
            @Parameter(description = "Search request parameters for counting", required = true)
            @Valid @RequestBody SearchRequest request) {
        
        SearchQuery query = toSearchQuery(request);
        long count = emailSearchService.getHitCount(query);
        
        HitCountResponse response = new HitCountResponse(count);
        return ResponseEntity.ok(response);
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(
            summary = "Stream emails",
            description = "Stream emails in real-time for large data dumps. " +
                    "Returns a stream of email documents matching the search criteria. " +
                    "Suitable for exporting large datasets."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Stream started successfully",
                    content = @Content(mediaType = MediaType.TEXT_EVENT_STREAM_VALUE)
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Invalid search parameters",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "500",
                    description = "Internal server error during streaming",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    public Flux<ServerSentEvent<EmailDocument>> streamEmails(
            @Parameter(description = "Stream search request parameters", required = true)
            @Valid @RequestBody StreamSearchRequest request) {

        SearchQuery query = toStreamSearchQuery(request);
        int batchSize = request.batchSize() != null ? request.batchSize() : 1000;

        return emailSearchService.searchStream(query, batchSize)
                .map(email -> ServerSentEvent.<EmailDocument>builder()
                        .data(email)
                        .build());
    }

    private SearchQuery toSearchQuery(SearchRequest request) {
        int page = request.page() != null ? request.page() : 0;
        int size = request.size() != null ? request.size() : 100;
        return new SearchQuery(
                request.startTime(),
                request.endTime(),
                request.query(),
                request.participantEmails(),
                request.adminFirmDomain(),
                page,
                size
        );
    }

    private SearchQuery toStreamSearchQuery(StreamSearchRequest request) {
        return new SearchQuery(
                request.startTime(),
                request.endTime(),
                request.query(),
                request.participantEmails(),
                request.adminFirmDomain(),
                0, // Always start from page 0 for streaming
                1000 // Default batch size for streaming
        );
    }
}
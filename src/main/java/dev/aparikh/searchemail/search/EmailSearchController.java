package dev.aparikh.searchemail.search;

import dev.aparikh.searchemail.api.ErrorResponse;
import dev.aparikh.searchemail.api.HitCountResponse;
import dev.aparikh.searchemail.api.SearchRequest;
import dev.aparikh.searchemail.api.SearchResponse;
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
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST Controller for email search operations.
 */
@RestController
@RequestMapping("/api/emails")
@Tag(name = "Email Search", description = "Email search and hit count operations")
public class EmailSearchController {

    private final EmailSearchService emailSearchService;

    public EmailSearchController(EmailSearchService emailSearchService) {
        this.emailSearchService = emailSearchService;
    }

    @PostMapping(value = "/search", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Search emails",
            description = "Search for emails based on time range, participant, and optional full-text query. " +
                    "BCC visibility is enforced based on admin firm domain."
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
        
        SearchResponse response = new SearchResponse(emails, totalCount);
        return ResponseEntity.ok(response);
    }

    @PostMapping(value = "/count", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Get hit count",
            description = "Get the total count of emails matching the search criteria without returning the actual documents. " +
                    "This is more efficient for pagination or just knowing the result size."
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

    private SearchQuery toSearchQuery(SearchRequest request) {
        return new SearchQuery(
                request.startTime(),
                request.endTime(),
                request.query(),
                request.participantEmail(),
                request.adminFirmDomain()
        );
    }
}
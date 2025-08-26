package dev.aparikh.searchemail.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aparikh.searchemail.api.SearchRequest;
import dev.aparikh.searchemail.model.EmailDocument;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = EmailSearchController.class)
class EmailSearchControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private EmailSearchService emailSearchService;

    @Test
    void searchEmailsReturnsSuccessResponse() throws Exception {
        Instant now = Instant.parse("2025-01-01T10:00:00Z");
        EmailDocument email = new EmailDocument(
                "1", "Test Subject", "Test Body", "from@test.com",
                List.of("to@test.com"), List.of(), List.of(), now
        );

        when(emailSearchService.search(any(SearchQuery.class))).thenReturn(List.of(email));
        when(emailSearchService.getHitCount(any(SearchQuery.class))).thenReturn(1L);

        SearchRequest request = new SearchRequest(
                now.minusSeconds(3600),
                now.plusSeconds(3600),
                "test query",
                List.of("user@test.com"),
                "test.com",
                null,
                null,
                null,
                null,
                null
        );

        mockMvc.perform(post("/api/emails/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.emails").isArray())
                .andExpect(jsonPath("$.emails[0].id").value("1"))
                .andExpect(jsonPath("$.emails[0].subject").value("Test Subject"))
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(100))
                .andExpect(jsonPath("$.totalPages").value(1));

        verify(emailSearchService).search(any(SearchQuery.class));
        verify(emailSearchService).getHitCount(any(SearchQuery.class));
    }

    @Test
    void getHitCountReturnsCount() throws Exception {
        Instant now = Instant.parse("2025-01-01T10:00:00Z");
        when(emailSearchService.getHitCount(any(SearchQuery.class))).thenReturn(42L);

        SearchRequest request = new SearchRequest(
                now.minusSeconds(3600),
                now.plusSeconds(3600),
                null,
                null,
                "test.com",
                null,
                null,
                null,
                null,
                null
        );

        mockMvc.perform(post("/api/emails/count")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.count").value(42));

        verify(emailSearchService).getHitCount(any(SearchQuery.class));
    }

    @Test
    void searchEmailsValidatesRequest() throws Exception {
        SearchRequest invalidRequest = new SearchRequest(
                null, // missing required field
                Instant.now(),
                null,
                null,
                "test.com",
                null,
                null,
                null,
                null,
                null
        );

        mockMvc.perform(post("/api/emails/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void searchEmailsHandlesServiceException() throws Exception {
        Instant now = Instant.parse("2025-01-01T10:00:00Z");
        when(emailSearchService.search(any(SearchQuery.class)))
                .thenThrow(new RuntimeException("Search failed"));

        SearchRequest request = new SearchRequest(
                now.minusSeconds(3600),
                now.plusSeconds(3600),
                null,
                null,
                "test.com",
                null,
                null,
                null,
                null,
                null
        );

        mockMvc.perform(post("/api/emails/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"));
    }

    @Test
    void searchEmailsValidatesTimeRange() throws Exception {
        Instant now = Instant.parse("2025-01-01T10:00:00Z");

        // This will trigger IllegalArgumentException from SearchQuery constructor
        when(emailSearchService.search(any(SearchQuery.class)))
                .thenThrow(new IllegalArgumentException("end must be >= start"));

        SearchRequest request = new SearchRequest(
                now.plusSeconds(3600), // start after end
                now,
                null,
                null,
                "test.com",
                null,
                null,
                null,
                null,
                null
        );

        mockMvc.perform(post("/api/emails/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"))
                .andExpect(jsonPath("$.message").value("end must be >= start"));
    }

    @Test
    void getHitCountValidatesRequest() throws Exception {
        SearchRequest invalidRequest = new SearchRequest(
                Instant.now(),
                null, // missing required field
                null,
                null,
                "test.com",
                null,
                null,
                null,
                null,
                null
        );

        mockMvc.perform(post("/api/emails/count")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void getHitCountHandlesServiceException() throws Exception {
        Instant now = Instant.parse("2025-01-01T10:00:00Z");
        when(emailSearchService.getHitCount(any(SearchQuery.class)))
                .thenThrow(new RuntimeException("Count failed"));

        SearchRequest request = new SearchRequest(
                now.minusSeconds(3600),
                now.plusSeconds(3600),
                null,
                null,
                "test.com",
                null,
                null,
                null,
                null,
                null
        );

        mockMvc.perform(post("/api/emails/count")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
    }

    @Test
    void searchEmailsWithPaginationParameters() throws Exception {
        Instant now = Instant.parse("2025-01-01T10:00:00Z");
        EmailDocument email = new EmailDocument(
                "1", "Test Subject", "Test Body", "from@test.com",
                List.of("to@test.com"), List.of(), List.of(), now
        );

        when(emailSearchService.search(any(SearchQuery.class))).thenReturn(List.of(email));
        when(emailSearchService.getHitCount(any(SearchQuery.class))).thenReturn(50L);

        SearchRequest request = new SearchRequest(
                now.minusSeconds(3600),
                now.plusSeconds(3600),
                null,
                null,
                "test.com",
                2, // page 2
                10, // size 10
                null,
                null,
                null
        );

        mockMvc.perform(post("/api/emails/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.size").value(10))
                .andExpect(jsonPath("$.totalPages").value(5))
                .andExpect(jsonPath("$.totalCount").value(50));
    }
}
package dev.aparikh.searchemail.search;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Search criteria. All searches must include a time range.
 * Optional query provides full-text search terms.
 * Optional participantEmails narrows to emails involving any of those participants.
 * adminFirmDomain is used to enforce BCC privacy.
 * Optional facetFields enables field-based faceting on specified fields.
 * Optional facetQueries enables query-based faceting with custom labels and queries.
 */
public record SearchQuery(
        Instant start,
        Instant end,
        String query,
        List<String> participantEmails,
        String adminFirmDomain,
        int page,
        int size,
        List<String> facetFields,
        List<FacetQueryDefinition> facetQueries,
        String sort
) {
    public SearchQuery {
        if (start == null || end == null) {
            throw new IllegalArgumentException("start and end must be provided");
        }
        if (end.isBefore(start)) {
            throw new IllegalArgumentException("end must be >= start");
        }
        if (page < 0) {
            throw new IllegalArgumentException("page must be >= 0");
        }
        if (size <= 0) {
            throw new IllegalArgumentException("size must be > 0");
        }
    }

    public Optional<String> queryOpt() {
        return Optional.ofNullable(query).filter(s -> !s.isBlank());
    }

    // Backward compatibility constructors
    public SearchQuery(Instant start, Instant end, String query, List<String> participantEmails,
                      String adminFirmDomain, int page, int size, List<String> facetFields, 
                      List<FacetQueryDefinition> facetQueries) {
        this(start, end, query, participantEmails, adminFirmDomain, page, size, facetFields, facetQueries, null);
    }
    
    // Legacy constructor with 8 parameters (no facetFields, no facetQueries, no sort)
    public SearchQuery(Instant start, Instant end, String query, List<String> participantEmails,
                      String adminFirmDomain, int page, int size, List<String> facetFields) {
        this(start, end, query, participantEmails, adminFirmDomain, page, size, facetFields, null, null);
    }

    public List<String> participantEmailsNonEmpty() {
        if (participantEmails == null) return List.of();
        return participantEmails.stream()
                .filter(email -> email != null && !email.isBlank())
                .toList();
    }
    
    public Optional<String> sortOpt() {
        return Optional.ofNullable(sort).filter(s -> !s.isBlank());
    }
    
    public static class Builder {
        private Instant start;
        private Instant end;
        private String query;
        private List<String> participantEmails;
        private String adminFirmDomain;
        private int page;
        private int size;
        private List<String> facetFields;
        private List<FacetQueryDefinition> facetQueries;
        private String sort;
        
        public Builder startTime(Instant start) {
            this.start = start;
            return this;
        }
        
        public Builder endTime(Instant end) {
            this.end = end;
            return this;
        }
        
        public Builder query(String query) {
            this.query = query;
            return this;
        }
        
        public Builder participantEmails(List<String> participantEmails) {
            this.participantEmails = participantEmails;
            return this;
        }
        
        public Builder adminFirmDomain(String adminFirmDomain) {
            this.adminFirmDomain = adminFirmDomain;
            return this;
        }
        
        public Builder page(int page) {
            this.page = page;
            return this;
        }
        
        public Builder size(int size) {
            this.size = size;
            return this;
        }
        
        public Builder facetFields(List<String> facetFields) {
            this.facetFields = facetFields;
            return this;
        }
        
        public Builder facetQueries(List<FacetQueryDefinition> facetQueries) {
            this.facetQueries = facetQueries;
            return this;
        }
        
        public Builder sort(String sort) {
            this.sort = sort;
            return this;
        }
        
        public SearchQuery build() {
            return new SearchQuery(start, end, query, participantEmails, adminFirmDomain, 
                                 page, size, facetFields, facetQueries, sort);
        }
    }
}

package dev.aparikh.searchemail.search;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SearchQueryTest {

    private final Instant start = Instant.parse("2025-01-01T10:00:00Z");
    private final Instant end = Instant.parse("2025-01-01T11:00:00Z");

    @Test
    void constructorValidatesTimeRange() {
        // Valid time range
        var query = new SearchQuery(start, end, null, null, "domain.com");
        assertThat(query.start()).isEqualTo(start);
        assertThat(query.end()).isEqualTo(end);
    }

    @Test
    void constructorRejectsNullStart() {
        assertThatThrownBy(() -> new SearchQuery(null, end, null, null, "domain.com"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("start and end must be provided");
    }

    @Test
    void constructorRejectsNullEnd() {
        assertThatThrownBy(() -> new SearchQuery(start, null, null, null, "domain.com"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("start and end must be provided");
    }

    @Test
    void constructorRejectsEndBeforeStart() {
        Instant laterStart = end.plusSeconds(1); // Start after end
        assertThatThrownBy(() -> new SearchQuery(laterStart, end, null, null, "domain.com"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("end must be >= start");
    }

    @Test
    void constructorAllowsEqualStartAndEnd() {
        var query = new SearchQuery(start, start, null, null, "domain.com");
        assertThat(query.start()).isEqualTo(start);
        assertThat(query.end()).isEqualTo(start);
    }

    @Test
    void queryOptReturnsEmptyForNull() {
        var query = new SearchQuery(start, end, null, null, "domain.com");
        assertThat(query.queryOpt()).isEmpty();
    }

    @Test
    void queryOptReturnsEmptyForBlankString() {
        var query = new SearchQuery(start, end, "   ", null, "domain.com");
        assertThat(query.queryOpt()).isEmpty();
    }

    @Test
    void queryOptReturnsEmptyForEmptyString() {
        var query = new SearchQuery(start, end, "", null, "domain.com");
        assertThat(query.queryOpt()).isEmpty();
    }

    @Test
    void queryOptReturnsValueForNonBlankString() {
        var query = new SearchQuery(start, end, "subject:meeting", null, "domain.com");
        assertThat(query.queryOpt()).hasValue("subject:meeting");
    }

    @Test
    void participantEmailOptReturnsEmptyForNull() {
        var query = new SearchQuery(start, end, null, null, "domain.com");
        assertThat(query.participantEmailOpt()).isEmpty();
    }

    @Test
    void participantEmailOptReturnsEmptyForBlankString() {
        var query = new SearchQuery(start, end, null, "   ", "domain.com");
        assertThat(query.participantEmailOpt()).isEmpty();
    }

    @Test
    void participantEmailOptReturnsEmptyForEmptyString() {
        var query = new SearchQuery(start, end, null, "", "domain.com");
        assertThat(query.participantEmailOpt()).isEmpty();
    }

    @Test
    void participantEmailOptReturnsValueForNonBlankString() {
        var query = new SearchQuery(start, end, null, "alice@example.com", "domain.com");
        assertThat(query.participantEmailOpt()).hasValue("alice@example.com");
    }

    @Test
    void recordFieldsAreAccessible() {
        var query = new SearchQuery(start, end, "test query", "user@domain.com", "domain.com");
        
        assertThat(query.start()).isEqualTo(start);
        assertThat(query.end()).isEqualTo(end);
        assertThat(query.query()).isEqualTo("test query");
        assertThat(query.participantEmail()).isEqualTo("user@domain.com");
        assertThat(query.adminFirmDomain()).isEqualTo("domain.com");
    }
}
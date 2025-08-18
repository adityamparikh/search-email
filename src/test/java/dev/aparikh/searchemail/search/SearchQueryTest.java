package dev.aparikh.searchemail.search;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SearchQueryTest {

    private final Instant start = Instant.parse("2025-01-01T10:00:00Z");
    private final Instant end = Instant.parse("2025-01-01T11:00:00Z");

    @Test
    void constructorValidatesTimeRange() {
        // Valid time range
        var query = new SearchQuery(start, end, null, null, "domain.com", 0, 100);
        assertThat(query.start()).isEqualTo(start);
        assertThat(query.end()).isEqualTo(end);
    }

    @Test
    void constructorRejectsNullStart() {
        assertThatThrownBy(() -> new SearchQuery(null, end, null, null, "domain.com", 0, 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("start and end must be provided");
    }

    @Test
    void constructorRejectsNullEnd() {
        assertThatThrownBy(() -> new SearchQuery(start, null, null, null, "domain.com", 0, 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("start and end must be provided");
    }

    @Test
    void constructorRejectsEndBeforeStart() {
        Instant laterStart = end.plusSeconds(1); // Start after end
        assertThatThrownBy(() -> new SearchQuery(laterStart, end, null, null, "domain.com", 0, 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("end must be >= start");
    }

    @Test
    void constructorAllowsEqualStartAndEnd() {
        var query = new SearchQuery(start, start, null, null, "domain.com", 0, 100);
        assertThat(query.start()).isEqualTo(start);
        assertThat(query.end()).isEqualTo(start);
    }

    @Test
    void queryOptReturnsEmptyForNull() {
        var query = new SearchQuery(start, end, null, null, "domain.com", 0, 100);
        assertThat(query.queryOpt()).isEmpty();
    }

    @Test
    void queryOptReturnsEmptyForBlankString() {
        var query = new SearchQuery(start, end, "   ", null, "domain.com", 0, 100);
        assertThat(query.queryOpt()).isEmpty();
    }

    @Test
    void queryOptReturnsEmptyForEmptyString() {
        var query = new SearchQuery(start, end, "", null, "domain.com", 0, 100);
        assertThat(query.queryOpt()).isEmpty();
    }

    @Test
    void queryOptReturnsValueForNonBlankString() {
        var query = new SearchQuery(start, end, "subject:meeting", null, "domain.com", 0, 100);
        assertThat(query.queryOpt()).hasValue("subject:meeting");
    }

    @Test
    void participantEmailsNonEmptyReturnsEmptyForNull() {
        var query = new SearchQuery(start, end, null, null, "domain.com", 0, 100);
        assertThat(query.participantEmailsNonEmpty()).isEmpty();
    }

    @Test
    void participantEmailsNonEmptyFiltersBlankStrings() {
        var participantList = new java.util.ArrayList<String>();
        participantList.add("alice@example.com");
        participantList.add("   ");
        participantList.add("");
        participantList.add(null);
        participantList.add("bob@example.com");

        var query = new SearchQuery(start, end, null, participantList, "domain.com", 0, 100);
        assertThat(query.participantEmailsNonEmpty()).containsExactly("alice@example.com", "bob@example.com");
    }

    @Test
    void participantEmailsNonEmptyReturnsEmptyForEmptyList() {
        var query = new SearchQuery(start, end, null, List.of(), "domain.com", 0, 100);
        assertThat(query.participantEmailsNonEmpty()).isEmpty();
    }

    @Test
    void participantEmailsNonEmptyReturnsValidEmails() {
        var query = new SearchQuery(start, end, null, List.of("alice@example.com", "bob@example.com"), "domain.com", 0, 100);
        assertThat(query.participantEmailsNonEmpty()).containsExactly("alice@example.com", "bob@example.com");
    }

    @Test
    void recordFieldsAreAccessible() {
        var query = new SearchQuery(start, end, "test query", List.of("user@domain.com"), "domain.com", 0, 100);
        
        assertThat(query.start()).isEqualTo(start);
        assertThat(query.end()).isEqualTo(end);
        assertThat(query.query()).isEqualTo("test query");
        assertThat(query.participantEmails()).containsExactly("user@domain.com");
        assertThat(query.adminFirmDomain()).isEqualTo("domain.com");
        assertThat(query.page()).isEqualTo(0);
        assertThat(query.size()).isEqualTo(100);
    }

    @Test
    void constructorRejectsNegativePage() {
        assertThatThrownBy(() -> new SearchQuery(start, end, null, null, "domain.com", -1, 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("page must be >= 0");
    }

    @Test
    void constructorRejectsZeroSize() {
        assertThatThrownBy(() -> new SearchQuery(start, end, null, null, "domain.com", 0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("size must be > 0");
    }

    @Test
    void constructorRejectsNegativeSize() {
        assertThatThrownBy(() -> new SearchQuery(start, end, null, null, "domain.com", 0, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("size must be > 0");
    }
}
package dev.aparikh.searchemail.search;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Search criteria. All searches must include a time range.
 * Optional query provides full-text search terms.
 * Optional participantEmails narrows to emails involving any of those participants.
 * adminFirmDomain is used to enforce BCC privacy.
 * Optional facetFields enables faceting on specified fields.
 */
public record SearchQuery(
        Instant start,
        Instant end,
        String query,
        List<String> participantEmails,
        String adminFirmDomain,
        int page,
        int size,
        List<String> facetFields
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

    public List<String> participantEmailsNonEmpty() {
        if (participantEmails == null) return List.of();
        return participantEmails.stream()
                .filter(email -> email != null && !email.isBlank())
                .toList();
    }
}

package dev.aparikh.searchemail.search;

import java.time.Instant;
import java.util.Optional;

/**
 * Search criteria. All searches must include a time range.
 * Optional query provides full-text search terms.
 * Optional participantEmail narrows to emails involving that participant.
 * adminFirmDomain is used to enforce BCC privacy.
 */
record SearchQuery(
        Instant start,
        Instant end,
        String query,
        String participantEmail,
        String adminFirmDomain
) {
    SearchQuery {
        if (start == null || end == null) {
            throw new IllegalArgumentException("start and end must be provided");
        }
        if (end.isBefore(start)) {
            throw new IllegalArgumentException("end must be >= start");
        }
    }

    Optional<String> queryOpt() {
        return Optional.ofNullable(query).filter(s -> !s.isBlank());
    }

    Optional<String> participantEmailOpt() {
        return Optional.ofNullable(participantEmail).filter(s -> !s.isBlank());
    }
}

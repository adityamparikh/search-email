package dev.aparikh.searchemail.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EmailDocumentTest {

    @Test
    void recordFieldsAreAccessible() {
        Instant sentAt = Instant.parse("2025-01-01T10:00:00Z");
        EmailDocument email = new EmailDocument(
                "test-id",
                "Test Subject",
                "Test Body",
                "from@test.com",
                List.of("to1@test.com", "to2@test.com"),
                List.of("cc@test.com"),
                List.of("bcc@test.com"),
                sentAt
        );

        assertThat(email.id()).isEqualTo("test-id");
        assertThat(email.subject()).isEqualTo("Test Subject");
        assertThat(email.body()).isEqualTo("Test Body");
        assertThat(email.from()).isEqualTo("from@test.com");
        assertThat(email.to()).containsExactly("to1@test.com", "to2@test.com");
        assertThat(email.cc()).containsExactly("cc@test.com");
        assertThat(email.bcc()).containsExactly("bcc@test.com");
        assertThat(email.sentAt()).isEqualTo(sentAt);
    }

    @Test
    void recordHandlesNullFields() {
        EmailDocument email = new EmailDocument(
                "test-id",
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );

        assertThat(email.id()).isEqualTo("test-id");
        assertThat(email.subject()).isNull();
        assertThat(email.body()).isNull();
        assertThat(email.from()).isNull();
        assertThat(email.to()).isNull();
        assertThat(email.cc()).isNull();
        assertThat(email.bcc()).isNull();
        assertThat(email.sentAt()).isNull();
    }

    @Test
    void recordHandlesEmptyLists() {
        EmailDocument email = new EmailDocument(
                "test-id",
                "Subject",
                "Body",
                "from@test.com",
                List.of(),
                List.of(),
                List.of(),
                Instant.parse("2025-01-01T10:00:00Z")
        );

        assertThat(email.to()).isEmpty();
        assertThat(email.cc()).isEmpty();
        assertThat(email.bcc()).isEmpty();
    }

    @Test
    void recordSupportsEquality() {
        Instant sentAt = Instant.parse("2025-01-01T10:00:00Z");
        EmailDocument email1 = new EmailDocument(
                "test-id", "Subject", "Body", "from@test.com",
                List.of("to@test.com"), List.of(), List.of(), sentAt
        );
        EmailDocument email2 = new EmailDocument(
                "test-id", "Subject", "Body", "from@test.com",
                List.of("to@test.com"), List.of(), List.of(), sentAt
        );

        assertThat(email1).isEqualTo(email2);
        assertThat(email1.hashCode()).isEqualTo(email2.hashCode());
    }

    @Test
    void recordSupportsToString() {
        EmailDocument email = new EmailDocument(
                "test-id", "Subject", "Body", "from@test.com",
                List.of("to@test.com"), List.of(), List.of(),
                Instant.parse("2025-01-01T10:00:00Z")
        );

        String toString = email.toString();
        assertThat(toString).contains("test-id");
        assertThat(toString).contains("Subject");
        assertThat(toString).contains("from@test.com");
    }
}
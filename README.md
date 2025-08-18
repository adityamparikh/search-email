# Email Search Engine (SolrJ + Spring Boot)

This module implements an email search engine backed by Apache Solr (via SolrJ). It indexes all typical email fields and enforces privacy protections for BCC participants at query time, based on the firm (email domain) of the searching admin.

## Design

- Storage/Search: Apache Solr core `emails` accessed through SolrJ.
- Data model:
  - Fields: `id`, `subject`, `body`, `from_addr`, `to_addr[]`, `cc_addr[]`, `bcc_addr[]`, `sent_at`.
  - All participant fields store full email addresses (normalized to lower-case).
  - `sent_at` is an Instant (UTC), stored in Solr as a date field.
- Privacy policy:
  - Admins can search by an email participant. Matches in TO/CC/FROM are always visible.
  - Matches in BCC are only visible if the searched participant’s email domain equals the admin’s firm domain (assume the domain is the firm).
  - Searches must include a start and end time; results are filtered to this range.
- Minimal duplication:
  - Each participant field is indexed separately; we do not store a duplicated "participants" blob. We combine across fields at query time with an OR expression.

## Components

- `SolrProperties` (typed config): `solr.base-url`, `solr.core`, `solr.commit-within-ms`.
- `SolrConfig`: builds a `SolrClient` (HttpSolrClient).
- `EmailDocument` (record): holds the email fields to index/fetch.
- `SearchQuery` (record): holds time range, participant email, and admin firm domain; validates non-null time range and non-decreasing order.
- `EmailSearchService`:
  - `index()` / `indexAll()`: writes documents to Solr, normalizing email addresses to lower-case.
  - `search(SearchQuery)`: builds a Solr query:
    - Applies `sent_at:[start TO end]` filter.
    - If a participantEmail is provided, adds an OR filter across `from_addr`, `to_addr`, `cc_addr` and (conditionally) `bcc_addr` only when the participant’s domain equals the admin’s firm domain.

## Schema

Tests create a core from the `_default` configset and add fields via Solr’s Schema API:

- `id`: string, stored/indexed, required
- `subject`: text_general
- `body`: text_general
- `from_addr`: string
- `to_addr`: string, multiValued
- `cc_addr`: string, multiValued
- `bcc_addr`: string, multiValued
- `sent_at`: pdate

Note: Strings are used for email addresses to avoid analyzer-side changes; values are normalized to lower-case at index and query time.

## Privacy Details

- BCC visibility is enforced at query time only when searching by a specific participant.
- Admin’s firm is derived from their domain string (e.g., `acme.com`). The participant’s firm is derived from the participant’s email domain.
- This logic ensures admins see BCC results only for their own firm’s employees.

## Code Organization

- `dev.aparikh.searchemail.config`: configuration classes (`SolrProperties`, `SolrConfig`).
- `dev.aparikh.searchemail.search`: search domain and service (`EmailDocument`, `SearchQuery`, `EmailSearchService`).
- Tests: `src/test/java/dev/aparikh/searchemail/search/EmailSearchServiceIT` spin up a Solr container and verify behavior.

## Spring Boot Guidelines Applied

- Constructor injection, final fields wherever applicable.
- Package-private visibility for components; only the application class is public.
- Typed configuration with `@ConfigurationProperties`.
- Transactions are not applicable (no RDBMS). OSIV disabled by default for safety.
- Centralized logging via SLF4J; avoid System.out.
- Integration tests use Testcontainers with a random web port.

## Running Tests

Prerequisites: Docker must be available for Testcontainers.

- Linux/macOS/Windows:

```bash
./gradlew test
```

The integration test spins up `solr:9.6.1`, creates the `emails` core and fields, indexes sample data, and asserts:
- BCC matches are visible only when admin’s firm domain equals the participant’s domain.
- TO/CC matches are always visible.
- Date range filtering works and is mandatory.

## Configuration

Add to your application properties (or environment variables):

- `solr.base-url=http://localhost:8983/solr`
- `solr.core=emails`
- `solr.commit-within-ms=0`

By default in this project, Solr is disabled unless explicitly enabled (tests enable it dynamically).

## Extensibility

- Add additional query criteria (subject/body text, pagination, sort) by expanding `SearchQuery` and `EmailSearchService.search`.
- Add firm mapping strategies beyond domain matching by plugging in a resolver injected into `EmailSearchService`.

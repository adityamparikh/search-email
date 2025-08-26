## Project Overview

This is a Spring Boot 3.5.4 application implementing an email search engine backed by Apache Solr. The system provides
privacy-aware search capabilities where BCC participants are only visible to admins from the same firm domain.

## Build and Development Commands

### Core Commands

```bash
# Build the project
./gradlew build

# Run tests (requires Docker for Testcontainers)
./gradlew test

# Run a single test class
./gradlew test --tests "EmailSearchServiceIT"

# Run the application
./gradlew bootRun

# Clean build
./gradlew clean build
```

### Development

- Uses Java 21 with Spring Boot 3.5.4
- Gradle wrapper (./gradlew) is the preferred build tool
- Tests use Testcontainers with Solr 9.6.1 (requires Docker)
- Development includes Spring Boot DevTools for hot reload

## Architecture

### Core Components

**Configuration Layer (`dev.aparikh.searchemail.config`)**:

- `SolrProperties`: Typed configuration for Solr connection (`solr.*` properties)
- `SolrConfig`: Creates HttpSolrClient bean with URL normalization

**Search Domain (`dev.aparikh.searchemail.search`)**:

- `EmailDocument`: Record representing email data with all fields (id, subject, body, from, to[], cc[], bcc[], sentAt)
- `SearchQuery`: Record for search parameters with configurable query text, time range validation, and privacy context
- `EmailIndexService`: Service responsible for indexing emails into Solr with proper field mapping and normalization
- `EmailSearchService`: Service responsible for searching emails with privacy enforcement and configurable query
  building

### Privacy Architecture

The system implements sophisticated firm-based privacy controls to prevent cross-firm information leakage:

#### Same-Firm Searches
When an admin searches for employees from their own firm:
- **Rule**: Admin can see same-firm participant in any field (FROM/TO/CC/BCC)
- **Example**: JP Morgan admin searching for "alice@jpmorgan.com" will find all emails where Alice appears in any field

#### Cross-Firm Searches  
When an admin searches for employees from different firms, strict privacy rules apply:

**✅ VISIBLE Scenarios:**
1. **Standard Cross-Firm Visibility**
   - Cross-firm participant in FROM/TO/CC + Admin firm has any participation
   - Example: JP Morgan admin searching for "bob@bankofamerica.com" finds emails where:
     - Bob is in TO field AND JP Morgan employee is in CC field
     - Bob is in FROM field AND JP Morgan employee is in BCC field

2. **Sender Privilege for BCC**
   - Cross-firm participant in BCC + Admin firm member is the sender
   - Example: JP Morgan admin finds Bob (BoA) in BCC when JP Morgan employee sent the email
   - Rationale: Senders can see all BCC recipients in emails they sent

**❌ HIDDEN Scenarios:**
1. **BCC Without Sender Privilege**
   - Cross-firm participant in BCC + Admin firm member is not the sender
   - Example: Bob (BoA) in BCC + JP Morgan employee only in TO field → HIDDEN
   - Prevents BCC information leakage across firms

2. **No Admin Firm Participation**
   - Cross-firm participant anywhere + No admin firm participation
   - Example: Bob (BoA) in any field with no JP Morgan employees → HIDDEN
   - Prevents discovery of emails where admin firm has no business involvement

#### Privacy Enforcement
- All privacy rules enforced at query time via Solr filter queries
- Hit counts, faceting, and search results all respect privacy constraints  
- Mandatory time range filtering for all searches
- Email addresses normalized to lowercase for consistent matching

### Data Flow

1. **Indexing**: EmailIndexService: EmailDocument → SolrInputDocument → Solr core
2. **Searching**: EmailSearchService: SearchQuery → SolrQuery with privacy filters → List<EmailDocument>
3. **Privacy Enforcement**: Applied at query time via conditional BCC field inclusion

### Separation of Concerns

- **EmailIndexService**: Handles document transformation, field normalization (email addresses to lowercase), and Solr
  indexing operations
- **EmailSearchService**: Handles configurable query building, privacy rule enforcement, result parsing, and field value
  extraction from Solr documents

### Query Capabilities

- **Configurable Queries**: SearchQuery accepts an optional query parameter for full-text search (defaults to "*:*" for
  match-all)
- **Field-Specific Search**: Supports Solr query syntax like `subject:Meeting` or `body:discuss`
- **Combined Filtering**: Query text works alongside time range and participant filters

### Solr Schema

Uses dynamic field creation via Schema API in tests:

- `id`: string (required, unique)
- `subject`, `body`: text_general (analyzed)
- `from_addr`: string (exact match)
- `to_addr`, `cc_addr`, `bcc_addr`: string arrays (exact match, multiValued)
- `sent_at`: pdate (date/time with range queries)

## Testing Strategy

### Integration Tests

- `EmailSearchServiceIT`: Full integration test using Testcontainers
- Creates real Solr instance with proper schema
- Tests privacy enforcement, date filtering, and search accuracy
- Includes setup/teardown for clean test isolation

### Test Configuration

- Uses `@DynamicPropertySource` to configure Solr connection from Testcontainers
- `TestSearchEmailApplication` for development with Testcontainers
- Tests require Docker and will create/destroy Solr containers

## Configuration

### Application Properties

```properties
# Solr connection (disabled by default, enabled in tests)
solr.base-url=http://localhost:8983/solr
solr.core=emails
solr.commit-within-ms=0
# Production best practices
spring.jpa.open-in-view=false
management.endpoints.web.exposure.include=health,info,metrics
```

### Conditional Bean Creation

- SolrClient and EmailSearchService are only created when Solr configuration is available
- Uses `@ConditionalOnBean(SolrClient.class)` for service activation

## Development Patterns

### Code Style

- Package-private visibility by default (only main application class is public)
- Constructor injection with final fields
- Records for immutable data structures
- Validation annotations on configuration properties
- SLF4J logging (avoid System.out)

### Error Handling

- Runtime exceptions with descriptive messages for Solr operations
- Graceful handling of missing/malformed data
- Validation at boundaries (SearchQuery validates time range order)

## Key Dependencies

- Spring Boot 3.5.4 (Web, Actuator, Cache, Validation, DevTools)
- Apache Solr SolrJ 9.6.1
- Lombok for boilerplate reduction
- Testcontainers for integration testing
- JUnit 5 with AssertJ assertions
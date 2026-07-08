# SKKU Alumni Backend Agent Notes

## Project Shape

- This backend serves the Sungkyunkwan University alumni officer address book and community service.
- The primary app audience is not every alumnus. The first business target is current alumni association officers who paid dues for the active officer term.
- Preserve officer history by generation and phase. Do not overwrite past officer roles when a new term starts.

## Domain Rules

- Store enum-like database values as `varchar`; map them in Java with `@Enumerated(EnumType.STRING)`.
- A member has at most one representative company and one representative industry.
- A member can have many hobbies. New hobby creation and duplicate cleanup should be handled explicitly when that feature is added.
- Do not migrate ASIS free-form memo fields into this service unless the requirement changes.
- Department names need lineage handling:
  - Renamed departments point to the current display department.
  - Closed departments keep their historical name.
  - Night-school prefixes such as `(야)` should be normalized into the daytime department name during import.

## API Rules

- Use cursor pagination for growing lists. Avoid APIs that require total counts for ordinary list screens.
- Filters are query parameters and combine as AND conditions unless a specific endpoint says otherwise.
- User-facing feed screens can use infinite scroll. Admin tabular screens can still use pagination controls, backed by cursor APIs.
- Current auth is intentionally scaffolded with sample user/admin IDs in operational controllers. Replace those constants with Spring Security principals when JWT/refresh-token work begins.

## Code Style

- Keep files focused on one entity, controller, service, record, enum, or function.
- Prefer descriptive names over short abbreviations.
- Keep user-visible sample data fictional. Do not copy personal data from attached spreadsheets into seed data.
- For schema changes, add Flyway migrations under `src/main/resources/db/migration`.
- Keep Hibernate `ddl-auto=validate`; schema ownership belongs to Flyway.

## Verification

- Run backend tests with Java 17+ as the Gradle runtime:

```shell
env JAVA_HOME=/Users/hyesung/Library/Java/JavaVirtualMachines/ms-17.0.15/Contents/Home ./gradlew test
```

- The Gradle toolchain is configured for Java 25, so Gradle may resolve a Java 25 toolchain during builds.

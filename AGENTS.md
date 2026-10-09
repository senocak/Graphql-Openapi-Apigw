# Repository Guidelines

## Project Structure

This is a Java 21 / Spring Boot Maven application. Production code is under `src/main/java/com/github/senocak/gateway`, organized by responsibility: `config`, `openapi`, `graphql`, `resolver`, `mapping`, `client`, `model`, and `security`. Runtime configuration and API specs live in `src/main/resources`; the default schema is assembled from the Swagger UI's seven API groups. Tests are in the matching `src/test/java` package tree. `gateway-demo.http` contains manual GraphQL and REST examples; `Dockerfile` and `docker-compose.yml` support container runs.

## Build, Test, and Run

- `./mvnw test` compiles the project and runs the test suite.
- `./mvnw spring-boot:run` starts the gateway on port 8080 and fetches the documents configured under `gateway.openapi.specs` in `application.yml`.
- Set `GATEWAY_OPENAPI_BASEURL` to override the shared backend origin, or edit each spec's `location` and `base-url` in `application.yml`.
- `docker compose up --build` builds and starts the gateway in Docker.

Use Java 21. Running the app requires outbound access to fetch the configured Swagger document. Automated integration tests use embedded WireMock and do not require a live backend or external spec fetch.

## Coding Style and Naming

Keep code in the existing package boundaries and follow nearby Java formatting (tabs are used in current sources). Use PascalCase for classes, camelCase for methods and fields, and descriptive names ending in `Test` for test classes. No formatter or linter is configured in `pom.xml`; keep changes consistent with adjacent code and avoid unrelated reformatting.

## Testing

Tests use JUnit 5, Spring Boot Test, Spring GraphQL Test, AssertJ, and WireMock. Add or update tests for behavior changes, especially OpenAPI-to-GraphQL schema generation, REST request mapping, header forwarding, and sanitized backend errors. Run `./mvnw test` before submitting changes.

## Commits and Pull Requests

Git history is unavailable in this workspace, so no existing commit convention could be verified. Use a concise imperative commit subject (for example, `Handle optional GraphQL input fields`). Pull requests should explain the behavior change, link a related issue when applicable, and report tests run. Update the README or sample OpenAPI document when configuration or externally visible mappings change.

## Configuration and Security

Keep backend addresses configurable through `gateway.openapi.base-url` or `GATEWAY_OPENAPI_BASEURL`. Do not commit credentials or tokens. Bearer authorization and `Cmp-Session-Id` forwarding are configured in `application.yml`; change them deliberately and document any security impact.

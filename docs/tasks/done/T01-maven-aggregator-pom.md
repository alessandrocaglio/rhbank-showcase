# T01 · Root Maven Aggregator POM + Maven Wrapper

**Phase:** 0 — Foundation
**Status:** todo

## Deliverables
- `pom.xml` (aggregator, `packaging=pom`) declaring all 5 Java submodules in correct build order
- Spring Boot BOM 3.3.5 + Quarkus BOM 3.15.1 in `<dependencyManagement>`
- Java 21 compiler settings, UTF-8 encoding
- JaCoCo plugin (line coverage ≥ 80%, fails build on miss)
- Jib plugin (global base: `registry.access.redhat.com/ubi9/openjdk-21-runtime:latest`, registry: `quay.io/acaglio`)
- Maven Wrapper generated via `mvn wrapper:wrapper -Dmaven=3.9.6`
- Minimal placeholder `pom.xml` in each submodule directory so `validate` resolves all modules

## Unit Tests
N/A — aggregator POM has no src/. Verification is structural.

## Verification
```bash
./mvnw validate
# Expected: BUILD SUCCESS, all submodule paths resolved
ls -la mvnw
```

## Acceptance Criteria
- [ ] `./mvnw validate` exits 0
- [ ] All 5 submodule paths resolve without error
- [ ] `mvnw` is executable
- [ ] JaCoCo and Jib plugins visible in effective POM

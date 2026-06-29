# R33 — GitHub Actions CI Pipeline (Image Build & Push)

## Goal
Automate the build and push of all five application images to `quay.io/acaglio/` on every commit
to `main`. This replaces the manual `podman build && podman push` workflow from R30.

## Triggers
- Push to `main` branch (full build + push of all images)
- Pull request to `main` (build only, no push — verify the build doesn't break)

## Workflow structure

```
.github/
└── workflows/
    └── build-and-push.yml
```

### Jobs

#### 1. `build-java` — Maven build + Podman push (Java services)
- Runner: `ubuntu-latest`
- Steps:
  1. Checkout
  2. Set up Java 21 (Temurin via `actions/setup-java`)
  3. Cache Maven repository (`~/.m2`)
  4. `./mvnw clean package -DskipTests`
  5. `podman login quay.io` using GitHub secrets
  6. Build and push each Java image:
     - `apps/payment-gateway` → `quay.io/acaglio/payment-gateway:<tag>`
     - `apps/account-verifier` → `quay.io/acaglio/account-verifier:<tag>`
     - `apps/transaction-engine` → `quay.io/acaglio/transaction-engine:<tag>`
     - `apps/clearing-house` → `quay.io/acaglio/clearing-house:<tag>`

#### 2. `build-spa` — Node build + Podman push (SPA)
- Runner: `ubuntu-latest`
- Steps:
  1. Checkout
  2. Set up Node 20
  3. `cd apps/spa-mobile-app && npm ci`
  4. `npm run build` (Vite build — validates the SPA compiles)
  5. `podman login quay.io`
  6. `podman build -t quay.io/acaglio/spa-mobile-app:<tag> apps/spa-mobile-app/`
  7. `podman push quay.io/acaglio/spa-mobile-app:<tag>`

#### 3. `test` — Run all module tests (parallel to build jobs)
- Runner: `ubuntu-latest`
- Steps:
  1. Checkout
  2. Set up Java 21
  3. `./mvnw test -pl grpc-api,apps/account-verifier,apps/payment-gateway,apps/transaction-engine,apps/clearing-house`
  4. Set up Node 20
  5. `cd apps/spa-mobile-app && npm ci && npm test -- --run`

### Image tagging strategy
- On push to `main`: tag with `latest` AND the short Git SHA (`${{ github.sha }}` truncated to 7 chars)
- On pull request: build only (no push), tag with `pr-<number>` for reference

### Secrets required (set in GitHub repo settings)
```
QUAY_USERNAME   — quay.io robot account username
QUAY_PASSWORD   — quay.io robot account password/token
```

## Open questions
1. **GitHub repo** — Is the code already pushed to GitHub? If not, create the repo first.
2. **quay.io robot account** — Use a dedicated robot account (not personal credentials) for CI pushes. Has one been created?
3. **Runner availability** — Self-hosted runner or GitHub-hosted (`ubuntu-latest`)? GitHub-hosted is simpler but has rate limits for quay.io pushes.
4. **OTel agent JAR version** — Should the CI pipeline download a pinned OTel agent version at build time, or should the Dockerfiles already contain the correct download step?
5. **Test infrastructure** — Some tests (e.g. `@QuarkusTest`) may require Kafka/PostgreSQL. Should we use Testcontainers in CI, or skip those tests in the CI run?

## Acceptance
- A push to `main` triggers the workflow and all three jobs pass.
- `quay.io/acaglio/payment-gateway:latest` and `quay.io/acaglio/payment-gateway:<sha>` both appear in the registry.
- A PR shows a build-only run with no push.
- Test job reports pass for all 107 tests.

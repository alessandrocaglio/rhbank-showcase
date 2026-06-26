# T02 · `grpc-api` Shared Module — Proto Definition + Stub Generation

**Phase:** 0 — Foundation
**Status:** todo
**Depends on:** T01

## Deliverables
- `apps/grpc-api/pom.xml` with `protobuf-maven-plugin` + `grpc-java` codegen plugin
- `src/main/proto/account.proto` — defines `AccountService/VerifyAccount` RPC
- Dependencies: `io.grpc:grpc-protobuf`, `io.grpc:grpc-stub`, `javax.annotation:javax.annotation-api`
- Generated Java stubs land in `target/generated-sources/`

## Proto Contract
```protobuf
service AccountService {
  rpc VerifyAccount (VerifyAccountRequest) returns (VerifyAccountResponse);
}
message VerifyAccountRequest {
  string transaction_id = 1; string source_account = 2;
  string destination_account = 3; double amount = 4; string currency = 5;
}
message VerifyAccountResponse { bool approved = 1; string reason = 2; }
```

## Unit Tests
`AccountProtoTest` — instantiates `VerifyAccountRequest` and `VerifyAccountResponse` via the
generated builder API and asserts field values round-trip correctly. Confirms stubs compiled.

## Verification
```bash
./mvnw package -pl apps/grpc-api
ls apps/grpc-api/target/generated-sources/protobuf/   # must contain Java files
./mvnw test -pl apps/grpc-api                         # AccountProtoTest passes
```

## Acceptance Criteria
- [ ] `./mvnw package -pl apps/grpc-api` exits 0
- [ ] Generated Java sources present under `target/generated-sources/`
- [ ] `AccountProtoTest` passes

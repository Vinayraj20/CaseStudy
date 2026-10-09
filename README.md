# Idempotent Background Transaction Processing Service

A lightweight Java 17 + Maven implementation for the practical transaction-processing assessment. TestNG is used for automated tests (JUnit is not used).

## Features

- Validates required transaction fields and positive amounts.
- Uses transaction IDs and request IDs to identify duplicate/replayed requests.
- Buffers out-of-order transactions by account and sequence number.
- Applies credits, debits, and reversals to an in-memory balance store.
- Simulates a transient failure and retries once (maximum two attempts).
- Records transaction statuses and processing events.
- Provides a status summary and sample console runner.
- Uses TestNG for automated tests.

## Design choices and assumptions

1. Sequence numbers start at `1` independently for each account.
2. A future sequence is kept pending until the next expected sequence arrives.
3. A terminal processing failure advances the sequence so one failed record does not block all later records. A real financial system may instead require a business-specific hold/reconciliation process.
4. The in-memory stores are lost when the process exits. They demonstrate the logic only; production would need durable storage and atomic database transactions.
5. The sample `simulateTransientFailure` flag is a test hook, not a production API field.
6. The sample uses one processor instance in a single process. Production needs concurrency control, durable idempotency keys, atomic balance/ledger updates, authentication, metrics, and recovery tooling.

## Prerequisites

- JDK 17+
- Maven 3.8+

Verify installations:

```bash
java -version
mvn -version
```

## Run the application

From the project root:

```bash
mvn clean compile
mvn exec:java -Dexec.mainClass="com.mphasis.assessment.Main"
```

If the Maven Exec plugin is not available in your environment, compile and run directly:

```bash
mvn clean package
java -cp target/classes com.mphasis.assessment.Main
```

## Run TestNG tests

```bash
mvn clean test
```

Surefire runs the suite declared in `testng.xml`. The report is normally generated under `target/surefire-reports/`.

## Test coverage

- Out-of-order sequence buffering and ordered processing
- Duplicate transaction prevention
- Invalid negative amount rejection
- Retry after simulated transient failure
- Insufficient balance handling
- Processing status summary

## Suggested GitHub submission checklist

- [ ] Run `mvn clean test` and verify all tests pass.
- [ ] Run the application and capture sample output.
- [ ] Review every class and be prepared to explain it.
- [ ] Add test output or screenshots if useful.
- [ ] Create a **public** GitHub repository and push the source.
- [ ] Submit the repository URL and a short summary to the recruiter.

## AI-assisted development note

AI tools were used to accelerate initial scaffolding and review of implementation options. The candidate should personally run the tests, inspect the implementation, make any corrections, and be prepared to explain the idempotency key, ordering policy, retry boundary, limitations of in-memory storage, and production hardening plan.

# Architecture

## Flow

```
POST /receipts ──► UploadValidator ──► FileStorage.save(<receipt_id>.<ext>) ──► ReceiptRepository.save
POST /receipts/{id}/process
      └► FileStorage.load ─► OcrEngine.extractText ─► ReceiptParser (text → header, taxes, items)
         └► [lock: receipt] Reconciler (items vs total → itemize_status)
              └► TransactionRepository.save (create, or update the receipt's one transaction; version + 1)
POST /transactions/{id}/itemize ──► [lock] re-parse the stored raw text → replace items only
PATCH /transactions/{id}/items  ──► [lock] If-Match check ─► Reconciler.check → 409, or save + COMPLETE
```

## Packages

| Package | Contents |
|---|---|
| `web` | `ReceiptController`, `TransactionController` (ETag / If-Match), `ErrorHandler`, `RequestLoggingFilter` (request id + access log): HTTP only |
| `error` | `ErrorCode` (code → HTTP status), `ApiException`, `ReconciliationException` (the 409, with expected / actual / difference) |
| `service` | `ReceiptService` (orchestration, locking, versions), `ReceiptParser`, `Reconciler`, `UploadValidator` |
| `ocr` | `OcrEngine` interface, `StubOcrEngine` |
| `storage` | `FileStorage` interface, `LocalDiskFileStorage` |
| `repository` | `ReceiptRepository` / `TransactionRepository` interfaces, `InMemory…` implementations (copy on read and write) |
| `model` | `Receipt`, `Transaction`, `TaxLine`, `LineItem`, `ItemizeStatus`, `ParsedReceipt`, `ReconciliationResult`, `Ids` (prefixed id generator) |

## Design patterns

| Pattern | Where | Why |
|---|---|---|
| **Strategy** | `OcrEngine` → `StubOcrEngine` | A real OCR vendor is a new implementation; the parser and everything after it stay the same |
| **Repository** | `ReceiptRepository`, `TransactionRepository` → `InMemory…` | Moving to a database means adding a new implementation, with no change to `ReceiptService` |
| **Strategy / Adapter** | `FileStorage` → `LocalDiskFileStorage` | Local disk today, S3 later |
| **Facade** | `ReceiptService` | Controllers call one method, and the service runs the whole pipeline |
| **Dependency injection** | constructors everywhere | `ReceiptService` depends only on interfaces; `ReceiptServiceTest` wires it with fakes, with no Spring and no disk |
| **Optimistic locking** | `Transaction.version`, `ETag` / `If-Match` | Stale edits are rejected with 412 instead of silently overwriting |
| **Value objects** | `TaxLine`, `LineItem`, `ParsedReceipt`, `ReconciliationResult` | Read-only, compared by value |
| **Central error handling** | `ErrorCode` enum + `ApiException` (+ `ReconciliationException`) + `ErrorHandler` | Each error code has one fixed HTTP status; every error, including Spring's own, becomes the same JSON body |

`ReceiptParser` and `Reconciler` have no interfaces on purpose. They are the core business rules with only one sensible implementation, so an interface would add nothing.

## Data model

Stored in memory today. The equivalent relational schema:

```
receipts                          transactions                         tax_lines
─────────────────────────         ──────────────────────────────       ─────────────────────────────
id            rcpt_… PK           id             txn_… PK              id              PK
original_name text                receipt_id     FK → receipts, UNIQUE transaction_id  FK → transactions
storage_key   text                merchant       text                  name            text  (VAT, GST)
raw_ocr_text  text                date           date                  rate            decimal (0.19)
created_at    timestamp           currency       char(3)               amount          decimal(12,2)
                                  grand_total    decimal(12,2) NULL    inclusive       boolean
                                  itemize_status enum
                                  version        bigint                line_items
                                                                       ─────────────────────────────
                                                                       id              PK
                                                                       transaction_id  FK → transactions
                                                                       position        int
                                                                       description     text
                                                                       amount          decimal(12,2)
```

- **One receipt, one transaction:** `transactions.receipt_id` is `UNIQUE`. Processing again updates that row; it never inserts a second one.
- **Taxes are their own rows** (`tax_lines`), not a single tax number on the transaction.
- **Raw OCR text is stored on the receipt**, and it's the only input to re-itemize.
- **`grand_total` is nullable**: a receipt with no readable total is kept, with `itemize_status = FAILED`.
- **Money is `decimal`** (`BigDecimal` in code), never floating point.

## Concurrency

Three layers, each covering a different failure:

| Risk | Protection |
|---|---|
| Two requests change the same transaction at the same moment and one update is lost | **One lock per receipt** (`ReceiptService.lockFor`): changes to the same receipt/transaction run one at a time; different receipts run in parallel |
| Two `process` calls on the same receipt create two transactions | Inside the lock the receipt is re-read, so the second call sees the first call's transaction and updates it |
| A user edits based on an old read and overwrites a newer change | **Optimistic locking** on PATCH: `version` / `ETag`, checked against `If-Match`. A stale edit gets 412 `VERSION_CONFLICT` and nothing changes |
| A reader sees a half-updated transaction | Repositories store and return **copies**; a change becomes visible only when it's saved, all at once |

OCR and parsing run **outside** the lock, because a real OCR call is slow and shouldn't block other edits. Only the create-or-update is locked. Race conditions are covered by multi-threaded tests in `ReceiptServiceTest`, and I checked that those tests fail when the lock is removed.

`If-Match` is optional, so simple clients (and the brief's curls) still work. Without it, the last write wins, but updates are still applied one at a time and never interleaved.

## Assumptions

- **Item amounts are net** (before tax), matching `gold.json`. The reconciliation rule is `sum(items) + sum(non-inclusive taxes) == grand_total`, ±0.01.
- **Inclusive taxes** ("incl. VAT") are already inside the prices, so they're not added. This is what makes the taxi receipt work.
- **Tax-only receipt:** `line_items` is empty and the status is `NEEDS_REVIEW`. `gold.json` also allows one fallback item equal to the total; an empty list is the more honest choice, because nothing was actually itemized.
- **PATCH replaces the whole item list.** Edit, merge and split are all "send the list you want", validated in one place. Taxes and the total are not user-editable.
- **The user can add any item they like** (even a negative "discount" line), as long as the result reconciles. The rule against inventing lines applies to the system, not to a deliberate user edit.

## Trade-offs

| Choice | Why | Cost |
|---|---|---|
| In-memory storage | The brief allows it; zero setup for the reviewer | Data is lost on restart; locks live in one process |
| Stubbed OCR | The brief says matching `gold.json` is the bar | No real image reading |
| A line-rule parser (regex) | Simple, predictable, easy to test | Tied to the fixture format (see limits below) |
| Full-list PATCH | One code path for edit / merge / split | No per-item history; line items have no ids in the API |
| Optional `If-Match` | Keeps the simple curls working | Clients that skip it get last-write-wins |
| `process` is synchronous | Simple request/response | A slow real OCR call would hold the HTTP request open |

## Known limits of the parser

It handles the fixture format. On real receipts it would miss:

- comma decimals (`3,50`) and currency symbols (`€3.50`);
- totals written differently (`TOTAL EUR 17.85`, `Summe`, `Amount due`);
- receipts without `MERCHANT:` / `DATE:` / `CURRENCY:` labels (real receipts have a shop name at the top, not a label);
- dates in other formats (`12.03.2026`), which are kept as text, not normalized;
- quantities (`2 x Espresso  7.00`) and per-item tax.

Nothing is guessed: an unreadable line is skipped, and a missing total gives `FAILED`, so these limits show up as `NEEDS_REVIEW` / `FAILED` rather than wrong numbers.

## Production path

What I'd change, in order, to run this for real:

1. **Database** (Postgres) with the schema above. Replace the per-receipt lock with a row version (`UPDATE … WHERE version = ?`), which works across many servers.
2. **Real OCR / VLM** behind `OcrEngine`, with timeouts, retries and a circuit breaker; the extraction step would return structured fields instead of relying on the regex parser.
3. **Asynchronous processing:** `process` returns `202 Accepted`, a worker does OCR from a queue, and the client polls or gets a webhook. Add an idempotency key so retries don't duplicate work.
4. **Object storage** (S3) behind `FileStorage`, with encryption at rest and a retention policy.
5. **Protect user edits:** record whether items are `AUTO` or `USER`, so processing again doesn't overwrite a user's corrections without asking.
6. **Auth and tenancy:** every receipt belongs to a user/company, and every query is scoped to it.
7. **Line item ids and an audit trail**, so edits are traceable per item.
8. **Metrics and alerts:** processing time, the `NEEDS_REVIEW` / `FAILED` rate, and the 409 / 412 rate.

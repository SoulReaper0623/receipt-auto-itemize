# Receipt upload, taxes, auto-itemize (Take-home A)

A small HTTP API (Java 8, Spring Boot 2.7). It takes an uploaded receipt and creates **one transaction**, with its **tax lines** stored as separate records and **auto-itemized line items**. It flags `NEEDS_REVIEW` when the numbers don't add up, and it never invents a balancing line.

## Run

### Prerequisites

- **Java 8 or newer.** The code targets Java 8 (1.8); it has been tested on JDK 8 and JDK 17.
- **Nothing else.** Maven is **not** needed: the included Maven wrapper (`./mvnw`) downloads the right Maven version (3.9.9) on first use.
- `curl`, for the examples below (or Postman).

Check your Java version:

```bash
java -version
```

If you have several JDKs and want to use a specific one, point `JAVA_HOME` at it first:

```bash
# macOS
export JAVA_HOME=$(/usr/libexec/java_home -v 1.8)
# Linux (example path)
export JAVA_HOME=/usr/lib/jvm/java-8-openjdk-amd64
# Windows (PowerShell, example path)
$env:JAVA_HOME = "C:\Program Files\Java\jdk1.8.0_291"
```

### Start the server (one command)

Run from the **repo root**. The stub OCR reads fixtures from `task-a/fixtures/task-a/` relative to the current directory.

```bash
./mvnw spring-boot:run
```

On Windows use `mvnw.cmd spring-boot:run`.

The server starts on **`http://localhost:8067`**. Check it's up:

```bash
curl http://localhost:8067/health
# {"status":"ok"}
```

Stop it with `Ctrl+C`.

### All commands

| What | Command |
|---|---|
| Start the server | `./mvnw spring-boot:run` |
| Start on another port | `./mvnw spring-boot:run -Dspring-boot.run.arguments=--server.port=9090` |
| Run all tests | `./mvnw test` |
| Run one test class | `./mvnw test -Dtest=ApiTest` |
| Build a runnable jar | `./mvnw package` (add `-DskipTests` to skip tests) |
| Run the jar (from repo root) | `java -jar target/navana-1.0-SNAPSHOT.jar` |
| Run the jar on another port | `java -jar target/navana-1.0-SNAPSHOT.jar --server.port=9090` |
| Clean build output and uploads | `./mvnw clean && rm -rf uploads` |

### Troubleshooting

| Problem | Fix |
|---|---|
| `Port 8067 was already in use` | Something else is on 8067. Find it with `lsof -i :8067` (macOS/Linux), or start on another port (see above). |
| `./mvnw: Permission denied` | `chmod +x mvnw` |
| `JAVA_HOME is not defined correctly` | Point `JAVA_HOME` at a JDK folder (see Prerequisites), or unset it to use the `java` on your `PATH`. |
| Image uploads come back `FAILED` | Start the server from the repo root, so `task-a/fixtures/task-a/` can be found. |

### Tests

`./mvnw test` runs 52 tests:

| Test class | Covers |
|---|---|
| `ReceiptParserTest` | extraction from the 3 fixtures, checked against `gold.json` |
| `InclusiveTaxTest` | the `inclusive` tax flag: how it's parsed and how reconciliation uses it |
| `ReceiptServiceTest` | the service wired with fakes (no Spring, no disk), plus multi-threaded race tests for locking and versions |
| `ApiTest` | every endpoint end to end, every error code and status, prefixed ids, `ETag` / `If-Match`, `X-Request-Id` |

## OCR: stubbed

**No OCR or LLM vendor is called, and no API key is needed.**

- If you upload a **`.txt`** file, its content is used as the OCR text.
- If you upload a **`.png` / `.jpg` / `.pdf`**, it is treated as an already-known fixture image. The text is read from `task-a/fixtures/task-a/<same base name>.txt`, so for example `receipt-clean.png` becomes `receipt-clean.txt`. An unknown image produces empty OCR text, and the result is `itemize_status: FAILED`.

The raw OCR text is stored with the receipt, and re-itemize always works from that stored text.

## Reconciliation rule

```
sum(line_items) + sum(taxes where inclusive = false) == grand_total   (±0.01)
```

- `VAT 19%  2.85` means the tax is added on top (`inclusive: false`).
- `incl. VAT 19%  3.83` means the tax is already inside the prices (`inclusive: true`), so it is not added.
- Line item amounts are **net**, matching `gold.json`.

| `itemize_status` | When |
|---|---|
| `COMPLETE` | the items reconcile |
| `NEEDS_REVIEW` | there are no items, or the items don't reconcile. The transaction is kept as parsed, and no fake line is added |
| `FAILED` | no total could be parsed (e.g. empty OCR) |

Fixture results: `receipt-clean` gives COMPLETE, `receipt-tax-only` gives NEEDS_REVIEW with `line_items: []`, and `receipt-mismatch` gives NEEDS_REVIEW with its items and total left exactly as parsed.

## Curls (Postman-ready)

Each block is a single command you can paste straight into Postman (**Import → Raw text**) or run in a terminal from the repo root.

**Placeholders:** `{{receipt_id}}` and `{{transaction_id}}` are Postman variables. Paste the value returned by the upload / process call, or set them once as collection variables. In a terminal, replace them by hand.

**File uploads in Postman:** after importing an upload request, open **Body → form-data** and click **Select files** on the `file` row to pick the fixture from `task-a/fixtures/task-a/`. Postman only resolves relative file paths against its own working directory.

### 1. Health

```bash
curl --location --request GET 'http://localhost:8067/health'
```

Response: `{"status":"ok"}`

### 2. Upload a receipt: `POST /receipts`

Multipart form, field `file`. Returns **201** `{"receipt_id":"rcpt_…"}`. Copy it into `{{receipt_id}}`.

Clean receipt (items + VAT reconcile, ends as `COMPLETE`):

```bash
curl --location --request POST 'http://localhost:8067/receipts' \
--form 'file=@"task-a/fixtures/task-a/receipt-clean.txt"'
```

Tax-only receipt (taxi, no items, ends as `NEEDS_REVIEW` with `line_items: []`):

```bash
curl --location --request POST 'http://localhost:8067/receipts' \
--form 'file=@"task-a/fixtures/task-a/receipt-tax-only.txt"'
```

Mismatch receipt (hotel shop, items don't add up, ends as `NEEDS_REVIEW` with no fake line):

```bash
curl --location --request POST 'http://localhost:8067/receipts' \
--form 'file=@"task-a/fixtures/task-a/receipt-mismatch.txt"'
```

Image or PDF: the stub OCR maps it to the fixture with the same base name, so any valid PNG named `receipt-clean.png` gives the clean result:

```bash
curl --location --request POST 'http://localhost:8067/receipts' \
--form 'file=@"receipt-clean.png"'
```

### 3. Process the receipt: `POST /receipts/{id}/process`

Runs OCR + extraction and creates the transaction (calling it again updates the **same** transaction). Returns the transaction. Copy its `id` (`txn_…`) into `{{transaction_id}}`.

```bash
curl --location --request POST 'http://localhost:8067/receipts/{{receipt_id}}/process'
```

Response (clean receipt):

```json
{
  "id": "txn_60e61ef2fff54000…",
  "receipt_id": "rcpt_460b9c758013…",
  "merchant": "Cafe Mitte",
  "date": "2026-03-12",
  "currency": "EUR",
  "grand_total": 17.85,
  "taxes": [{ "name": "VAT", "rate": 0.19, "amount": 2.85, "inclusive": false }],
  "line_items": [
    { "description": "Espresso", "amount": 3.50 },
    { "description": "Sandwich", "amount": 8.90 },
    { "description": "Mineral water", "amount": 2.60 }
  ],
  "itemize_status": "COMPLETE",
  "version": 1
}
```

### 4. Get the transaction: `GET /transactions/{id}`

Returns the header, taxes, line items and `itemize_status`.

```bash
curl --location --request GET 'http://localhost:8067/transactions/{{transaction_id}}'
```

### 5. Re-itemize: `POST /transactions/{id}/itemize`

Rebuilds the line items from the **stored** OCR text. Only the items are replaced, and it stays the same transaction.

```bash
curl --location --request POST 'http://localhost:8067/transactions/{{transaction_id}}/itemize'
```

### 6. Edit items: `PATCH /transactions/{id}/items`

The body is the **full list** of items you want, so edit, merge and split are all the same call. If the items no longer add up to the total (with stored taxes), the response is **409** and nothing is saved.

These examples use the **clean receipt** transaction (total 17.85, VAT 2.85 on top).

Merge (3.50 + 11.50 + 2.85 = 17.85) returns **200** and `COMPLETE`:

```bash
curl --location --request PATCH 'http://localhost:8067/transactions/{{transaction_id}}/items' \
--header 'Content-Type: application/json' \
--data-raw '{"items":[{"description":"Espresso","amount":3.50},{"description":"Sandwich + water","amount":11.50}]}'
```

Split (3.50 + 4.45 + 4.45 + 2.60 + 2.85 = 17.85) returns **200** and `COMPLETE`:

```bash
curl --location --request PATCH 'http://localhost:8067/transactions/{{transaction_id}}/items' \
--header 'Content-Type: application/json' \
--data-raw '{"items":[{"description":"Espresso","amount":3.50},{"description":"Sandwich half 1","amount":4.45},{"description":"Sandwich half 2","amount":4.45},{"description":"Mineral water","amount":2.60}]}'
```

An edit that doesn't add up returns **409**:

```bash
curl --location --request PATCH 'http://localhost:8067/transactions/{{transaction_id}}/items' \
--header 'Content-Type: application/json' \
--data-raw '{"items":[{"description":"Espresso","amount":3.50}]}'
```

Response: `{"code":"ITEMS_DO_NOT_RECONCILE","message":"line items do not reconcile with the transaction total","status":409,"request_id":"…","details":{"expected":17.85,"actual":6.35,"difference":11.50}}`

Fixing the **mismatch receipt** (hotel shop) transaction (4.00 + 12.60 + 1.90 VAT = 18.50) returns **200** and `COMPLETE`:

```bash
curl --location --request PATCH 'http://localhost:8067/transactions/{{transaction_id}}/items' \
--header 'Content-Type: application/json' \
--data-raw '{"items":[{"description":"Water","amount":4.00},{"description":"Snacks","amount":12.60}]}'
```

Safe edit with **`If-Match`**: send the `ETag` you last got for this transaction (e.g. `"1"`). A stale version returns **412** `VERSION_CONFLICT` and nothing is saved:

```bash
curl --location --request PATCH 'http://localhost:8067/transactions/{{transaction_id}}/items' \
--header 'Content-Type: application/json' \
--header 'If-Match: "1"' \
--data-raw '{"items":[{"description":"Espresso","amount":3.50},{"description":"Sandwich + water","amount":11.50}]}'
```

Response on conflict: `{"code":"VERSION_CONFLICT","message":"transaction was changed since you read it; GET it again and retry with the new ETag","status":412,"request_id":"…","details":{"expected_version":1,"current_version":2}}`

### 7. Error cases

Wrong file type returns **415** `UNSUPPORTED_FILE_TYPE`:

```bash
curl --location --request POST 'http://localhost:8067/receipts' \
--form 'file=@"pom.xml"'
```

Not a multipart upload returns **415** `UNSUPPORTED_MEDIA_TYPE`, with a hint on how to send the file:

```bash
curl --location --request POST 'http://localhost:8067/receipts' \
--header 'Content-Type: text/plain' \
--data-raw 'MERCHANT: Cafe Mitte'
```

Item without an amount returns **400** `VALIDATION_FAILED` (`"message":"each item needs a description and an amount"`):

```bash
curl --location --request PATCH 'http://localhost:8067/transactions/{{transaction_id}}/items' \
--header 'Content-Type: application/json' \
--data-raw '{"items":[{"description":"No amount"}]}'
```

Unknown ids return **404** `RECEIPT_NOT_FOUND` / `TRANSACTION_NOT_FOUND`:

```bash
curl --location --request POST 'http://localhost:8067/receipts/does-not-exist/process'
```

```bash
curl --location --request GET 'http://localhost:8067/transactions/does-not-exist'
```

## Concurrency (safe edits)

Every transaction has a `version`. It's in the JSON and in the **`ETag`** response header, starts at `1`, and goes up by 1 on every change (process again, re-itemize, PATCH).

To make sure you don't overwrite someone else's change, send the version you last read as **`If-Match`** on `PATCH /transactions/{id}/items`:

- The version still matches: the change is applied, and you get the new `ETag`.
- Someone changed it in the meantime: **412 `VERSION_CONFLICT`**, nothing is changed, and `details` shows `expected_version` and `current_version`. GET the transaction again and retry.
- No `If-Match` header: the change is applied without the check (last write wins).

Inside the server, changes to the same receipt/transaction run one at a time (one lock per receipt), while different receipts run in parallel. Stored objects are copied on read and write, so nobody ever sees a half-finished update.

## Ids

Ids carry their kind as a prefix, so they can't be confused: receipts are `rcpt_<32 hex>` and transactions are `txn_<32 hex>`. If you pass the wrong kind (e.g. a `rcpt_…` id to `/transactions/{id}`), the 404 message says so.

## Errors

Every error has the same JSON shape, with a stable `code` you can match on:

```json
{
  "code": "ITEMS_DO_NOT_RECONCILE",
  "message": "line items do not reconcile with the transaction total",
  "status": 409,
  "request_id": "3e8640b7-2038-44a0-b672-285e1f5eb234",
  "details": { "expected": 17.85, "actual": 3.85, "difference": 14.00 }
}
```

`details` appears only when there is something extra to say (the 409 and the 412). `request_id` is also returned as the `X-Request-Id` header on **every** response, and it's printed on every server log line for that request, so you can find the logs for one call.

| HTTP | `code` | When |
|---|---|---|
| 400 | `MALFORMED_REQUEST` | the JSON body can't be parsed |
| 400 | `VALIDATION_FAILED` | PATCH body is missing `items`, or an item has no description or amount |
| 400 | `FILE_REQUIRED` | multipart upload without a non-empty `file` field |
| 400 | `INVALID_FILE_NAME` | file name has path characters or other unsafe characters |
| 404 | `RECEIPT_NOT_FOUND` | unknown receipt id |
| 404 | `TRANSACTION_NOT_FOUND` | unknown transaction id |
| 404 | `ROUTE_NOT_FOUND` | no such endpoint |
| 405 | `METHOD_NOT_ALLOWED` | e.g. `GET /receipts` |
| 409 | `ITEMS_DO_NOT_RECONCILE` | PATCH items don't add up to the total; nothing is saved |
| 412 | `VERSION_CONFLICT` | `If-Match` version is stale; someone changed the transaction since you read it |
| 413 | `FILE_TOO_LARGE` | upload over 10 MB |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | upload is not `multipart/form-data`, or PATCH is not `application/json` |
| 415 | `UNSUPPORTED_FILE_TYPE` | extension isn't png / jpg / jpeg / pdf / txt |
| 415 | `FILE_CONTENT_MISMATCH` | file bytes or declared content type don't match the extension |
| 500 | `INTERNAL_ERROR` | unexpected server error; the details go to the log (with the stack trace), never to the client |

## Logging

A sample line:

```
INFO [3e8640b7-…] o.p.web.RequestLoggingFilter : PATCH /transactions/8ad1…/items -> 409 (67 ms)
```

- **Every request** gets one access line: method, path, status and duration.
- **Business events** are logged at INFO: receipt uploaded, processed (created or updated, item and tax counts, `itemize_status`), re-itemized, and items replaced or rejected.
- **Client errors (4xx)** are logged at WARN with their `code`. **Server errors (5xx)** are logged at ERROR with the stack trace.
- **No receipt data is logged:** no OCR text, merchant names, amounts or uploaded filenames. Logs contain only ids, counts, statuses and codes. User-controlled values in paths are sanitized so they can't inject fake log lines.

## Notes

- Storage is in memory, so data is lost on restart. Uploaded files are saved to `./uploads/` (gitignored) as `<receipt_id>.<ext>`, for example `rcpt_460b….png`. The name the user sent is never used as a path.
- Uploads are validated by file name, extension (png/jpg/jpeg/pdf/txt), declared content type, real file content (magic bytes / UTF-8 text) and size (10 MB max).
- Out of scope, as the brief says: auth, UI, persistence beyond memory, and a real OCR vendor.

See [ARCHITECTURE.md](ARCHITECTURE.md) for the design.

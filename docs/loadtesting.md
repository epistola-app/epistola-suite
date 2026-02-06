# Load Testing

Epistola Suite includes an embedded load testing feature that allows you to measure document generation performance without external tools. Load tests stress-test the system with high concurrency to validate installation capacity and identify performance bottlenecks.

## Overview

The load testing module (`modules/loadtest`) enables users to:
- Generate N documents concurrently (1-10,000 documents)
- Control concurrency level (1-500 concurrent requests)
- Measure real-world document generation performance
- Collect detailed performance metrics (latency percentiles, throughput, error rates)
- Track individual request timing for debugging

## Architecture

### Module Structure

```
modules/loadtest/               # Business logic module
├── model/                      # Domain models (LoadTestRun, LoadTestRequest, etc.)
├── commands/                   # Commands (StartLoadTest, CancelLoadTest)
├── queries/                    # Queries (GetLoadTestRun, ListLoadTestRuns, etc.)
├── batch/                      # Execution engine (LoadTestExecutor, LoadTestPoller)
├── cleanup/                    # LoadTestCleanupScheduler (90-day retention for runs)
└── resources/db/migration/     # V11-V13 migrations

apps/epistola/                  # UI layer
└── loadtest/                   # LoadTestHandler, LoadTestRoutes
    └── templates/              # Thymeleaf templates
```

### Separation of Concerns

- **`modules/loadtest`**: Business logic (CQRS commands/queries, async execution)
- **`apps/epistola`**: UI layer (HTTP handlers, Thymeleaf templates, HTMX)

This follows the same pattern as `modules/epistola-core` and enables future reuse in other applications.

## How It Works

### 1. Job Submission Phase (Fast)

When a load test starts, the `LoadTestExecutor`:
1. Creates N async document generation jobs concurrently
2. Each job is queued as PENDING in `document_generation_requests`
3. Returns immediately (job queueing takes < 1 second for 1000 jobs)

```kotlin
val generationRequest = mediator.send(
    GenerateDocument(
        tenantId = run.tenantId,
        templateId = run.templateId,
        variantId = run.variantId,
        versionId = run.versionId,
        environmentId = run.environmentId,
        data = run.testData,
        filename = "loadtest-${run.id}-$sequenceNumber.pdf",
        correlationId = "loadtest-${run.id}-$sequenceNumber",
    ),
)
```

### 2. Async Processing Phase (Measured)

Background workers (`DocumentGenerationExecutor`) process jobs:
- Jobs transition: PENDING → IN_PROGRESS → COMPLETED/FAILED
- Each job generates an actual PDF document
- Database tracks `started_at` and `completed_at` timestamps

### 3. Polling Phase (Load Test Executor)

The `LoadTestExecutor` polls the database every 500ms:
```kotlin
SELECT
    dgr.id,
    dgr.created_at,
    dgr.started_at,
    dgr.completed_at,
    dgr.status,
    dgr.error_message,
    dgi.document_id
FROM document_generation_requests dgr
LEFT JOIN document_generation_items dgi ON dgi.request_id = dgr.id
WHERE dgr.id IN (<jobIds>)
```

It tracks:
- How many jobs have completed
- How many jobs have failed
- Actual timing data from database timestamps
- Live progress updates to `load_test_runs` table

### 4. Metrics Calculation

Once all jobs reach terminal state (COMPLETED/FAILED/CANCELLED):
```kotlin
val duration = Duration.between(job.startedAt, job.completedAt).toMillis()
```

Metrics calculated:
- **Throughput**: Requests per second
- **Latency**: Min, max, avg, p50, p95, p99
- **Success rate**: Percentage of successful requests
- **Error summary**: Count by error type (VALIDATION, TIMEOUT, CONFIGURATION, GENERATION)

### 5. Document Cleanup

After metrics are saved, all generated documents are deleted:
```kotlin
DELETE FROM documents
WHERE correlation_id LIKE 'loadtest-${runId}-%'
```

Load test documents are not retained since they have no business value after metrics collection.

## Database Schema

### load_test_runs

Stores load test configuration and aggregated metrics.

```sql
CREATE TABLE load_test_runs (
    id UUID PRIMARY KEY,                        -- UUIDv7
    tenant_id VARCHAR(63) NOT NULL,
    template_id VARCHAR(50) NOT NULL,
    variant_id VARCHAR(50) NOT NULL,
    version_id INTEGER,                         -- Explicit version
    environment_id VARCHAR(30),                 -- Or environment
    target_count INTEGER NOT NULL,              -- Documents to generate (1-10000)
    concurrency_level INTEGER NOT NULL,         -- Concurrent requests (1-500)
    test_data JSONB NOT NULL,                   -- Test data for all requests
    status VARCHAR(20) NOT NULL,                -- PENDING, RUNNING, COMPLETED, FAILED, CANCELLED
    claimed_by VARCHAR(255),                    -- Instance that claimed this run
    claimed_at TIMESTAMP WITH TIME ZONE,

    -- Progress (updated during execution)
    completed_count INTEGER NOT NULL DEFAULT 0,
    failed_count INTEGER NOT NULL DEFAULT 0,

    -- Aggregated metrics (populated after completion)
    total_duration_ms BIGINT,
    avg_response_time_ms DOUBLE PRECISION,
    min_response_time_ms BIGINT,
    max_response_time_ms BIGINT,
    p50_response_time_ms BIGINT,
    p95_response_time_ms BIGINT,
    p99_response_time_ms BIGINT,
    requests_per_second DOUBLE PRECISION,
    success_rate_percent DOUBLE PRECISION,
    error_summary JSONB,                        -- {"VALIDATION": 5, "TIMEOUT": 2}

    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    started_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE
);
```

### Request Details (via document_generation_requests)

Load test request details are **not** duplicated in a separate table. Instead, they are queried directly from `document_generation_requests` using the `batch_id` link.

**Benefits:**
- Single source of truth (no data duplication)
- Always accurate timing data (never stale)
- Automatic cleanup via partition dropping (3 months retention)
- 6MB saved per 10K-request load test

Request details queried via:
```sql
SELECT
    id,
    created_at as started_at,
    completed_at,
    EXTRACT(EPOCH FROM (completed_at - started_at)) * 1000 as duration_ms,
    CASE WHEN status = 'COMPLETED' THEN true ELSE false END as success,
    error_message,
    document_id,
    ROW_NUMBER() OVER (ORDER BY created_at) as sequence_number
FROM document_generation_requests
WHERE batch_id = :batchId
```

## Configuration

### Application Properties

```yaml
epistola:
  loadtest:
    enabled: true
    max-concurrency: 200                        # Recommended based on database pool size
    polling:
      enabled: true
      interval-ms: 5000                         # Load test poller interval
      stale-timeout-minutes: 10                 # Recover stale RUNNING tests
    cleanup:
      runs-retention-days: 90                   # Keep load test run data for 90 days

  # Request details are stored in document_generation_requests and cleaned
  # automatically via partition dropping (3 months retention)
  partitions:
    retention-months: 3                         # Partition retention (affects request data)
```

### Production Recommendations

**Database Connection Pool:**
- Ensure connection pool size supports target concurrency
- Example: HikariCP with `maximumPoolSize: 50` supports ~50 concurrent document generations
- Load tests with concurrency > pool size will queue requests (realistic load testing)

**Resource Limits:**
- JVM heap: Increase if testing with very high concurrency (500+)
- Virtual threads: JDK 21+ handles thousands of concurrent requests efficiently

**Monitoring:**
- Watch database CPU/memory during load tests
- Monitor background job queue depth (`document_generation_requests` with PENDING status)
- Track PDF rendering time in application logs

## Usage

### Via UI

1. Navigate to `/tenants/{tenantId}/load-tests`
2. Click "Start New Load Test"
3. Configure:
   - **Template**: Select template and variant
   - **Version**: Choose explicit version or environment
   - **Target Count**: Number of documents (1-10,000)
   - **Concurrency**: Concurrent requests (1-500)
   - **Test Data**: JSON data for all requests
4. Click "Start Load Test"
5. Monitor progress in real-time (updates every 2 seconds via HTMX polling)
6. View detailed metrics after completion

### Programmatically

```kotlin
val run = StartLoadTest(
    tenantId = TenantId.of("tenant-1"),
    templateId = TemplateId.of("invoice"),
    variantId = VariantId.of("default"),
    versionId = VersionId.of(5),
    environmentId = null,
    targetCount = 100,
    concurrencyLevel = 10,
    testData = objectMapper.createObjectNode().apply {
        put("invoiceNumber", "TEST-001")
        put("amount", 1000.00)
    },
).execute()

// Wait for completion (poll until COMPLETED)
val finalRun = GetLoadTestRun(tenantId, run.id).query()
println("Success rate: ${finalRun.successRatePercent}%")
println("p95 latency: ${finalRun.p95ResponseTimeMs}ms")
```

## Interpreting Results

### Metrics Dashboard

After a load test completes, the detail view shows:

**Overall Stats:**
- Requests: `150 / 100` (completed/target)
- Success Rate: `98.0%`
- Requests/sec: `12.5`

**Latency Distribution:**
- Avg Response Time: `750 ms`
- p50 (Median): `680 ms`
- p95: `1,200 ms`
- p99: `1,450 ms`
- Min / Max: `450 / 2,100 ms`

**Error Summary (if failures):**
```
VALIDATION: 2
TIMEOUT: 1
```

### What to Look For

**Good Performance:**
- Success rate > 99%
- p95 latency within acceptable range for use case
- Consistent throughput (requests/sec)
- Low error rates

**Performance Issues:**
- High p99 latency (indicates tail latency problems)
- Many TIMEOUT errors (increase timeout or optimize rendering)
- Many VALIDATION errors (check test data schema)
- Low throughput (check database/CPU bottlenecks)

**Capacity Planning:**
- Run tests with increasing concurrency (10, 50, 100, 200)
- Find the "knee" where latency increases sharply
- Set production limits below that threshold

## Detailed Request Log

For debugging, view individual request details:
1. Click "View Detailed Request Log" on test detail page
2. See all N requests with:
   - Sequence number
   - Start time
   - Duration (ms)
   - Status (SUCCESS/FAILED)
   - Error type and message (if failed)

Paginated with 100 requests per page.

## Troubleshooting

### Load Test Completes Too Fast

**Symptom**: Test shows "COMPLETED" with 0ms average response time

**Cause**: Jobs are queued but not processed

**Solutions**:
- Check that `JobPoller` is running (background worker)
- Verify `spring.profiles.active` doesn't include `sync-mode` (which disables async processing)
- Check application logs for job processing errors

### Load Test Times Out

**Symptom**: Test stuck in RUNNING status for > 10 minutes

**Cause**: Jobs are not completing

**Solutions**:
- Check database for jobs stuck in IN_PROGRESS status
- Verify `DocumentGenerationExecutor` is processing jobs
- Check for errors in application logs
- Manually cancel the test via UI

### High Failure Rate

**Symptom**: Many requests fail with errors

**Common Causes**:
- **VALIDATION errors**: Test data doesn't match template schema
- **TIMEOUT errors**: PDF rendering takes too long (increase timeout or optimize template)
- **CONFIGURATION errors**: Template/variant/version not found
- **GENERATION errors**: PDF rendering failures (check template syntax)

**Debugging**:
1. View detailed request log
2. Check error messages for specific failures
3. Test single document generation manually to validate template
4. Review template complexity (many images = slower rendering)

### Database Connection Pool Exhausted

**Symptom**: Errors about connection pool timeout

**Cause**: Concurrency level exceeds available database connections

**Solutions**:
- Reduce load test concurrency
- Increase `spring.datasource.hikari.maximum-pool-size`
- Monitor connection usage during tests

## Best Practices

### Test Data

- Use realistic data (actual field values, not placeholders)
- Include edge cases (long text, special characters, missing optional fields)
- Validate data against schema before running large tests

### Concurrency Levels

Start small and increase gradually:
1. **Baseline**: 1 concurrent (validate functionality)
2. **Low load**: 10 concurrent (typical usage)
3. **Medium load**: 50 concurrent (peak usage)
4. **High load**: 100-200 concurrent (stress test)
5. **Extreme load**: 500 concurrent (capacity limits)

### Test Frequency

- **Development**: Run after template changes to catch performance regressions
- **Staging**: Run before deployment to validate changes
- **Production**: Run periodically to monitor capacity (off-peak hours)

### Cleanup

- Load test documents are auto-deleted after metrics collection
- Request details (in `document_generation_requests`) are auto-deleted after 3 months via partition dropping
- Aggregated metrics in `load_test_runs` are retained for 90 days (configurable)

## Performance Tuning

### Template Optimization

**Reduce complexity:**
- Minimize number of blocks
- Optimize image sizes (compress before embedding)
- Use CSS efficiently (avoid duplicate styles)

**Measure impact:**
- Run baseline load test
- Make optimization change
- Run new load test
- Compare p95 latency

### Database Optimization

**Indexes:**
- Ensure proper indexes on foreign keys
- Monitor slow query logs during load tests

**Connection pool:**
- Set `maximumPoolSize` based on CPU cores and workload
- Monitor connection usage with metrics

### Application Tuning

**Virtual threads:**
- JDK 21+ virtual threads handle high concurrency efficiently
- No semaphore limits in `LoadTestExecutor` (unlike `DocumentGenerationExecutor`)

**Memory:**
- Increase heap size for very high concurrency tests
- Monitor GC overhead during tests

## Future Enhancements

Potential improvements (not in MVP):

- **Time-based tests**: Run for N seconds instead of N documents
- **Visual charts**: Response time histogram, throughput over time
- **Data variation**: Use different data for each request
- **Export results**: CSV, JSON formats
- **Comparison view**: Overlay metrics from multiple tests
- **Scheduled tests**: Run load tests on cron schedule
- **Alerting**: Notify when metrics exceed thresholds
- **Distributed testing**: Multi-node load generation

## Related Documentation

- [Generation](./generation.md) - Document generation architecture
- [Testing](./testing.md) - General testing approach
- [Database Schema](../modules/loadtest/src/main/resources/db/migration/V11__load_test_infrastructure.sql) - Load test tables

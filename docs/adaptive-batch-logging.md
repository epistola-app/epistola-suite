# Adaptive Batch Polling Logs

This document shows example log output from the adaptive batch job polling feature.

## Startup Logs

When the application starts, you'll see initialization messages:

```
INFO  JobPoller - Job poller started: instanceId=hostname-12345, maxConcurrentJobs=2, pollInterval=5000ms
INFO  AdaptiveBatchSizer - Adaptive batch sizer initialized: minBatchSize=1, maxBatchSize=10, fastThreshold=2000ms, slowThreshold=5000ms
```

## Poll Cycle Logs

### Claiming Jobs

When jobs are available and claimed:

```
INFO  JobPoller - Claimed 1 job(s) | Requested batch: 1, Available slots: 2, Active: 0/2
DEBUG JobPoller - Processing request abc-123 (active jobs: 1)
```

When no jobs are available:

```
DEBUG JobPoller - No pending jobs available | Batch size: 3, Active: 0/2
```

When at max capacity:

```
DEBUG JobPoller - Max concurrent jobs reached (2), skipping poll
```

## Job Completion Logs

When a job completes:

```
DEBUG AdaptiveBatchSizer - Job completed in 1500ms, EMA updated: 0ms → 1500ms
DEBUG AdaptiveBatchSizer - Batch size unchanged: 1 | STABLE: Normal processing (2000ms < EMA 1500ms < 5000ms) | Last job: 1500ms
INFO  JobPoller - Job completed: abc-123 in 1500ms | Active: 0/2
```

## Batch Size Scaling Decisions

### Scaling Up (Fast Processing)

When processing is consistently fast (< 2 seconds), the batch size increases:

```
DEBUG AdaptiveBatchSizer - Job completed in 800ms, EMA updated: 1500ms → 1360ms
INFO  AdaptiveBatchSizer - Batch size adjusted: 1 → 2 | SCALING UP: Fast processing (EMA 1360ms < 2000ms threshold) | Last job: 800ms

DEBUG AdaptiveBatchSizer - Job completed in 900ms, EMA updated: 1360ms → 1268ms
INFO  AdaptiveBatchSizer - Batch size adjusted: 2 → 3 | SCALING UP: Fast processing (EMA 1268ms < 2000ms threshold) | Last job: 900ms

DEBUG AdaptiveBatchSizer - Job completed in 1000ms, EMA updated: 1268ms → 1214ms
INFO  AdaptiveBatchSizer - Batch size adjusted: 3 → 4 | SCALING UP: Fast processing (EMA 1214ms < 2000ms threshold) | Last job: 1000ms
```

Next poll cycle claims more jobs:

```
INFO  JobPoller - Claimed 4 job(s) | Requested batch: 4, Available slots: 2, Active: 0/2
```

### Scaling Down (Slow Processing)

When processing slows down (> 5 seconds), the batch size decreases:

```
DEBUG AdaptiveBatchSizer - Job completed in 7000ms, EMA updated: 1214ms → 2371ms
DEBUG AdaptiveBatchSizer - Batch size unchanged: 4 | STABLE: Normal processing (2000ms < EMA 2371ms < 5000ms) | Last job: 7000ms

DEBUG AdaptiveBatchSizer - Job completed in 8000ms, EMA updated: 2371ms → 3497ms
DEBUG AdaptiveBatchSizer - Batch size unchanged: 4 | STABLE: Normal processing (2000ms < EMA 3497ms < 5000ms) | Last job: 8000ms

DEBUG AdaptiveBatchSizer - Job completed in 9000ms, EMA updated: 3497ms → 4598ms
DEBUG AdaptiveBatchSizer - Batch size unchanged: 4 | STABLE: Normal processing (2000ms < EMA 4598ms < 5000ms) | Last job: 9000ms

DEBUG AdaptiveBatchSizer - Job completed in 10000ms, EMA updated: 4598ms → 5678ms
INFO  AdaptiveBatchSizer - Batch size adjusted: 4 → 3 | SCALING DOWN: Slow processing (EMA 5678ms > 5000ms threshold) | Last job: 10000ms

DEBUG AdaptiveBatchSizer - Job completed in 11000ms, EMA updated: 5678ms → 6742ms
INFO  AdaptiveBatchSizer - Batch size adjusted: 3 → 2 | SCALING DOWN: Slow processing (EMA 6742ms > 5000ms threshold) | Last job: 11000ms
```

Next poll cycle claims fewer jobs:

```
INFO  JobPoller - Claimed 2 job(s) | Requested batch: 2, Available slots: 2, Active: 0/2
```

### At Boundaries

When at maximum batch size (can't scale up further):

```
DEBUG AdaptiveBatchSizer - Job completed in 500ms, EMA updated: 1200ms → 1060ms
DEBUG AdaptiveBatchSizer - Batch size unchanged: 10 | AT MAX: Would scale up but already at maxBatchSize | Last job: 500ms
```

When at minimum batch size (can't scale down further):

```
DEBUG AdaptiveBatchSizer - Job completed in 12000ms, EMA updated: 8000ms → 8800ms
DEBUG AdaptiveBatchSizer - Batch size unchanged: 1 | AT MIN: Would scale down but already at minBatchSize | Last job: 12000ms
```

## Stable State

When processing times are within normal range (2-5 seconds), batch size remains stable:

```
DEBUG AdaptiveBatchSizer - Job completed in 3000ms, EMA updated: 3200ms → 3160ms
DEBUG AdaptiveBatchSizer - Batch size unchanged: 5 | STABLE: Normal processing (2000ms < EMA 3160ms < 5000ms) | Last job: 3000ms

DEBUG AdaptiveBatchSizer - Job completed in 3500ms, EMA updated: 3160ms → 3228ms
DEBUG AdaptiveBatchSizer - Batch size unchanged: 5 | STABLE: Normal processing (2000ms < EMA 3228ms < 5000ms) | Last job: 3500ms
```

## High-Throughput Scenario

With multiple jobs completing rapidly:

```
INFO  JobPoller - Claimed 5 job(s) | Requested batch: 5, Available slots: 10, Active: 0/10
DEBUG JobPoller - Processing request job-001 (active jobs: 1)
DEBUG JobPoller - Processing request job-002 (active jobs: 2)
DEBUG JobPoller - Processing request job-003 (active jobs: 3)
DEBUG JobPoller - Processing request job-004 (active jobs: 4)
DEBUG JobPoller - Processing request job-005 (active jobs: 5)

DEBUG AdaptiveBatchSizer - Job completed in 800ms, EMA updated: 1500ms → 1360ms
INFO  JobPoller - Job completed: job-001 in 800ms | Active: 4/10

DEBUG AdaptiveBatchSizer - Job completed in 850ms, EMA updated: 1360ms → 1258ms
INFO  JobPoller - Job completed: job-002 in 850ms | Active: 3/10

DEBUG AdaptiveBatchSizer - Job completed in 900ms, EMA updated: 1258ms → 1186ms
INFO  AdaptiveBatchSizer - Batch size adjusted: 5 → 6 | SCALING UP: Fast processing (EMA 1186ms < 2000ms threshold) | Last job: 900ms
INFO  JobPoller - Job completed: job-003 in 900ms | Active: 2/10

DEBUG AdaptiveBatchSizer - Job completed in 750ms, EMA updated: 1186ms → 1099ms
INFO  AdaptiveBatchSizer - Batch size adjusted: 6 → 7 | SCALING UP: Fast processing (EMA 1099ms < 2000ms threshold) | Last job: 750ms
INFO  JobPoller - Job completed: job-004 in 750ms | Active: 1/10

DEBUG AdaptiveBatchSizer - Job completed in 800ms, EMA updated: 1099ms → 1039ms
INFO  AdaptiveBatchSizer - Batch size adjusted: 7 → 8 | SCALING UP: Fast processing (EMA 1039ms < 2000ms threshold) | Last job: 800ms
INFO  JobPoller - Job completed: job-005 in 800ms | Active: 0/10
```

## Observing Metrics

The same information is available via Micrometer metrics:

```bash
# Check current batch size
curl -s http://localhost:4000/actuator/metrics/epistola.jobs.batch_size | jq '.measurements[0].value'
# Output: 7

# Check average processing time
curl -s http://localhost:4000/actuator/metrics/epistola.jobs.processing_time_ema_ms | jq '.measurements[0].value'
# Output: 1039.0

# Check throughput
curl -s http://localhost:4000/actuator/metrics/epistola.jobs.completed.total | jq '.measurements[0].value'
# Output: 42
```

## Log Levels

To see all adaptive behavior logs, ensure your logging configuration includes:

```yaml
logging:
  level:
    app.epistola.suite.documents.batch: DEBUG  # Shows all adaptive decisions
```

For production, use INFO level to see only batch size changes:

```yaml
logging:
  level:
    app.epistola.suite.documents.batch: INFO  # Shows only scaling events
```

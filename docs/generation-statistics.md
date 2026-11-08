# Generation statistics

Per tenant, how many documents were generated and how many failed, and how many previews were
rendered. Generated documents and their jobs are deleted after their retention, so the Generation page
could only count what was still there; these figures are kept.

Code: `modules/epistola-core/.../statistics/` (`GenerationStatistics.kt`, `GenerationStatisticsQueries.kt`).

## How they are counted

- **Documents** are counted from `generation_results` in the background, never on the generation path:
  every `rollup-interval` one node of the cluster reads the results after the position it stopped at
  (the `app_metadata` entry `statistics.generation-position`, locked for each chunk) and adds them to the tenant's hour. A document deleted right after it
  was generated still counts.
- Results younger than `settle` wait for the next round: their sequence numbers may still commit out of
  order, and counting a later one before an earlier one would skip the earlier.
- A round counts at most `max-chunks` × `chunk-size` results, one chunk per transaction, so a backlog
  after downtime is worked off in steps.
- **Previews** are counted in memory on each node and written every `preview-flush-interval`; a node
  that stops between flushes loses at most that interval's previews.
- Its `completeUntil` says up to when the figures hold every result; later ones follow within minutes.

## Retention

Hours (`generation_stats_hourly`) are kept for `hourly-retention`, then folded into days
(`generation_stats_daily`), which are kept indefinitely. There is no foreign key to `tenants`: a
deleted tenant's history stays.

Partition maintenance also records every monthly partition it drops in `partition_history`, with its
month, estimated row count and size, so months can be compared after their rows are gone.

Partition retention (`epistola.partitions.retention-months`) drops old `generation_results` months
whether or not they were counted. The roll-up follows within minutes, so this only loses results
while statistics are off (`epistola.statistics.enabled=false`) or the roll-up is that far behind.

## Reading them

`GetGenerationStatistics` (`DOCUMENT_VIEW`) returns a tenant's figures per month or per day. The
Generation page shows each month, and the last 30 days by day.

## Configuration

| Property                                     | Default | Effect                                                     |
| -------------------------------------------- | ------- | ---------------------------------------------------------- |
| `epistola.statistics.enabled`                | `true`  | Count at all                                               |
| `epistola.statistics.hourly-retention`       | `7d`    | How long hours are kept before folding into days (≥ 1 day) |
| `epistola.statistics.rollup-interval`        | `2m`    | How often one node counts new generation results           |
| `epistola.statistics.settle`                 | `2m`    | Results younger than this wait for the next round          |
| `epistola.statistics.chunk-size`             | `10000` | Results counted per transaction                            |
| `epistola.statistics.max-chunks`             | `20`    | Chunks per round                                           |
| `epistola.statistics.preview-flush-interval` | `1m`    | How often a node writes the previews it counted            |

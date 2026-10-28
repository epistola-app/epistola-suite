# Collect: delivering every result once

How the collect feed (`/api/tenants/{tenantKey}/generation/collect`) hands out generation results
without losing one, and the lock it uses to do so. Measured limits are in
[Collect performance](collect-performance.md), a dated record.

## The feed in brief

Every rendered document emits one row into `generation_results`
(`EmitGenerationResult`). Each row gets a `sequence` from one global Postgres sequence and lands in
one of 64 partitions. A consumer polls (`FetchGenerationResults`): it receives the rows after its
cursor in each partition it owns, ordered by `sequence`, and acknowledges the highest one it
received on its next poll (`AcknowledgeGenerationResults`). The acknowledgement moves its cursors
up to that sequence.

## The problem: numbers are handed out at insert, rows become visible at commit

A row's `sequence` is drawn when it is inserted, but the row only becomes visible when its
transaction commits, and transactions commit in whatever order they finish:

```
emit A:  draws 1000 … still committing (slow disk, GC pause, network to the database)
emit B:  draws 1001 → commits
poll:    sees 1001, delivers it; the consumer acknowledges 1001 → cursors move past 1000
emit A:  commits → 1000 is visible, but below every cursor: never delivered
```

Nothing logs an error: the server considers 1000 delivered. It happens when emits overlap and their
commit times vary, more often with more render workers, more nodes and a busier database. The 2.0
load test lost 13 of 50,000 results this way before the fix, and 1 of 5,000 with batches of 50.

## The fix: a poll delivers only up to where nothing is still in flight

`EmitsInFlight` marks every emit that has not committed yet, and a poll waits for those before it
moves past their numbers.

1. **An emit** takes a **shared**, transaction-scoped advisory lock
   (`pg_advisory_xact_lock_shared`, one fixed key, `EPIS`/1) before it draws its sequence. Postgres
   releases it when the transaction commits or rolls back. The lock protects no data: it only makes
   the open emit visible in `pg_locks`, across every node, since all nodes share the database.
2. **A poll**:
   1. reads the highest sequence handed out so far (`generation_results_sequence_seq`);
   2. reads, from `pg_locks`, which transactions hold the lock at that moment;
   3. waits until those transactions have finished, then delivers rows up to that sequence.

**Why that is watertight:** a sequence at or below the one read in step 1 was drawn before step 1,
by an emit that already held the lock then, so it is one the poll waits for. An emit that starts
after step 1 draws a higher sequence, which the poll does not deliver this time.

### No deadlocks, no waiting emits

- **No deadlock is possible.** Every emit takes the lock in shared mode, and shared holders never
  block each other. Nothing ever takes it exclusively, and the poll takes no lock at all: it only
  reads `pg_locks`. With nobody waiting on the lock, there is no cycle.
- **Emits never wait.** Taking a shared lock that nobody holds exclusively is immediate. A render is
  never slowed by a consumer's poll.
- **A poll's wait is bounded.** It waits only for the emits already in flight, normally a few
  milliseconds. After two seconds (`FetchGenerationResultsHandler.IN_FLIGHT_WAIT`) it returns an
  empty page with `hasMore: true`, and the consumer polls again; nothing is skipped. A rolled-back
  emit leaves a permanent gap in the sequence, which does not stall delivery: its lock is gone, so
  nobody waits for it.
- **A poll with nothing new** stops after reading its cursors and the highest sequence, without
  looking at locks.

### Alternatives considered

- **Gap detection** (deliver only up to the first missing number): sequences have permanent gaps
  (rolled-back emits, cached sequence values), and numbers are global across all 64 partitions while
  a consumer sees only its own, so a gap cannot be told from a number that is still coming.
- **A gap-free counter row**, taken just before commit: its row lock makes emits commit in number
  order, but it serialises the last step of every emit across all nodes, capping throughput.
- **Numbering at insert, closer to the commit**: shrinks the window, never closes it.
- **The transaction id and `pg_snapshot_xmin`**: lock-free, but waits for every open transaction in
  the database, so one long report or migration would hold delivery back.
- **Acknowledging per result**: removes ordering from the problem, but writes once per result and
  changes the collect protocol.

## What to watch

`epistola.collect.inflight.wait` (a timer, tag `outcome`):

- its count is how many polls found emits in flight and waited, and its time how long;
- `outcome=settled`: the emits finished and the poll delivered;
- `outcome=timed_out`: the poll hit the two-second limit and delivered nothing; the next poll
  retries. A steady stream of these means emits stay open for seconds (a stuck render, a database
  under pressure), and is worth investigating.

A timed-out poll is also logged at WARN.

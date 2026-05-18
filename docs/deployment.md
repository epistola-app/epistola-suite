# Deployment

How Epistola Suite is deployed with the Helm chart (`charts/epistola`). For the
migration model itself see [`migrations.md`](migrations.md).

## Database migrations: `migration.mode`

Migrations run as a **separate, explicit step** by default (issue #431). The
chart selects how via `migration.mode`:

| Mode            | What runs                                                                                              | When to use                                                                    |
| --------------- | ------------------------------------------------------------------------------------------------------ | ------------------------------------------------------------------------------ |
| `job` (default) | A `pre-install`/`pre-upgrade` hook Job migrates **once per release** before the Deployment is applied. | Multi-replica / production. Strongest deploy gate.                             |
| `initContainer` | An init container in **every app pod** migrates (idempotent) before the app container starts.          | Simpler clusters; fresh installs of a chart-managed CNPG database (see below). |
| `embedded`      | The app process runs Flyway at boot (no separate step).                                                | Local/dev or single-replica convenience.                                       |

In `job` and `initContainer` modes the chart injects
`EPISTOLA_MIGRATION_MODE=validate` into the app container, so app pods only
**validate** the schema and fail fast if it is behind — they never migrate at
boot. The migration workload reuses the **same image** with
`EPISTOLA_RUN_MODE=migrate` and the same datasource credentials as the app
(shared `epistola.databaseEnv` helper).

`embedded` emits no Job, no init container, and no `EPISTOLA_MIGRATION_MODE` —
the app migrates at boot exactly as before.

## CNPG ordering caveat

With `database.type=cnpg`, the CloudNativePG `Cluster` is a normal (non-hook)
resource Helm applies **after** hooks run. So on a **fresh `helm install`** the
`pre-install` migration Job starts before the database exists. A `wait-for-db`
init container (image: `migration.waitImage`, default `busybox:1.36`) polls the
DB TCP port and tolerates CNPG's asynchronous provisioning on **upgrades** and
for `external` / `cnpgExisting` databases — but on a first install of a
chart-managed CNPG cluster it will time out (`migration.job.activeDeadlineSeconds`).

For the first install of a chart-managed CNPG database, use
`migration.mode=initContainer` (or `embedded`), or pre-create the cluster and use
`database.type=cnpgExisting`. The `wait-for-db` container is skipped entirely for
`database.type=none` (you wire the datasource via `config.env`).

## Failure handling & exit codes

`MigrationLauncher` exits **0** on success, **1** on failure; the app `main()`
forces a non-zero exit on a validate-mode fail-fast. Consequently:

- **`job` mode:** a failed migration fails the Job, which fails
  `helm install`/`helm upgrade` — the new Deployment is never rolled out and old
  pods keep serving the previous version. The failed Job is **retained** for log
  inspection (`helm.sh/hook-delete-policy: before-hook-creation,hook-succeeded`):

  ```bash
  kubectl logs job/<release>-epistola-migrate
  ```

- **`initContainer` mode:** a failed migration fails the init container, so the
  pod never becomes ready (`kubectl logs <pod> -c migrate`). The release still
  reports applied — diagnose via unhealthy pods.

Tuning (`job` mode): `migration.job.backoffLimit`,
`migration.job.activeDeadlineSeconds`, `migration.job.ttlSecondsAfterFinished`,
`migration.resources`.

## Runtime DDL (current limitation)

`PartitionMaintenanceScheduler` still issues raw partition DDL
(`CREATE TABLE … PARTITION OF` / `DROP TABLE`) at runtime, so the runtime DB role
still needs DDL privileges today. Moving that behind `SECURITY DEFINER` functions
and splitting the migrate role from a DDL-less runtime role is tracked in the
follow-up backlog issue (#438) — out of scope for the migration-step separation.

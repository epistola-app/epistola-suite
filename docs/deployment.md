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
`EPISTOLA_MIGRATION_MODE=migrate` and the same datasource credentials as the app
(shared `epistola.databaseEnv` helper).

`embedded` emits no Job, no init container, and no `EPISTOLA_MIGRATION_MODE` —
the app migrates at boot exactly as before.

### GitOps tools (Argo CD / Flux)

`job` mode orders the migration via `helm.sh/hook` annotations, which only fire
when **Helm itself** runs the release — plain `helm upgrade --install` and Flux's
**helm-controller** (a `HelmRelease`) honor them natively, nothing extra needed.

Tools that apply the chart's **rendered manifests** ignore Helm hooks: Argo CD
(it only acts on its own `argocd.argoproj.io/hook` annotations) and Flux's
**kustomize-controller**. There the Job would apply as an ordinary resource and
lose its run-before-the-app ordering. Re-attach the gate with
`migration.job.annotations`, e.g. for Argo CD:

```yaml
migration:
  mode: job
  job:
    annotations:
      argocd.argoproj.io/hook: PreSync
      argocd.argoproj.io/hook-delete-policy: BeforeHookCreation
```

(`BeforeHookCreation` lets re-syncs replace the fixed-name Job.) If your GitOps
tool has no resource-level hook, prefer `initContainer` or `embedded`.

## Usage

### Production, external Postgres (default `job` mode)

`migration.mode=job` is the default, so it can be omitted:

```bash
helm upgrade --install epistola oci://ghcr.io/epistola-app/charts/epistola \
  --set image.tag=v0.20.0 \
  --set config.profiles=prod,keycloak \
  --set database.type=external \
  --set database.external.host=pg.internal \
  --set database.external.existingSecret=epistola-db \
  --set replicaCount=3
```

The hook Job migrates once before the new Deployment rolls out; the 3 app
replicas boot with `EPISTOLA_MIGRATION_MODE=validate` and refuse to start if the
schema is behind. Equivalent `values.yaml`:

```yaml
migration:
  mode: job # default — shown for clarity
database:
  type: external
  external:
    host: pg.internal
    existingSecret: epistola-db
config:
  profiles: prod,keycloak
replicaCount: 3
```

### Fresh install with a chart-managed CNPG cluster

`job` mode is rejected here (see the caveat below) — use `initContainer` for the
first install, then switch back to `job` for subsequent upgrades if desired:

```bash
helm install epistola oci://ghcr.io/epistola-app/charts/epistola \
  --set database.type=cnpg \
  --set migration.mode=initContainer
```

### Run the migration step standalone (outside Helm / CI / one-off)

Same image, `EPISTOLA_MIGRATION_MODE=migrate` (or the `--migrate` arg). Exits
`0` on success, non-zero on failure — usable as a CI/pipeline gate:

```bash
docker run --rm \
  -e EPISTOLA_MIGRATION_MODE=migrate \
  -e SPRING_DATASOURCE_URL=jdbc:postgresql://pg.internal:5432/epistola \
  -e SPRING_DATASOURCE_USERNAME=epistola \
  -e SPRING_DATASOURCE_PASSWORD=… \
  ghcr.io/epistola-app/epistola-suite:v0.20.0
echo "exit: $?"

# or from the built jar:
java -jar epistola.jar --migrate \
  --spring.datasource.url=jdbc:postgresql://localhost:5432/epistola
```

### Local development (embedded — unchanged)

No configuration needed: `epistola.migration.mode` defaults to `embedded`, so
the app migrates at boot as before.

```bash
./gradlew :apps:epistola:bootRun --args='--spring.profiles.active=local'
```

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

`MigrationLauncher` exits **0** on success, **1** on failure. App pods in
`validate` mode fail fast by throwing `SchemaBehindException`, which implements
Spring Boot's `ExitCodeGenerator`; Spring Boot's `SpringBootExceptionHandler`
then `System.exit`s with a non-zero code — there is no `try/catch` or manual
exit in `main()`. (The isolated migration runner excludes Spring Boot's
`LoggingApplicationListener`, so migration-job logs use Logback's default
formatting; Flyway output and exit codes are unaffected.) Consequently:

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

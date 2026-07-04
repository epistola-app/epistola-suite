# Deployment

How Epistola Suite is deployed with the Helm chart (`charts/epistola`). For the
migration model itself see [`migrations.md`](migrations.md).

## Database migrations: `migration.mode`

Migrations run as a **separate, explicit step** by default (issue #431). The
chart selects how via `migration.mode`:

| Mode            | What runs                                                                                              | When to use                                         |
| --------------- | ------------------------------------------------------------------------------------------------------ | --------------------------------------------------- |
| `job` (default) | A `pre-install`/`pre-upgrade` hook Job migrates **once per release** before the Deployment is applied. | Multi-replica / production. Strongest deploy gate.  |
| `initContainer` | An init container in **every app pod** migrates (idempotent) before the app container starts.          | Simpler clusters / GitOps tools without Helm hooks. |
| `embedded`      | The app process runs Flyway at boot (no separate step).                                                | Local/dev or single-replica convenience.            |

In `job` and `initContainer` modes the chart injects
`EPISTOLA_MIGRATION_MODE=validate` into the app container, so app pods only
**validate** the schema and fail fast if it is behind — they never migrate at
boot. The migration workload reuses the **same image** with
`EPISTOLA_MIGRATION_MODE=migrate`, and by default the same datasource credentials
as the app — or a **separate migration role** via `migration.credentials` so the
app's own role can run without DDL (see
[Running with a DDL-less runtime role](#running-with-a-ddl-less-runtime-role)).

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

### Using a CloudNativePG database (`cnpgExisting`)

The chart does **not** provision a database — a database must not share the app
release's lifecycle (a `helm uninstall` would delete it). Own the CNPG `Cluster`
yourself and point the chart at it:

```bash
kubectl apply -f charts/epistola/examples/cnpg-cluster.yaml   # your cluster, your lifecycle
helm upgrade --install epistola oci://ghcr.io/epistola-app/charts/epistola \
  --set database.type=cnpgExisting \
  --set database.cnpgExisting.clusterName=epistola-db
```

CNPG generates the app-credentials Secret `<clusterName>-app`, which the chart
reads. `examples/cnpg-cluster.yaml` is a minimal starting point; extend it (roles,
grants, storage, resources) as your environment needs. This is also how you set
up the [DDL-less two-role](#running-with-a-ddl-less-runtime-role) database.

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

## Waiting for the database

Because the database is provisioned outside this chart, it exists before a
release runs. A `wait-for-db` init container (image: `migration.waitImage`) still
polls the DB TCP port before migrating, so a CNPG cluster (`cnpgExisting`) that is
still finishing asynchronous provisioning, or a brief network blip, is tolerated
rather than failing the release. The `wait-for-db` container is skipped entirely
for `database.type=none` (you wire the datasource via `config.env`).

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

## Running with a DDL-less runtime role

The application performs no schema DDL at runtime **except** monthly partition
maintenance (`PartitionMaintenanceScheduler` creating current/next-month
partitions and dropping ones past retention). That runs through two
`SECURITY DEFINER` functions — `epistola_create_partition(regclass, date)` and
`epistola_drop_partitions_before(regclass, date)` (created by a core migration) —
rather than raw `CREATE TABLE`/`DROP TABLE`. A `SECURITY DEFINER` function
executes with the privileges of its **owner**, so a role that only holds
`EXECUTE` on it can drive the partition DDL without holding DDL privileges
itself.

This makes a **two-role** database setup possible:

- **Migration role** — owns the tables and the partition functions, holds DDL,
  runs Flyway (the separate migration step, `EPISTOLA_MIGRATION_MODE=migrate`).
- **Runtime role** — what the app pods connect as. Needs only DML on the tables
  plus `EXECUTE` on the two functions. **No `CREATE`/`ALTER`/`DROP`.**

Grants an operator applies (the migration role owns the functions; grant the
runtime role execute + DML):

```sql
GRANT EXECUTE ON FUNCTION
  epistola_create_partition(regclass, date),
  epistola_drop_partitions_before(regclass, date)
  TO <runtime_role>;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO <runtime_role>;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO <runtime_role>;
```

The functions are bounded to partition management — each requires its `parent`
to be a RANGE-partitioned table and derives the child name/bounds (create) or
operates only on real child partitions from `pg_inherits` (drop) — so granting
`EXECUTE` does not hand the runtime role a general DDL primitive.

**Single-role deployments are unaffected:** the one role owns the functions and
executes them, so nothing changes.

### Wiring the two roles in the Helm chart

The chart lets the **migration step** connect as a different database role from
the app pods, so the app's role (`database.*`) can be the DDL-less runtime role:

```yaml
migration:
  credentials:
    username: epistola_migrate # the DDL-holding migration role
    existingSecret: epistola-migrate-db
    passwordKey: password
```

When `migration.credentials.username` is set, only the **migration Job /
init container** uses these credentials (same database URL, different
username/password); the app pods keep using `database.*`. When it's empty
(default), the migration step reuses the app credentials — single-role behaviour,
unchanged.

You provision the two roles and their grants (the `GRANT`s above), then reference
them from the chart: the app role via `database.external.*`, the migration role
via `migration.credentials.*`. For a **CNPG-managed** database, create the second
role and its grants through the CNPG `Cluster` you manage (`spec.managed.roles`
plus your bootstrap SQL) — with `database.type=cnpgExisting` the chart just
consumes it. The two-role split is otherwise identical: app pods use their
credentials, the migration step uses `migration.credentials`.

## Trusting a client root CA: `extraCaCerts`

Some environments route the suite's **outbound HTTPS** through endpoints signed
by a private/internal CA — an internal Keycloak, a private template catalog
server, the epistola-hub, or a corporate TLS-inspecting egress proxy. The suite's
outbound clients (catalog fetch, Keycloak Admin API, hub registration) use the
JVM's default truststore, so the pod must be told to trust that CA.

`extraCaCerts` does this without rebuilding the image and without weakening the
hardened pod (non-root, `readOnlyRootFilesystem`). It delivers the certs as a
[Paketo `ca-certificates` service binding](https://github.com/paketo-buildpacks/ca-certificates):
the chart projects a binding directory (a `type` marker file plus your PEM
cert(s)) and sets `SERVICE_BINDING_ROOT`, and the buildpack's launch helper
merges those CAs into the JVM truststore at startup. The public CA bundle is
**preserved** — your CA is added, not substituted — so public-internet TLS keeps
working. There is no init container; everything happens in the app container at
launch.

### Inline PEM (chart renders the Secret)

```yaml
extraCaCerts:
  enabled: true
  certs:
    corp-root-ca.crt: |
      -----BEGIN CERTIFICATE-----
      ...
      -----END CERTIFICATE-----
```

Or supply the file at install time:

```bash
helm upgrade --install epistola charts/epistola \
  --set extraCaCerts.enabled=true \
  --set-file extraCaCerts.certs.corp-root-ca\.crt=/path/to/ca.pem
```

### Pre-existing Secret (you manage the certs)

Reference a Secret whose keys are PEM files (one cert each). The chart renders no
Secret of its own in this mode — it still adds the small `type` ConfigMap that
marks the binding:

```yaml
extraCaCerts:
  enabled: true
  existingSecret: corp-ca-bundle
```

```bash
kubectl create secret generic corp-ca-bundle \
  --from-file=corp-root-ca.crt=/path/to/root-ca.pem \
  --from-file=corp-intermediate.crt=/path/to/intermediate.pem
```

Notes:

- Each Secret/`certs` value must be a **PEM-encoded certificate**, one cert per
  key. The key name is cosmetic (it becomes the cert's filename inside the
  binding). Supply a root + intermediate as separate keys rather than
  concatenating them.
- This covers the app's outbound HTTPS only. Database TLS is configured
  separately via the datasource/JDBC settings.
- At startup the buildpack logs `Added N additional CA certificate(s) to system
truststore` — a quick confirmation your certs were picked up.
- A TLS failure against a CA-signed endpoint shows up as
  `PKIX path building failed` / `unable to find valid certification path` in the
  app log — if you see that, the CA is not (yet) trusted.

## Connection pool (HikariCP)

The app's base pool tuning lives in `application.yaml` and applies to every
deployment without extra configuration: a fixed-size pool, proactive eviction of
dead idle connections (`keepalive-time`/`max-lifetime`), leak detection, and —
critically — a pgjdbc `socketTimeout`/`tcpKeepAlive` so a dead socket (node
failover, network blip, a laptop resuming from sleep in dev) **self-heals**
instead of wedging the pool until a restart. Without `socketTimeout` a thread can
block forever on a half-open socket while holding its pool lease; the pool then
drains to the stuck leases and never recovers.

Two knobs are surfaced as chart values:

- **`datasource.hikari.maximumPoolSize`** (default `20`) — the per-replica pool
  size, pinned to `minimum-idle` (fixed-size pool). Because each replica owns its
  own pool, the cluster total is `replicas × maximumPoolSize`. Ensure Postgres
  `max_connections` comfortably exceeds
  `(maxReplicas × maximumPoolSize) + migration job + reserve`; raise your
  database's `max_connections` (or front Postgres with PgBouncer) before scaling
  `replicaCount`/autoscaling.
- **`datasource.hikari.socketTimeoutSeconds`** (default `30`) — the app's
  per-socket read timeout. Keep it at or above your slowest normal query.

The **migration JVM** shares `application.yaml` but runs long DDL (index builds,
big `ALTER`s) that can read with no socket traffic past the app's 30s timeout, so
the chart exempts it: `migration.socketTimeoutSeconds` (default `0` = no socket
timeout) and leak detection off. Migrations stay bounded by
`migration.job.activeDeadlineSeconds`.

> If a PgBouncer / load balancer with a shorter idle cutoff sits in front of
> Postgres, lower the app's `max-lifetime` below that cutoff (override
> `SPRING_DATASOURCE_HIKARI_MAX_LIFETIME` via `config.env`) so stale connections
> are retired before the proxy drops them.

## Logging

Two chart values set the log level on the app pods (both empty by default, so
the application's built-in defaults apply):

- **`logging.level`** — the root logger, rendered as `LOGGING_LEVEL_ROOT`
  (e.g. `WARN`, `INFO`, `DEBUG`).
- **`logging.appLevel`** — the application's own `app.epistola` packages,
  rendered as `LOGGING_LEVEL_APP_EPISTOLA`. Set this to `DEBUG` to trace the
  app's own behaviour without turning every library verbose.

```yaml
logging:
  level: INFO
  appLevel: DEBUG
```

These apply to the app pods only; the migration step keeps its own minimal
logging. For any **other** logger, set the corresponding `LOGGING_LEVEL_<LOGGER>`
env var via `config.env` — e.g. quiet a noisy library:

```yaml
config:
  env:
    LOGGING_LEVEL_ORG_SPRINGFRAMEWORK_WEB: WARN
```

## Observability

Prometheus scrape, OpenTelemetry agent friendliness (`observability.otelAgent`)
and the full metric catalogue are documented in [metrics.md](metrics.md).

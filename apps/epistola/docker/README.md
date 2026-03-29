# Local Development Services

Docker Compose setup for running Epistola Suite locally.

## Services

| Service       | Port | Purpose                                             |
| ------------- | ---- | --------------------------------------------------- |
| PostgreSQL 17 | 4001 | Application database                                |
| Keycloak 26.5 | 8080 | OAuth2/OIDC (optional, requires `keycloak` profile) |

## Quick Start

```bash
# Start all services (PostgreSQL + Keycloak)
docker compose -f apps/epistola/docker/docker-compose.yaml up -d

# Or start only PostgreSQL (no Keycloak needed for local profile)
docker compose -f apps/epistola/docker/docker-compose.yaml up -d postgres

# Run the app
./gradlew :apps:epistola:bootRun --args='--spring.profiles.active=local'
```

PostgreSQL data is stored in tmpfs (RAM) — it resets on container restart. Flyway migrations and the DemoLoader will set up schema and sample data automatically on each app start.

## With Keycloak (OAuth2)

```bash
# Start services, then run with both profiles
./gradlew :apps:epistola:bootRun --args='--spring.profiles.active=local,keycloak'
```

### Test Users

| Email      | Password | Role  |
| ---------- | -------- | ----- |
| admin@test | admin    | Admin |
| user@test  | user     | User  |

### Admin Console

- URL: http://localhost:8080/admin
- Username: `admin`
- Password: `admin`

### Realm Configuration

The realm is pre-configured with:

- Realm: `epistola`
- Client: `epistola-suite`
- Client Secret: `epistola-dev-secret`
- Redirect URI: `http://localhost:4000/*`

## Stop / Reset

```bash
# Stop all services
docker compose -f apps/epistola/docker/docker-compose.yaml down

# Stop and remove volumes (full reset)
docker compose -f apps/epistola/docker/docker-compose.yaml down -v
```

# Keycloak Development Setup

This directory contains a pre-configured Keycloak setup for testing OAuth2/OIDC authentication locally.

## Quick Start

```bash
# Start Keycloak (from project root)
docker compose -f apps/epistola/docker/keycloak/docker-compose.yaml up -d

# Wait for Keycloak to be ready (check http://localhost:8080)

# Run the app with local + keycloak profiles (both form login and OAuth2)
./gradlew :apps:epistola:bootRun --args='--spring.profiles.active=local,keycloak'

# Or OAuth2 only (no form login)
./gradlew :apps:epistola:bootRun --args='--spring.profiles.active=keycloak'
```

## Test Users

| Email | Password | Role |
|-------|----------|------|
| admin@test | admin | Admin |
| user@test | user | User |

## Keycloak Admin Console

- URL: http://localhost:8080/admin
- Username: `admin`
- Password: `admin`

## Configuration

The realm is pre-configured with:
- Realm: `epistola`
- Client: `epistola-suite`
- Client Secret: `epistola-dev-secret`
- Redirect URI: `http://localhost:4000/*`

## Stop Keycloak

```bash
docker compose -f apps/epistola/docker/keycloak/docker-compose.yaml down
```

## Reset Keycloak

To reset to initial state:

```bash
docker compose -f apps/epistola/docker/keycloak/docker-compose.yaml down -v
docker compose -f apps/epistola/docker/keycloak/docker-compose.yaml up -d
```

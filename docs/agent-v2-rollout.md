# Agent v2 rollout: Java and database procedure

This page complements the [Python and evaluation runbook](../../Mu-mirror-AI/docs/agent-v2-rollout.md). It documents the existing backend release path; it does not authorize production deployment or database changes.

## Database preparation

The SQL lives in this repository:

- `src/main/resources/db/migration-v3-record-ai-authorization.sql` adds the record authorization boundary used by the views.
- `src/main/resources/db/migration-v4-agent-v2.sql` creates the `mirror_ai` security-barrier views and Java run/command-receipt tables.
- `deploy/agent-reader-role.sql` hardens a pre-provisioned reader role and grants SELECT on the eight named views only.

Before the first migration, use an approved test database and take a restorable backup. Example commands from this checkout:

```bash
pg_dump "$MIRROR_DB_ADMIN_DSN" --format=custom --file="$BACKUP_PATH"
psql "$MIRROR_DB_ADMIN_DSN" -v ON_ERROR_STOP=1 -f src/main/resources/db/migration-v3-record-ai-authorization.sql
psql "$MIRROR_DB_ADMIN_DSN" -v ON_ERROR_STOP=1 -f src/main/resources/db/migration-v4-agent-v2.sql
```

Provision a dedicated login role and its password with the secret manager, then run:

```bash
psql "$MIRROR_DB_ADMIN_DSN" -v ON_ERROR_STOP=1 -v reader_role=mirror_ai_reader -f deploy/agent-reader-role.sql
```

The role script revokes inherited memberships and broad schema/table/sequence grants for that role. Use a dedicated account. Verify the app's actual PostgreSQL major version in a test environment, view definitions and grants, the no-DML boundary, and successful/denied reads before release. The local Task9 write E2E used PostgreSQL 16.15 but did not test this view migration. The destructive fixture at the Python test path is synthetic-only and must never be applied to an application database.

The existing `deploy/backend-release.sh` performs a database dump before its selected migration and can be given `MIGRATION=src/main/resources/db/migration-v4-agent-v2.sql`. Its default migration is still `migration-v2.sql`; explicitly select v3/v4 as needed. If v3 is not already installed, apply it first using the backup procedure above. The release script rolls back the application symlink on health failure; it deliberately does not roll back schema or user data.

## Engine configuration and order

`application.yml` defaults to `chat.engine=legacy`, empty graph user IDs, `shadow-sample-rate=0`, and create-record disabled. Keep these defaults while deploying compatible Java/Python proto versions and the frontend. Inject `MIRROR_AGENT_INTERNAL_TOKEN`, `MIRROR_AGENT_COMMAND_HMAC_KEY`, and the Python read-only DSN through existing secret management.

For a test user only, set `MIRROR_CHAT_ENGINE=graph_v2` and `MIRROR_GRAPH_ENABLED_USER_IDS=<approved-user-uuid>`. For shadow observation, set `MIRROR_CHAT_ENGINE=shadow` and a deliberate `MIRROR_SHADOW_SAMPLE_RATE`; legacy remains the user-facing engine. The Java scheduler samples at request intake, excludes explicit create requests, runs at most one shadow at a time without a waiting queue, and cancels it when a foreground graph request needs capacity. Before graph_v2 traffic, turn shadow sampling back to zero. There is no automatic engine retry within a turn.

During staged validation, verify the separate 10003/10004 checks, a read-only `mirror_ai` SELECT, unauthenticated Agent gRPC rejection, and HTTP 401 from the command endpoint when no grant is sent. Keep Python Agent disabled if DSN/token is absent; the legacy server must continue to start and serve old RPCs.

## Rollback and cleanup gate

Rollback by setting `MIRROR_CHAT_ENGINE=legacy`, setting shadow sampling to zero, and restarting via `deploy/backend-release.sh`. This affects future request selection only. Do not revert written records, drop run/receipt tables, or remove views during code rollback.

Do not set graph_v2 as default until permissions and write idempotency pass, unexplained core regressions are resolved, paired answer scoring does not decline, and adequate latency samples satisfy the agreed limit. Task10 has not performed a small-traffic rollout or collected those measurements. Keep old chat code for one stable release cycle, search all RPC/mapper callers, then propose cleanup separately. Classification/profile/ordinary-page callers keep their shared RPCs and mappers.

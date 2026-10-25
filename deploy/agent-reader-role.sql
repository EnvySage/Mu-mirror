-- Grant the Python service account access to the fixed mirror_ai views only.
-- Run as a database administrator after migration-v11-agent-v2.sql.
-- The role must already exist; inject its password through deployment secret
-- management, never through this file. Example:
--   psql "$ADMIN_DSN" -v reader_role=mirror_ai_reader -f agent-reader-role.sql

\set ON_ERROR_STOP on

\if :{?reader_role}
\else
  \echo 'Required psql variable: reader_role'
  \quit 3
\endif

SELECT (:'reader_role' ~ '^[a-z_][a-z0-9_]{0,62}$') AS reader_role_valid \gset
\if :reader_role_valid
\else
  \echo 'reader_role must be a simple lowercase PostgreSQL role name'
  \quit 3
\endif

SELECT EXISTS (
    SELECT 1 FROM pg_roles WHERE rolname = :'reader_role'
) AS reader_role_exists \gset
\if :reader_role_exists
\else
  \echo 'reader_role must be provisioned separately with an injected password'
  \quit 3
\endif

ALTER ROLE :"reader_role"
    LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS;

-- Remove memberships that could otherwise grant inherited or SET ROLE access.
SELECT format('REVOKE %I FROM %I', granted.rolname, :'reader_role')
FROM pg_auth_members AS membership
JOIN pg_roles AS granted ON granted.oid = membership.roleid
JOIN pg_roles AS member ON member.oid = membership.member
WHERE member.rolname = :'reader_role'
\gexec

SELECT current_database() AS reader_database \gset
ALTER ROLE :"reader_role" IN DATABASE :"reader_database"
    SET search_path TO pg_catalog, mirror_ai;

-- The existing pgvector extension is installed in public by schema.sql.
-- Vault vector reads use its qualified type/operator; this script grants no
-- direct public-schema or base-table privileges. It relies on the database's
-- standard PUBLIC USAGE on public, which does not grant table access.

GRANT CONNECT ON DATABASE :"reader_database" TO :"reader_role";
REVOKE CREATE, TEMPORARY ON DATABASE :"reader_database" FROM :"reader_role";

-- Remove any previous direct table/schema rights held by this account.
REVOKE ALL PRIVILEGES ON SCHEMA public FROM :"reader_role";
REVOKE ALL PRIVILEGES ON ALL TABLES IN SCHEMA public FROM :"reader_role";
REVOKE ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public FROM :"reader_role";
REVOKE ALL PRIVILEGES ON SCHEMA mirror_ai FROM :"reader_role";
REVOKE ALL PRIVILEGES ON ALL TABLES IN SCHEMA mirror_ai FROM :"reader_role";
REVOKE ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA mirror_ai FROM :"reader_role";

GRANT USAGE ON SCHEMA mirror_ai TO :"reader_role";
GRANT SELECT ON
    mirror_ai.record_chunks,
    mirror_ai.glossary_terms,
    mirror_ai.chat_history,
    mirror_ai.profiles,
    mirror_ai.vault_metadata,
    mirror_ai.vault_content,
    mirror_ai.vault_keys,
    mirror_ai.todos
TO :"reader_role";

SELECT
    current_database() AS database_name,
    :'reader_role' AS reader_role,
    count(*) AS granted_view_count
FROM information_schema.role_table_grants
WHERE grantee = :'reader_role'
  AND table_schema = 'mirror_ai'
  AND privilege_type = 'SELECT';

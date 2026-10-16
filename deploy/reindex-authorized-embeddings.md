# Rebuild authorized embeddings after a model change

Use the offline command runner in `EmbeddingReindexCommandLineRunner` when changing a production user's embedding model. It rebuilds vectors only for confirmed AI memory chunks and confirmed Vault items, then changes the model setting in the same database transaction.

## Before running

1. Take a database backup.
2. Keep PostgreSQL and the Python AI gRPC service available.
3. Stop the Java API service and any other process that can write this user's records, Vault items, or settings. The command checks an explicit offline confirmation and uses table locks during the final commit.
4. Make sure the user's configured Embedding API key, endpoint, and target model are available and that the target returns 1024-dimensional vectors.
5. Run the command separately for each user. The database schema does not need a migration.

The command uses the existing encrypted API key from `user_settings`; the production `MUMIRROR_AES_KEY` must match the key used by the application.

## Dry run

Run the packaged backend with the production datasource and AI gRPC settings, replacing the jar path and model names:

```bash
java -jar Mu-mirror-B.jar \
  --spring.main.web-application-type=none \
  --mirror.scheduling.enabled=false \
  --mirror.embedding.reindex.enabled=true \
  --mirror.embedding.reindex.user-id=<USER_UUID> \
  --mirror.embedding.reindex.expected-model=<CURRENT_MODEL> \
  --mirror.embedding.reindex.target-model=<TARGET_MODEL>
```

Dry run reports only the number of eligible diary and Vault chunks. It makes no API calls and writes nothing.

## Apply

Run this full command after reviewing the dry-run counts:

```bash
java -jar Mu-mirror-B.jar \
  --spring.main.web-application-type=none \
  --mirror.scheduling.enabled=false \
  --mirror.embedding.reindex.enabled=true \
  --mirror.embedding.reindex.user-id=<USER_UUID> \
  --mirror.embedding.reindex.expected-model=<CURRENT_MODEL> \
  --mirror.embedding.reindex.target-model=<TARGET_MODEL> \
  --mirror.embedding.reindex.apply=true \
  --mirror.embedding.reindex.offline-confirmed=true
```

The command probes the target model, creates all replacement vectors in memory, then opens one transaction. If the model probe or any embedding call fails, or if an authorized source changes while vectors are being generated, the database remains unchanged. At commit it replaces eligible vectors, clears vectors from unapproved or inactive chunks, invalidates old profile snapshots, and switches `user_settings.embedding_model`.

Only diary chunks with `ai_memory_approved = TRUE` from active, completed user records are embedded. Only chunks belonging to Vault items with `digest_status = 'confirmed'` are embedded. ToDo authorization is not changed. Existing profile snapshots are invalidated because their provenance may include legacy diary content; generate a new profile after the user has approved usable memories.

## Rollback

To return to the previous model, rerun the same command with `expected-model` set to the current model and `target-model` set to the previous model. This regenerates authorized vectors in the old embedding space and updates the setting atomically. Invalidated profile snapshots stay invalidated and can be regenerated from currently authorized data.

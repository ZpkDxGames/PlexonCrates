package com.antondev.crates.database;

import com.antondev.crates.domain.draft.DefinitionDraft;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Canonical SQLite boundary for published definition graphs. */
public final class DefinitionRepository {
    private final DatabaseService database;

    public DefinitionRepository(DatabaseService database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    public CompletableFuture<DatabaseService.PublishedSnapshot> bootstrap(
            List<DatabaseService.DefinitionBundle> definitions) {
        return database.bootstrapPublishedDefinitions(definitions);
    }

    public CompletableFuture<DatabaseService.PublishedSnapshot> loadPublished() {
        return database.loadPublishedDefinitions();
    }

    public CompletableFuture<List<DefinitionDraft>> loadDrafts() {
        return database.loadDefinitionDrafts();
    }

    public CompletableFuture<List<DatabaseService.StoredKeyDefinition>> loadKeys() {
        return database.loadDefinitionKeys();
    }

    public CompletableFuture<DatabaseService.PublishResult> publish(DatabaseService.PublishRequest request) {
        return database.publishDefinitionDraft(request);
    }

    public CompletableFuture<DatabaseService.DeleteResult> delete(String crateId, UUID actorId, String actorName) {
        return database.deleteDefinition(crateId, actorId, actorName);
    }

    public CompletableFuture<DatabaseService.DefinitionCounts> counts(String crateId) {
        return database.definitionCounts(crateId);
    }
}

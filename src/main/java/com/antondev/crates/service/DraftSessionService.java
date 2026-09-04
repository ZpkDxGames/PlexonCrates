package com.antondev.crates.service;

import com.antondev.crates.database.DatabaseService;
import com.antondev.crates.domain.draft.DefinitionDraft;
import com.antondev.crates.domain.draft.DraftMutation;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Coordinates one ordered, revision-checked save stream per administrator and crate.
 * The durable lease itself remains authoritative in SQLite.
 */
public final class DraftSessionService {
    public enum State {
        LOADING,
        SAVING,
        SAVED,
        SAVE_FAILED,
        READ_ONLY
    }

    public record View(
            String crateId,
            State state,
            UUID draftId,
            String ownerName,
            long revision,
            long leaseToken,
            String failure,
            boolean writable) {

        public View {
            crateId = required(crateId, "crateId");
            state = Objects.requireNonNull(state, "state");
            ownerName = ownerName == null ? "" : ownerName;
            failure = failure == null ? "" : failure;
            if (revision < 0 || leaseToken < 0) {
                throw new IllegalArgumentException("Draft revision and lease token cannot be negative");
            }
        }
    }

    @FunctionalInterface
    public interface StateListener {
        void changed(UUID actorId, String crateId, View view);
    }

    private record SessionKey(UUID actorId, String crateId) {
        private SessionKey {
            Objects.requireNonNull(actorId, "actorId");
            crateId = required(crateId, "crateId").toLowerCase(Locale.ROOT);
        }
    }

    private static final class Session {
        private final SessionKey key;
        private final String actorName;
        private final CompletableFuture<DefinitionDraft> ready = new CompletableFuture<>();
        private DefinitionDraft draft;
        private CompletableFuture<DefinitionDraft> tail;
        private State localState = State.LOADING;
        private String failure = "";
        private long operation;
        private volatile long touchedAt = System.currentTimeMillis();

        private Session(SessionKey key, String actorName) {
            this.key = key;
            this.actorName = required(actorName, "actorName");
        }
    }

    private final DatabaseService database;
    private final StateListener listener;
    private final ConcurrentHashMap<SessionKey, Session> sessions = new ConcurrentHashMap<>();

    public DraftSessionService(DatabaseService database, StateListener listener) {
        this.database = Objects.requireNonNull(database, "database");
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    public CompletableFuture<View> openCrate(
            UUID actorId, String actorName, String crateId, long baseRevision, byte[] initialPayload) {
        SessionKey key = new SessionKey(actorId, crateId);
        Session created = new Session(key, actorName);
        Session session = sessions.putIfAbsent(key, created);
        if (session == null) {
            session = created;
            beginLoad(session, baseRevision, initialPayload);
        }
        session.touchedAt = System.currentTimeMillis();
        Session current = session;
        return current.ready.thenApply(ignored -> view(current));
    }

    public Optional<View> view(UUID actorId, String crateId) {
        Session session = sessions.get(new SessionKey(actorId, crateId));
        if (session == null) return Optional.empty();
        session.touchedAt = System.currentTimeMillis();
        return Optional.of(view(session));
    }

    public boolean writable(UUID actorId, String crateId) {
        return view(actorId, crateId).map(View::writable).orElse(false);
    }

    public Optional<byte[]> payload(UUID actorId, String crateId) {
        Session session = sessions.get(new SessionKey(actorId, crateId));
        if (session == null) return Optional.empty();
        synchronized (session) {
            return session.draft == null ? Optional.empty() : Optional.of(session.draft.payload());
        }
    }

    public CompletableFuture<View> saveCrate(
            UUID actorId, String crateId, String actionType, String summary, byte[] payload) {
        Session session = requireSession(actorId, crateId);
        byte[] snapshot = Objects.requireNonNull(payload, "payload").clone();
        String action = required(actionType, "actionType");
        String detail = required(summary, "summary");
        return queueSave(session, action, detail, snapshot);
    }

    public CompletableFuture<View> retryCrate(UUID actorId, String crateId, byte[] payload) {
        Session session = requireSession(actorId, crateId);
        synchronized (session) {
            if (session.draft == null && session.localState == State.SAVE_FAILED) {
                sessions.remove(session.key, session);
                return openCrate(actorId, session.actorName, crateId, 0,
                        Objects.requireNonNull(payload, "payload").clone());
            }
            if (session.draft == null || !session.draft.ownerId().equals(actorId)) {
                return CompletableFuture.failedFuture(new DraftAccessException(readOnlyMessage(session)));
            }
            if (session.localState != State.SAVE_FAILED) {
                return CompletableFuture.failedFuture(new IllegalStateException("This draft does not have a failed save to retry"));
            }
            session.tail = CompletableFuture.completedFuture(session.draft);
            session.localState = State.SAVED;
            session.failure = "";
        }
        return queueSave(session, "RETRY", "Retried the latest failed draft snapshot",
                Objects.requireNonNull(payload, "payload").clone());
    }

    public CompletableFuture<View> takeoverCrate(UUID actorId, String crateId) {
        Session session = requireSession(actorId, crateId);
        DefinitionDraft current;
        synchronized (session) {
            current = session.draft;
            if (current == null) {
                return CompletableFuture.failedFuture(new IllegalStateException("The draft is still loading"));
            }
            if (current.ownerId().equals(actorId)) {
                return CompletableFuture.completedFuture(viewLocked(session));
            }
        }
        var result = new CompletableFuture<View>();
        database.takeoverDefinitionDraft(current.draftId(), current.leaseToken(), actorId, session.actorName)
                .whenComplete((taken, error) -> {
                    if (error != null) {
                        result.completeExceptionally(unwrap(error));
                        return;
                    }
                    spread(taken);
                    Session ownerSession = sessions.get(session.key);
                    if (ownerSession != null) {
                        synchronized (ownerSession) {
                            ownerSession.localState = State.SAVED;
                            ownerSession.failure = "";
                            ownerSession.tail = CompletableFuture.completedFuture(taken);
                        }
                        View next = view(ownerSession);
                        publish(ownerSession, next);
                        result.complete(next);
                    } else {
                        result.complete(new View(crateId, State.SAVED, taken.draftId(), taken.ownerName(),
                                taken.revision(), taken.leaseToken(), "", true));
                    }
                });
        return result;
    }

    public CompletableFuture<View> undoCrate(UUID actorId, String crateId) {
        Session session = requireSession(actorId, crateId);
        DefinitionDraft current;
        long operation;
        synchronized (session) {
            View view = viewLocked(session);
            if (!view.writable()) {
                return CompletableFuture.failedFuture(new DraftAccessException(accessMessage(view)));
            }
            if (session.tail == null || !session.tail.isDone()) {
                return CompletableFuture.failedFuture(new IllegalStateException("Wait for the current draft save before undoing"));
            }
            current = session.draft;
            operation = ++session.operation;
            session.localState = State.SAVING;
            session.failure = "";
        }
        publish(session, view(session));
        var result = new CompletableFuture<View>();
        CompletableFuture<DefinitionDraft> undo = database.undoDefinitionDraft(current.draftId(), current.revision(),
                current.leaseToken(), actorId, Instant.now()).thenApply(restored -> {
                    spread(restored);
                    return restored;
                });
        synchronized (session) {
            session.tail = undo;
        }
        undo.whenComplete((restored, error) -> finish(session, operation, restored, error, false, result));
        return result;
    }

    public CompletableFuture<Void> discardCrate(UUID actorId, String crateId) {
        Session session = requireSession(actorId, crateId);
        DefinitionDraft current;
        synchronized (session) {
            View view = viewLocked(session);
            if (!view.writable()) {
                return CompletableFuture.failedFuture(new DraftAccessException(accessMessage(view)));
            }
            if (session.tail == null || !session.tail.isDone()) {
                return CompletableFuture.failedFuture(new IllegalStateException(
                        "Wait for the current draft save before discarding"));
            }
            current = session.draft;
            session.localState = State.SAVING;
        }
        publish(session, view(session));
        var result = new CompletableFuture<Void>();
        database.discardDefinitionDraft(current.draftId(), current.revision(), current.leaseToken(), actorId,
                        session.actorName)
                .whenComplete((ignored, error) -> {
                    if (error == null) {
                        sessions.keySet().removeIf(key -> key.crateId().equals(session.key.crateId()));
                        result.complete(null);
                        return;
                    }
                    synchronized (session) {
                        session.localState = State.SAVED;
                    }
                    publish(session, view(session));
                    result.completeExceptionally(unwrap(error));
                });
        return result;
    }

    public void forget(UUID actorId) {
        sessions.keySet().removeIf(key -> key.actorId().equals(actorId));
    }

    public void expireOlderThan(long cutoffEpochMillis) {
        sessions.entrySet().removeIf(entry -> entry.getValue().touchedAt < cutoffEpochMillis
                && (entry.getValue().tail == null || entry.getValue().tail.isDone()));
    }

    public int activeSessions() {
        return sessions.size();
    }

    public CompletableFuture<Void> awaitIdle() {
        List<CompletableFuture<?>> futures = new ArrayList<>();
        for (Session session : sessions.values()) {
            synchronized (session) {
                futures.add(session.tail == null ? session.ready : session.tail);
            }
        }
        return CompletableFuture.allOf(futures.toArray(CompletableFuture<?>[]::new));
    }

    public void clear() {
        sessions.clear();
    }

    private void beginLoad(Session session, long baseRevision, byte[] initialPayload) {
        byte[] payload = Objects.requireNonNull(initialPayload, "initialPayload").clone();
        database.createOrResumeDefinitionDraft("CRATE", session.key.crateId(), session.key.actorId(),
                        session.actorName, baseRevision, payload)
                .whenComplete((draft, error) -> {
                    if (error != null) {
                        synchronized (session) {
                            session.localState = State.SAVE_FAILED;
                            session.failure = concise(error);
                        }
                        session.ready.completeExceptionally(unwrap(error));
                        publish(session, view(session));
                        return;
                    }
                    synchronized (session) {
                        session.draft = draft;
                        session.tail = CompletableFuture.completedFuture(draft);
                        session.localState = State.SAVED;
                        session.failure = "";
                    }
                    session.ready.complete(draft);
                    publish(session, view(session));
                });
    }

    private CompletableFuture<View> queueSave(
            Session session, String actionType, String summary, byte[] payload) {
        long operation;
        CompletableFuture<DefinitionDraft> previous;
        synchronized (session) {
            View currentView = viewLocked(session);
            if (!currentView.writable()) {
                return CompletableFuture.failedFuture(new DraftAccessException(accessMessage(currentView)));
            }
            operation = ++session.operation;
            session.localState = State.SAVING;
            session.failure = "";
            previous = session.tail;
        }
        publish(session, view(session));

        CompletableFuture<DefinitionDraft> next = previous.thenCompose(ignored -> {
            DefinitionDraft current;
            synchronized (session) {
                current = session.draft;
                if (current == null || !current.ownerId().equals(session.key.actorId())) {
                    return CompletableFuture.failedFuture(new DraftAccessException(readOnlyMessage(session)));
                }
            }
            DraftMutation mutation = new DraftMutation(current.revision(), current.leaseToken(),
                    session.key.actorId(), actionType, summary, payload, "UNVALIDATED", Instant.now());
            return database.saveDefinitionDraft(current.draftId(), mutation);
        }).thenApply(saved -> {
            // Update the session revision inside the ordered chain. A following save must
            // construct its compare-and-update mutation from this new revision.
            spread(saved);
            return saved;
        });
        synchronized (session) {
            session.tail = next;
        }
        var result = new CompletableFuture<View>();
        next.whenComplete((saved, error) -> finish(session, operation, saved, error, true, result));
        return result;
    }

    private void finish(Session session, long operation, DefinitionDraft saved, Throwable error,
                        boolean blocksWrites, CompletableFuture<View> result) {
        View next;
        synchronized (session) {
            if (operation == session.operation) {
                if (error == null) {
                    session.localState = State.SAVED;
                    session.failure = "";
                } else if (!blocksWrites) {
                    session.localState = State.SAVED;
                    session.failure = "";
                    session.tail = CompletableFuture.completedFuture(session.draft);
                } else {
                    session.localState = State.SAVE_FAILED;
                    session.failure = concise(error);
                }
            }
            next = viewLocked(session);
        }
        publish(session, next);
        if (error == null) result.complete(next);
        else result.completeExceptionally(unwrap(error));
    }

    private void spread(DefinitionDraft draft) {
        for (Session candidate : sessions.values()) {
            if (!candidate.key.crateId().equals(draft.targetId())) continue;
            synchronized (candidate) {
                candidate.draft = draft;
                if (!draft.ownerId().equals(candidate.key.actorId())) {
                    candidate.failure = "";
                }
            }
            publish(candidate, view(candidate));
        }
    }

    private Session requireSession(UUID actorId, String crateId) {
        Session session = sessions.get(new SessionKey(actorId, crateId));
        if (session == null) throw new IllegalStateException("Open this crate editor before changing its draft");
        session.touchedAt = System.currentTimeMillis();
        return session;
    }

    private View view(Session session) {
        synchronized (session) {
            return viewLocked(session);
        }
    }

    private static View viewLocked(Session session) {
        DefinitionDraft draft = session.draft;
        if (draft == null) {
            return new View(session.key.crateId(), session.localState, null, "", 0, 0,
                    session.failure, false);
        }
        boolean owner = draft.ownerId().equals(session.key.actorId());
        State state = owner ? session.localState : State.READ_ONLY;
        boolean writable = owner && (state == State.SAVED || state == State.SAVING);
        return new View(session.key.crateId(), state, draft.draftId(), draft.ownerName(), draft.revision(),
                draft.leaseToken(), session.failure, writable);
    }

    private void publish(Session session, View view) {
        listener.changed(session.key.actorId(), session.key.crateId(), view);
    }

    private static String accessMessage(View view) {
        return switch (view.state()) {
            case LOADING -> "The draft is still loading";
            case SAVE_FAILED -> "The latest draft save failed; retry it before making more changes";
            case READ_ONLY -> "This draft is currently edited by "
                    + (view.ownerName().isBlank() ? "another administrator" : view.ownerName());
            case SAVING, SAVED -> "This draft is not writable";
        };
    }

    private static String readOnlyMessage(Session session) {
        return session.draft == null ? "The draft is still loading"
                : "This draft is currently edited by " + session.draft.ownerName();
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException) && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static String concise(Throwable error) {
        Throwable current = unwrap(error);
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private static String required(String value, String name) {
        String text = Objects.requireNonNull(value, name).trim();
        if (text.isEmpty()) throw new IllegalArgumentException(name + " cannot be blank");
        return text;
    }

    public static final class DraftAccessException extends IllegalStateException {
        @java.io.Serial
        private static final long serialVersionUID = 1L;

        public DraftAccessException(String message) {
            super(message);
        }
    }
}

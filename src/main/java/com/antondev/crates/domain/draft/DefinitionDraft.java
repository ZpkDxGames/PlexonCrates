package com.antondev.crates.domain.draft;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record DefinitionDraft(
        UUID draftId,
        String targetType,
        String targetId,
        UUID ownerId,
        String ownerName,
        long baseRevision,
        long revision,
        long leaseToken,
        DraftSaveState saveState,
        String validationStatus,
        byte[] payload,
        Instant createdAt,
        Instant updatedAt) {

    public DefinitionDraft {
        draftId = Objects.requireNonNull(draftId, "draftId");
        targetType = requireText(targetType, "targetType");
        targetId = requireText(targetId, "targetId");
        ownerId = Objects.requireNonNull(ownerId, "ownerId");
        ownerName = requireText(ownerName, "ownerName");
        saveState = Objects.requireNonNull(saveState, "saveState");
        validationStatus = Objects.requireNonNull(validationStatus, "validationStatus");
        payload = Objects.requireNonNull(payload, "payload").clone();
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        if (baseRevision < 0 || revision < 0 || leaseToken < 0) {
            throw new IllegalArgumentException("Draft revisions and lease tokens cannot be negative");
        }
    }

    @Override
    public byte[] payload() {
        return payload.clone();
    }

    public boolean writableBy(UUID actorId, long presentedLeaseToken) {
        return ownerId.equals(actorId) && leaseToken == presentedLeaseToken;
    }

    private static String requireText(String value, String name) {
        String text = Objects.requireNonNull(value, name).trim();
        if (text.isEmpty()) throw new IllegalArgumentException(name + " cannot be blank");
        return text;
    }
}

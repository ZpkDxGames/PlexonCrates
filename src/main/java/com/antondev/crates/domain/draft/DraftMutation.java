package com.antondev.crates.domain.draft;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record DraftMutation(
        long expectedRevision,
        long leaseToken,
        UUID actorId,
        String actionType,
        String summary,
        byte[] payload,
        String validationStatus,
        Instant createdAt) {

    public DraftMutation {
        actorId = Objects.requireNonNull(actorId, "actorId");
        actionType = requireText(actionType, "actionType");
        summary = requireText(summary, "summary");
        payload = Objects.requireNonNull(payload, "payload").clone();
        validationStatus = Objects.requireNonNull(validationStatus, "validationStatus");
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        if (expectedRevision < 0 || leaseToken < 0) {
            throw new IllegalArgumentException("Expected revision and lease token cannot be negative");
        }
    }

    @Override
    public byte[] payload() {
        return payload.clone();
    }

    private static String requireText(String value, String name) {
        String text = Objects.requireNonNull(value, name).trim();
        if (text.isEmpty()) throw new IllegalArgumentException(name + " cannot be blank");
        return text;
    }
}

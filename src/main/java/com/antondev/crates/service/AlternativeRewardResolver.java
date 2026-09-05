package com.antondev.crates.service;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Validates and resolves the one-edge alternative reward contract. */
public final class AlternativeRewardResolver<T> {
    public enum Reason {
        PLAYER_LIMIT, GLOBAL_LIMIT, PERMISSION, COOLDOWN, DATE_WINDOW,
        DECODE_FAILURE, MISSING_INTEGRATION, INVALID_COMMAND, CAPACITY_FAILURE, TRANSACTION_FAILURE
    }

    public record Node<T>(String rewardId, T value, String fallbackId, Set<Reason> fallbackReasons) {
        public Node {
            rewardId = normalizeId(rewardId);
            value = Objects.requireNonNull(value, "value");
            fallbackId = fallbackId == null || fallbackId.isBlank() ? null : normalizeId(fallbackId);
            var reasons = EnumSet.noneOf(Reason.class);
            if (fallbackReasons != null) reasons.addAll(fallbackReasons);
            fallbackReasons = Set.copyOf(reasons);
        }
    }

    public record Resolution<T>(String sourceId, String selectedId, T value, Reason reason, boolean fallback) {
        public Resolution {
            sourceId = normalizeId(sourceId);
            selectedId = normalizeId(selectedId);
            value = Objects.requireNonNull(value, "value");
            reason = reason == null ? null : reason;
            if (!fallback && !sourceId.equals(selectedId)) {
                throw new IllegalArgumentException("A direct resolution must retain its source ID");
            }
        }
    }

    public static <T> List<String> validate(Map<String, Node<T>> nodes) {
        if (nodes == null) return List.of("ALTERNATIVES_EMPTY");
        var errors = new ArrayList<String>();
        var normalized = new LinkedHashMap<String, Node<T>>();
        for (Map.Entry<String, Node<T>> entry : nodes.entrySet()) {
            if (entry.getValue() == null) {
                errors.add("ALTERNATIVE_NULL:" + entry.getKey());
                continue;
            }
            if (!entry.getKey().equalsIgnoreCase(entry.getValue().rewardId())) {
                errors.add("ALTERNATIVE_ID_MISMATCH:" + entry.getKey());
            }
            normalized.put(entry.getValue().rewardId(), entry.getValue());
            if (entry.getValue().fallbackId() != null && !entry.getValue().fallbackReasons().stream()
                    .allMatch(AlternativeRewardResolver::fallbackReasonAllowed)) {
                errors.add("ALTERNATIVE_UNSAFE_REASON:" + entry.getValue().rewardId());
            }
        }
        for (Node<T> node : normalized.values()) {
            if (node.fallbackId() == null) continue;
            Node<T> target = normalized.get(node.fallbackId());
            if (target == null) {
                errors.add("ALTERNATIVE_MISSING:" + node.rewardId() + "->" + node.fallbackId());
            } else if (target.fallbackId() != null) {
                errors.add("ALTERNATIVE_DEPTH:" + node.rewardId());
            }
        }
        return List.copyOf(errors);
    }

    public static <T> Optional<Resolution<T>> resolve(
            Map<String, Node<T>> nodes, String rewardId, Reason reason) {
        Objects.requireNonNull(nodes, "nodes");
        String id = normalizeId(rewardId);
        Node<T> source = nodes.get(id);
        if (source == null) return Optional.empty();
        if (reason != null && source.fallbackId() != null && source.fallbackReasons().contains(reason)) {
            Node<T> fallback = nodes.get(source.fallbackId());
            if (fallback != null) {
                return Optional.of(new Resolution<>(source.rewardId(), fallback.rewardId(),
                        fallback.value(), reason, true));
            }
        }
        return Optional.of(new Resolution<>(source.rewardId(), source.rewardId(), source.value(), reason, false));
    }

    public static boolean fallbackReasonAllowed(Reason reason) {
        return reason == Reason.PLAYER_LIMIT || reason == Reason.GLOBAL_LIMIT
                || reason == Reason.PERMISSION || reason == Reason.COOLDOWN || reason == Reason.DATE_WINDOW;
    }

    private static String normalizeId(String raw) {
        String id = Objects.requireNonNull(raw, "rewardId").trim().toLowerCase(java.util.Locale.ROOT);
        if (!id.matches("[a-z0-9][a-z0-9_-]{0,63}")) {
            throw new IllegalArgumentException("Invalid reward ID: " + raw);
        }
        return id;
    }
}

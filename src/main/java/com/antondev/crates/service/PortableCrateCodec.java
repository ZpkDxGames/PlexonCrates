package com.antondev.crates.service;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

/**
 * HMAC codec for single-use portable crate metadata. The Bukkit/PDC adapter
 * can store the returned token on an item; this class never exposes the local
 * signing secret through exports, logs, or display text.
 */
public final class PortableCrateCodec {
    public static final int VERSION = 1;
    private static final String ALGORITHM = "HmacSHA256";

    private PortableCrateCodec() {}

    public enum RevisionPolicy {
        LATEST_PUBLISHED, PINNED_REVISION
    }

    public record Payload(
            UUID issueId,
            String crateId,
            RevisionPolicy revisionPolicy,
            long pinnedRevision,
            UUID issuedTo) {
        public Payload {
            issueId = Objects.requireNonNull(issueId, "issueId");
            crateId = normalizeCrateId(crateId);
            revisionPolicy = Objects.requireNonNull(revisionPolicy, "revisionPolicy");
            issuedTo = issuedTo;
            if (revisionPolicy == RevisionPolicy.PINNED_REVISION && pinnedRevision < 1) {
                throw new IllegalArgumentException("Pinned portable crates need a positive revision");
            }
            if (revisionPolicy == RevisionPolicy.LATEST_PUBLISHED && pinnedRevision != 0) {
                throw new IllegalArgumentException("Latest portable crates cannot carry a pinned revision");
            }
        }

        public String canonical() {
            return VERSION + "|" + issueId + "|" + crateId + "|" + revisionPolicy.name()
                    + "|" + pinnedRevision + "|" + (issuedTo == null ? "-" : issuedTo);
        }
    }

    public record Token(Payload payload, String signature) {
        public Token {
            payload = Objects.requireNonNull(payload, "payload");
            signature = Objects.requireNonNull(signature, "signature");
        }

        public String encoded() {
            String data = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    payload.canonical().getBytes(StandardCharsets.UTF_8));
            return "pc" + VERSION + "." + data + "." + signature;
        }
    }

    public static Token issue(String crateId, RevisionPolicy policy, long pinnedRevision, UUID issuedTo,
                              byte[] secret) {
        Payload payload = new Payload(UUID.randomUUID(), crateId, policy,
                policy == RevisionPolicy.PINNED_REVISION ? pinnedRevision : 0, issuedTo);
        return new Token(payload, sign(payload, secret));
    }

    public static Token decodeAndVerify(String encoded, byte[] secret) {
        Objects.requireNonNull(encoded, "encoded");
        Objects.requireNonNull(secret, "secret");
        String[] parts = encoded.split("\\.", -1);
        if (parts.length != 3 || !parts[0].equals("pc" + VERSION)) {
            throw new IllegalArgumentException("Invalid portable crate token format");
        }
        byte[] canonical;
        try {
            canonical = Base64.getUrlDecoder().decode(parts[1]);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Invalid portable crate payload", error);
        }
        String text = new String(canonical, StandardCharsets.UTF_8);
        String[] fields = text.split("\\|", -1);
        if (fields.length != 6 || !fields[0].equals(Integer.toString(VERSION))) {
            throw new IllegalArgumentException("Invalid portable crate payload");
        }
        UUID issueId;
        UUID issuedTo = null;
        try {
            issueId = UUID.fromString(fields[1]);
            if (!fields[5].equals("-")) issuedTo = UUID.fromString(fields[5]);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Invalid portable crate UUID", error);
        }
        RevisionPolicy policy;
        try {
            policy = RevisionPolicy.valueOf(fields[3]);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Invalid portable crate revision policy", error);
        }
        long pinned;
        try {
            pinned = Long.parseLong(fields[4]);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("Invalid portable crate revision", error);
        }
        Payload payload = new Payload(issueId, fields[2], policy, pinned, issuedTo);
        String expected = sign(payload, secret);
        byte[] left = Base64.getUrlDecoder().decode(parts[2]);
        byte[] right = Base64.getUrlDecoder().decode(expected);
        if (!MessageDigest.isEqual(left, right)) throw new SecurityException("Invalid portable crate signature");
        return new Token(payload, parts[2]);
    }

    public static boolean verifies(String encoded, byte[] secret) {
        try {
            decodeAndVerify(encoded, secret);
            return true;
        } catch (RuntimeException error) {
            return false;
        }
    }

    public static String sign(Payload payload, byte[] secret) {
        Objects.requireNonNull(payload, "payload");
        if (secret == null || secret.length < 16) throw new IllegalArgumentException("Portable signing secret is too short");
        try {
            var mac = javax.crypto.Mac.getInstance(ALGORITHM);
            mac.init(new javax.crypto.spec.SecretKeySpec(secret.clone(), ALGORITHM));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(
                    payload.canonical().getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("HMAC is unavailable", error);
        }
    }

    private static String normalizeCrateId(String raw) {
        String id = Objects.requireNonNull(raw, "crateId").trim().toLowerCase(java.util.Locale.ROOT);
        if (!id.matches("[a-z0-9][a-z0-9_-]{0,63}")) {
            throw new IllegalArgumentException("Invalid crate ID: " + raw);
        }
        return id;
    }
}

package com.antondev.crates.migration.phoenix;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Read-only PhoenixCratesLite migration boundary.
 *
 * <p>The current 3.0 finalization specification explicitly forbids guessing
 * undocumented Phoenix field layouts. Until an operator-owned live fixture is
 * supplied and a reviewed source adapter is implemented, this service only
 * scans and fingerprints files. It never mutates the source tree and refuses
 * IMPORT_TO_DRAFTS.</p>
 */
public final class PhoenixMigrationService {
    public static final long MAX_SOURCE_FILE_BYTES = 64L * 1024L * 1024L;
    private static final DateTimeFormatter REPORT_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final Path sourceRoot;
    private final Path reportRoot;

    public PhoenixMigrationService(Path pluginDataFolder) {
        Objects.requireNonNull(pluginDataFolder, "pluginDataFolder");
        Path root = pluginDataFolder.toAbsolutePath().normalize();
        this.sourceRoot = root.resolve("imports").resolve("phoenix").normalize();
        this.reportRoot = root.resolve("migration-reports").normalize();
        if (!sourceRoot.startsWith(root) || !reportRoot.startsWith(root)) {
            throw new IllegalArgumentException("Phoenix migration paths must remain inside PlexonCrates data");
        }
    }

    public Path sourceRoot() {
        return sourceRoot;
    }

    public Path reportRoot() {
        return reportRoot;
    }

    /**
     * Scans without following links and records deterministic per-file hashes.
     * No source file is opened for write, renamed, moved, normalized or deleted.
     */
    public ScanResult scan() throws IOException {
        Files.createDirectories(sourceRoot);
        Files.createDirectories(reportRoot);

        List<SourceFile> files = new ArrayList<>();
        try (var stream = Files.walk(sourceRoot, FileVisitOption.FOLLOW_LINKS)) {
            for (Path candidate : stream.sorted().toList()) {
                Path normalized = candidate.toAbsolutePath().normalize();
                if (!normalized.startsWith(sourceRoot)) {
                    throw new IOException("Phoenix source path escapes configured import root: " + candidate);
                }
                if (candidate.equals(sourceRoot)) continue;
                if (Files.isSymbolicLink(candidate)) {
                    throw new IOException("Symbolic links are not permitted in Phoenix migration sources: "
                            + sourceRoot.relativize(candidate));
                }
                if (!Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) continue;
                long size = Files.size(candidate);
                if (size > MAX_SOURCE_FILE_BYTES) {
                    throw new IOException("Phoenix source file exceeds the 64 MiB scan limit: "
                            + sourceRoot.relativize(candidate));
                }
                String relative = portableRelative(sourceRoot.relativize(normalized));
                files.add(new SourceFile(relative, size, sha256(candidate)));
            }
        }
        files.sort(Comparator.comparing(SourceFile::relativePath));
        String fingerprint = fingerprint(files);
        List<String> warnings = files.isEmpty()
                ? List.of("No Phoenix migration fixture files were found under imports/phoenix.")
                : List.of("Source files were fingerprinted only; no Phoenix schema adapter is enabled yet.");
        return new ScanResult(Instant.now(), fingerprint, List.copyOf(files), warnings);
    }

    /** Creates a deterministic migration plan that remains blocked until a fixture adapter exists. */
    public PlanResult plan(ScanResult scan) {
        Objects.requireNonNull(scan, "scan");
        List<PlanEntry> entries = scan.files().stream()
                .map(file -> new PlanEntry(file.relativePath(), MigrationStatus.MANUAL_REVIEW,
                        "Awaiting reviewed Phoenix source schema mapping"))
                .toList();
        return new PlanResult(Instant.now(), scan.fingerprint(), entries, false,
                "Field-complete Phoenix import is gated until the operator supplies the current PlexonCraft "
                        + "PhoenixCratesLite data folder or sanitized exact fixtures.");
    }

    /**
     * Deliberately fails closed. A source adapter must be implemented and tested
     * against the real operator fixture before any definitions can be created.
     */
    public void importToDrafts(PlanResult plan) {
        Objects.requireNonNull(plan, "plan");
        throw new IllegalStateException(
                "Phoenix IMPORT_TO_DRAFTS is blocked: no reviewed source schema adapter is available");
    }

    public ValidationResult validate(PlanResult plan) {
        Objects.requireNonNull(plan, "plan");
        boolean fingerprintPresent = plan.sourceFingerprint() != null && !plan.sourceFingerprint().isBlank();
        return new ValidationResult(fingerprintPresent && plan.importEnabled(),
                plan.importEnabled() ? List.of() : List.of(
                        "Phoenix source schema has not been established from an operator-provided fixture.",
                        "No imported crate may be published from this framework state."));
    }

    public Path writeReport(ScanResult scan, PlanResult plan) throws IOException {
        Objects.requireNonNull(scan, "scan");
        Objects.requireNonNull(plan, "plan");
        Files.createDirectories(reportRoot);
        String fileName = "phoenix-" + REPORT_TIME.format(Instant.now()) + ".md";
        Path report = confinedReport(fileName);
        StringBuilder body = new StringBuilder();
        body.append("# PlexonCrates Phoenix Migration Report\n\n")
                .append("- Phase: `PLAN`\n")
                .append("- Source root: `imports/phoenix/`\n")
                .append("- Source fingerprint: `").append(scan.fingerprint()).append("`\n")
                .append("- Source files: ").append(scan.files().size()).append("\n")
                .append("- Import enabled: **").append(plan.importEnabled()).append("**\n\n")
                .append("## Source files\n\n")
                .append("| File | Bytes | SHA-256 | Status |\n")
                .append("| --- | ---: | --- | --- |\n");
        for (SourceFile source : scan.files()) {
            body.append("| `").append(markdown(source.relativePath())).append("` | ")
                    .append(source.size()).append(" | `").append(source.sha256()).append("` | MANUAL_REVIEW |\n");
        }
        if (scan.files().isEmpty()) body.append("| _none_ | 0 | - | MANUAL_REVIEW |\n");
        body.append("\n## Gate\n\n").append(plan.detail()).append("\n\n")
                .append("The scanner did not modify Phoenix source files. No crate definitions were imported.\n");
        Files.writeString(report, body.toString(), StandardCharsets.UTF_8);
        return report;
    }

    private Path confinedReport(String fileName) throws IOException {
        if (!fileName.matches("phoenix-[0-9]{8}-[0-9]{6}\\.md")) {
            throw new IOException("Unsafe Phoenix migration report name");
        }
        Path candidate = reportRoot.resolve(fileName).normalize();
        if (!candidate.getParent().equals(reportRoot)) {
            throw new IOException("Migration report path escapes report directory");
        }
        return candidate;
    }

    private static String sha256(Path path) throws IOException {
        MessageDigest digest = sha256Digest();
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String fingerprint(List<SourceFile> files) {
        MessageDigest digest = sha256Digest();
        for (SourceFile file : files) {
            digest.update(file.relativePath().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(Long.toString(file.size()).getBytes(StandardCharsets.US_ASCII));
            digest.update((byte) 0);
            digest.update(file.sha256().getBytes(StandardCharsets.US_ASCII));
            digest.update((byte) '\n');
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String portableRelative(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static String markdown(String value) {
        return value.replace("|", "\\|").replace("`", "'");
    }

    public enum Phase {
        SCAN,
        PLAN,
        IMPORT_TO_DRAFTS,
        VALIDATE,
        CUTOVER_REPORT
    }

    public enum MigrationStatus {
        EXACT,
        CONVERTED,
        MANUAL_REVIEW,
        UNSUPPORTED,
        SKIPPED,
        CONFLICT
    }

    public record SourceFile(String relativePath, long size, String sha256) {
        public SourceFile {
            Objects.requireNonNull(relativePath, "relativePath");
            Objects.requireNonNull(sha256, "sha256");
            if (relativePath.isBlank() || size < 0 || !sha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Invalid Phoenix source file metadata");
            }
        }
    }

    public record ScanResult(Instant scannedAt, String fingerprint, List<SourceFile> files, List<String> warnings) {
        public ScanResult {
            scannedAt = Objects.requireNonNull(scannedAt, "scannedAt");
            fingerprint = Objects.requireNonNull(fingerprint, "fingerprint");
            files = List.copyOf(files);
            warnings = List.copyOf(warnings);
        }
    }

    public record PlanEntry(String source, MigrationStatus status, String detail) {
        public PlanEntry {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(status, "status");
            detail = detail == null ? "" : detail;
        }
    }

    public record PlanResult(Instant plannedAt, String sourceFingerprint, List<PlanEntry> entries,
                             boolean importEnabled, String detail) {
        public PlanResult {
            plannedAt = Objects.requireNonNull(plannedAt, "plannedAt");
            sourceFingerprint = Objects.requireNonNull(sourceFingerprint, "sourceFingerprint");
            entries = List.copyOf(entries);
            detail = detail == null ? "" : detail;
        }
    }

    public record ValidationResult(boolean validForImport, List<String> issues) {
        public ValidationResult {
            issues = List.copyOf(issues);
        }
    }
}

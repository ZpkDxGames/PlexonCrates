from pathlib import Path

path = Path('src/main/java/com/antondev/crates/service/CrateRegistry.java')
text = path.read_text()

marker = '    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");\n'
if marker not in text:
    raise SystemExit('CrateRegistry record marker missing')
record = '''    public record PreparedDraftImport(String crateId, Crate crate, byte[] payload, Path file) {
        public PreparedDraftImport {
            crateId = java.util.Objects.requireNonNull(crateId, "crateId");
            crate = java.util.Objects.requireNonNull(crate, "crate");
            payload = java.util.Objects.requireNonNull(payload, "payload").clone();
            file = java.util.Objects.requireNonNull(file, "file");
        }
        @Override public byte[] payload() { return payload.clone(); }
    }

'''
text = text.replace(marker, record + marker, 1)

start = text.index('    public Crate importAsDraft(Path sourceFile, String rawNewId, String editor) throws Exception {')
end = text.index('    public Path exportDefinition(', start)
replacement = '''    public PreparedDraftImport prepareImportedDraft(String sourceYaml, String rawNewId, String editor) throws Exception {
        String newId = normalize(rawNewId);
        if (!validId(newId) || crates.containsKey(newId)) {
            throw new IllegalArgumentException("Invalid or existing imported crate ID");
        }
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(java.util.Objects.requireNonNull(sourceYaml, "sourceYaml"));
        yaml.set("id", newId);
        yaml.set("state", "DRAFT");
        yaml.set("display-order", nextDisplayOrder());
        yaml.set("audit.created-at", Instant.now().toString());
        yaml.set("audit.updated-at", Instant.now().toString());
        yaml.set("audit.last-editor", editor);
        Path destination = directory.resolve(newId + ".yml").normalize();
        if (!destination.getParent().equals(directory.normalize())) {
            throw new IllegalArgumentException("Invalid imported crate path");
        }
        Crate parsed = parse(destination, yaml);
        byte[] payload = yaml.saveToString().getBytes(StandardCharsets.UTF_8);
        return new PreparedDraftImport(newId, parsed, payload, destination);
    }

    public void writeImportedDraft(PreparedDraftImport prepared) throws IOException {
        AtomicFiles.write(prepared.file(), new String(prepared.payload(), StandardCharsets.UTF_8));
    }

    public Crate installImportedDraft(PreparedDraftImport prepared) {
        String id = prepared.crateId();
        if (crates.containsKey(id)) throw new IllegalArgumentException("Imported crate ID became occupied: " + id);
        install(id, prepared.file(), prepared.crate());
        payloads.put(id, prepared.payload());
        fireChange(prepared.crate(), CrateDefinitionChangeEvent.ChangeType.CREATED);
        return prepared.crate();
    }

    public Crate importAsDraft(Path sourceFile, String rawNewId, String editor) throws Exception {
        Path source = sourceFile.toAbsolutePath().normalize();
        if (!Files.isRegularFile(source) || !source.getFileName().toString().endsWith(".yml")) {
            throw new IllegalArgumentException("Import source must be an existing .yml file");
        }
        PreparedDraftImport prepared = prepareImportedDraft(
                Files.readString(source, StandardCharsets.UTF_8), rawNewId, editor);
        writeImportedDraft(prepared);
        return installImportedDraft(prepared);
    }

'''
text = text[:start] + replacement + text[end:]
path.write_text(text)

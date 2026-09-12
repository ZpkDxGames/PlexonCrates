from pathlib import Path

path = Path('src/main/java/com/antondev/crates/migration/phoenix/PhoenixMigrationService.java')
text = path.read_text()
start = text.index('        Files.writeString(marker, "source=" + SOURCE + "', text.index('private PersistedImport persistAsyncImport'))
end_marker = '                + dataRoot.relativize(report) + "\n", StandardCharsets.UTF_8);'
try:
    end = text.index(end_marker, start) + len(end_marker)
except ValueError:
    # The staged source currently contains literal newlines inside Java strings.
    end = text.index('StandardCharsets.UTF_8);', start) + len('StandardCharsets.UTF_8);')
replacement = '''        Files.writeString(marker, "source=" + SOURCE + "\\nfingerprint=" + prepared.scan().fingerprint()
                + "\\nimported-at=" + prepared.importedAt() + "\\nbackup=" + dataRoot.relativize(prepared.backup())
                + "\\nreport=" + dataRoot.relativize(report) + "\\n", StandardCharsets.UTF_8);'''
text = text[:start] + replacement + text[end:]
path.write_text(text)

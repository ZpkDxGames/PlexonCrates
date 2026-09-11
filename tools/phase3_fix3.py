from pathlib import Path

FILES = [
    Path('src/test/java/com/antondev/crates/PluginIntegrationTest.java'),
    Path('src/test/java/com/antondev/crates/OpeningPipelineIntegrationTest.java'),
    Path('src/test/java/com/antondev/crates/PlexonKeysIntegrationTest.java'),
]

OLD = '''    private void awaitOpeningCommit() {\n        plugin.database().awaitIdle().join();\n        server.getScheduler().performTicks(2);\n        plugin.database().awaitIdle().join();\n        server.getScheduler().performTicks(2);\n    }'''

NEW = '''    private void awaitOpeningCommit() {\n        // Phase 3 adds durable PREPARED -> PAYMENT_ATTEMPTED -> PAYMENT_COMMITTED\n        // -> GRANT_ATTEMPTED -> COMPLETED barriers. Alternate the bounded DB\n        // worker barrier with primary-thread scheduler ticks until all continuations\n        // have had a chance to run; do not weaken the terminal assertions below.\n        for (int pass = 0; pass < 12; pass++) {\n            plugin.database().awaitIdle().join();\n            server.getScheduler().performTicks(2);\n        }\n        plugin.database().awaitIdle().join();\n        server.getScheduler().performTicks(2);\n        assertEquals(0, plugin.openings().pendingCount(),\n                "opening pipeline did not reach a terminal state");\n    }'''

for path in FILES:
    text = path.read_text(encoding='utf-8')
    if OLD not in text:
        raise SystemExit(f'expected legacy awaitOpeningCommit helper not found in {path}')
    path.write_text(text.replace(OLD, NEW), encoding='utf-8')

portable = FILES[0]
text = portable.read_text(encoding='utf-8')
old_portable = '''    private void awaitPortableCommit() {\n        for (int pass = 0; pass < 6; pass++) {\n            plugin.database().awaitIdle().join();\n            server.getScheduler().performTicks(2);\n        }\n    }'''
new_portable = '''    private void awaitPortableCommit() {\n        // Portable openings add issuance reservation/consume ahead of the same\n        // schema-4 payment and grant barriers, so drain the complete bounded chain.\n        for (int pass = 0; pass < 14; pass++) {\n            plugin.database().awaitIdle().join();\n            server.getScheduler().performTicks(2);\n        }\n        plugin.database().awaitIdle().join();\n        server.getScheduler().performTicks(2);\n        assertEquals(0, plugin.openings().pendingCount(),\n                "portable opening pipeline did not reach a terminal state");\n    }'''
if old_portable not in text:
    raise SystemExit('expected legacy awaitPortableCommit helper not found')
portable.write_text(text.replace(old_portable, new_portable), encoding='utf-8')

from pathlib import Path

path = Path('src/test/java/com/antondev/crates/PluginIntegrationTest.java')
text = path.read_text(encoding='utf-8')
old = '''        assertTrue(rewardLore.contains("Eligible chance » 28%"));
        assertTrue(rewardLore.contains("Base chance » 28%"));
'''
new = '''        assertTrue(rewardLore.contains("Current pool chance: 28%"));
        assertFalse(rewardLore.contains("Configured base chance:"));
'''
if old not in text:
    raise SystemExit('expected legacy preview assertions not found')
path.write_text(text.replace(old, new, 1), encoding='utf-8')

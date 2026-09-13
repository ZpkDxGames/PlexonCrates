package com.antondev.crates.config;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GuiConfigMigrationTest {
    @TempDir Path temp;

    @Test void pre65ConfigIsBackedUpAndMigratedWithoutTouchingCustomText() throws Exception {
        Path file = temp.resolve("menus.yml");
        Files.writeString(file, "filler:\n  material: BLUE_STAINED_GLASS_PANE\n  name: 'Custom shell'\nclaims:\n  size: 54\n  claim-slots: [10]\n  previous: {slot: 47}\n  back: {slot: 48}\n  guide: {slot: 49}\n  next: {slot: 51}\n  close: {slot: 53}\n");
        YamlConfiguration migrated = GuiConfigMigrator.loadAndMigrate(file.toFile());
        assertEquals(2, migrated.getInt("schema-version"));
        assertTrue(Files.exists(temp.resolve("menus.yml.pre-6.5.bak")));
        assertEquals("Custom shell", migrated.getString("filler.name"));
        assertEquals("GRAY_STAINED_GLASS_PANE", migrated.getString("theme.background.material"));
        assertEquals(45, migrated.getInt("claims.previous.slot"));
        assertEquals(52, migrated.getInt("claims.close.slot"));
        assertEquals(28, migrated.getIntegerList("claims.claim-slots").size());
    }

    @Test void schema2DoesNotCreateAnotherBackup() throws Exception {
        Path file = temp.resolve("menus2.yml");
        Files.writeString(file, "schema-version: 2\ntheme:\n  background:\n    material: GRAY_STAINED_GLASS_PANE\n");
        GuiConfigMigrator.loadAndMigrate(file.toFile());
        assertFalse(Files.exists(temp.resolve("menus.yml.pre-6.5.bak")));
    }
}

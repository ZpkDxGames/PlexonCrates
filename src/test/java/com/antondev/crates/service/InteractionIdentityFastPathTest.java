package com.antondev.crates.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.antondev.crates.PlexonCrates;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

final class InteractionIdentityFastPathTest {
    private PlexonCrates plugin;

    @BeforeEach
    void setUp() {
        var server = MockBukkit.mock();
        server.addSimpleWorld("Survival_World");
        plugin = MockBukkit.load(PlexonCrates.class);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void wandRejectsUnrelatedMaterialBeforeMetadataAccess() {
        ItemStack item = mock(ItemStack.class);
        when(item.getType()).thenReturn(Material.DIAMOND_PICKAXE);

        assertFalse(plugin.wand().isWand(item));
        verify(item, never()).hasItemMeta();
        verify(item, never()).getItemMeta();
    }

    @Test
    void wandCandidateWithoutMetaNeverMaterializesItemMeta() {
        ItemStack item = mock(ItemStack.class);
        when(item.getType()).thenReturn(Material.BLAZE_ROD);
        when(item.hasItemMeta()).thenReturn(false);

        assertFalse(plugin.wand().isWand(item));
        verify(item, never()).getItemMeta();
    }

    @Test
    void portableRejectsUnrelatedMaterialBeforeMetadataAccess() {
        ItemStack item = mock(ItemStack.class);
        when(item.getType()).thenReturn(Material.DIAMOND_SWORD);

        assertFalse(plugin.portables().isPortable(item));
        verify(item, never()).hasItemMeta();
        verify(item, never()).getItemMeta();
    }

    @Test
    void portableCandidateWithoutMetaNeverMaterializesItemMeta() {
        ItemStack item = mock(ItemStack.class);
        when(item.getType()).thenReturn(Material.CHEST);
        when(item.hasItemMeta()).thenReturn(false);

        assertFalse(plugin.portables().isPortable(item));
        verify(item, never()).getItemMeta();
    }
}

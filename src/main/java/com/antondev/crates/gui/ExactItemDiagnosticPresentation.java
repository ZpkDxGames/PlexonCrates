package com.antondev.crates.gui;

import com.antondev.crates.item.ExactItemInspector;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.inventory.ItemStack;

/** Read-only GUI formatting for exact native-ItemStack diagnostics. */
public final class ExactItemDiagnosticPresentation {
    private static final int MAX_BUNDLE_DETAILS = 3;
    private final ExactItemInspector inspector;

    public ExactItemDiagnosticPresentation() {
        this(new ExactItemInspector());
    }

    ExactItemDiagnosticPresentation(ExactItemInspector inspector) {
        this.inspector = Objects.requireNonNull(inspector, "inspector");
    }

    public List<Component> single(ItemStack source, String label) {
        Objects.requireNonNull(source, "source");
        ExactItemInspector.Diagnostics value = inspector.inspect(source);
        var lines = new ArrayList<Component>();
        lines.add(Component.text(label, NamedTextColor.AQUA));
        lines.add(pair("Material", value.material()));
        lines.add(pair("Captured amount", value.capturedAmount()));
        lines.add(pair("Native bytes", value.serializedBytes()));
        lines.add(pair("SHA-256", value.shortFingerprint()));
        lines.add(pair("Custom data", yesNo(value.customDataPresent())));
        lines.add(pair("Container data", yesNo(value.containerContentsPresent())));
        lines.add(pair("Max stack", value.maximumStackSize()));
        return List.copyOf(lines);
    }

    public List<Component> bundle(List<ItemStack> sources) {
        Objects.requireNonNull(sources, "sources");
        List<ItemStack> items = sources.stream()
                .filter(Objects::nonNull)
                .filter(item -> !item.getType().isAir())
                .toList();
        var lines = new ArrayList<Component>();
        lines.add(Component.text("Exact delivery bundle", NamedTextColor.AQUA));
        lines.add(pair("Stacks", items.size()));
        if (items.isEmpty()) {
            lines.add(Component.text("No exact item delivery is configured.", NamedTextColor.GRAY));
            return List.copyOf(lines);
        }
        int details = Math.min(items.size(), MAX_BUNDLE_DETAILS);
        for (int index = 0; index < details; index++) {
            ExactItemInspector.Diagnostics value = inspector.inspect(items.get(index));
            lines.add(Component.text("#" + (index + 1) + " " + value.material()
                    + " ×" + value.capturedAmount(), NamedTextColor.WHITE));
            lines.add(Component.text("  " + value.serializedBytes() + " bytes • "
                    + value.shortFingerprint(), NamedTextColor.GRAY));
            lines.add(Component.text("  custom=" + yesNo(value.customDataPresent())
                    + " • container=" + yesNo(value.containerContentsPresent())
                    + " • max-stack=" + value.maximumStackSize(), NamedTextColor.DARK_GRAY));
        }
        if (items.size() > details) {
            lines.add(Component.text("+" + (items.size() - details)
                    + " more exact stack template(s)", NamedTextColor.GRAY));
        }
        return List.copyOf(lines);
    }

    private static Component pair(String label, Object value) {
        return Component.text(label + ": ", NamedTextColor.GRAY)
                .append(Component.text(String.valueOf(value), NamedTextColor.WHITE));
    }

    private static String yesNo(boolean value) {
        return value ? "yes" : "no";
    }
}

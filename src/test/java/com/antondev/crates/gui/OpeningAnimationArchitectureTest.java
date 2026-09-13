package com.antondev.crates.gui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class OpeningAnimationArchitectureTest {
    private static final Path MENU = Path.of("src/main/java/com/antondev/crates/gui/MenuService.java");
    private static final Path COORDINATOR = Path.of("src/main/java/com/antondev/crates/gui/OpeningAnimationCoordinator.java");

    @Test
    void rouletteDelegatesToOneSharedCoordinator() throws Exception {
        String menu = Files.readString(MENU);
        String animate = section(menu, "public void animate", "public void reveal");
        assertTrue(animate.contains("animations.start("));
        assertFalse(animate.contains("new BukkitRunnable"));
        assertFalse(animate.contains("runTaskTimer"));
        assertTrue(menu.contains("private final OpeningAnimationCoordinator animations"));
    }

    @Test
    void coordinatorStopsItsOnlyTaskWhenNoAnimationsRemain() throws Exception {
        String source = Files.readString(COORDINATOR);
        assertTrue(source.contains("Map<UUID, Animation> active"));
        assertTrue(source.contains("task = Bukkit.getScheduler().runTaskTimer"));
        assertTrue(source.contains("if (active.isEmpty() && task != null)"));
        assertTrue(source.contains("task.cancel()"));
        assertTrue(source.contains("public void stop()"));
    }

    @Test
    void invisibleOrClosedAnimationIsRetiredBeforeAnotherVisualTick() throws Exception {
        String source = Files.readString(COORDINATOR);
        assertTrue(source.contains("boolean visible = player.isOnline()"));
        assertTrue(source.contains("if (!visible)"));
        assertTrue(source.contains("iterator.remove()"));
        assertTrue(source.contains("complete(animation)"));
        String invisible = section(source, "if (!visible)", "int step = ++animation.step");
        assertFalse(invisible.contains("inventory().setItem"));
        assertFalse(invisible.contains("playSound"));
    }

    @Test
    void completionCallbackHasOneCentralInvocationPath() throws Exception {
        String source = Files.readString(COORDINATOR);
        assertTrue(source.contains("private void complete(Animation animation)"));
        assertTrue(source.contains("animation.completed().run()"));
        assertFalse(source.contains("animation.completed().run();\n            } catch")
                && source.indexOf("animation.completed().run()") != source.lastIndexOf("animation.completed().run()"));
    }

    @Test
    void menuExposesExplicitAnimationShutdown() throws Exception {
        String source = Files.readString(MENU);
        assertTrue(source.contains("public void stop()"));
        assertTrue(source.contains("animations.stop()"));
    }

    private static String section(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + start.length());
        if (from < 0 || to < 0) throw new AssertionError("Could not isolate " + start);
        return source.substring(from, to);
    }
}

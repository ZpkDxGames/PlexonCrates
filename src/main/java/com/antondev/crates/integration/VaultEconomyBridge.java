package com.antondev.crates.integration;

import java.lang.reflect.Method;
import java.util.Optional;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/** Optional Vault adapter without a compile-time dependency. */
public final class VaultEconomyBridge {
    private final JavaPlugin owner;
    private volatile Binding binding;
    private volatile String diagnostic = "Vault has not been resolved.";

    public VaultEconomyBridge(JavaPlugin owner) {
        this.owner = owner;
    }

    public boolean available() {
        return resolve().isPresent();
    }

    public boolean deposit(OfflinePlayer player, double amount) {
        return transact(player, amount, true);
    }

    public boolean withdraw(OfflinePlayer player, double amount) {
        return transact(player, amount, false);
    }

    private boolean transact(OfflinePlayer player, double amount, boolean deposit) {
        if (amount <= 0) return true;
        Optional<Binding> resolved = resolve();
        if (resolved.isEmpty()) return false;
        try {
            Binding current = resolved.get();
            Method operation = deposit ? current.deposit() : current.withdraw();
            Object response = operation.invoke(current.provider(), player, amount);
            Object success = current.success().invoke(response);
            return success instanceof Boolean value && value;
        } catch (ReflectiveOperationException | RuntimeException error) {
            diagnostic = "Vault economy " + (deposit ? "deposit" : "withdrawal") + " failed: " + concise(error);
            return false;
        }
    }

    public String diagnostic() { return diagnostic; }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Optional<Binding> resolve() {
        Binding current = binding;
        if (current != null) return Optional.of(current);
        Plugin vault = owner.getServer().getPluginManager().getPlugin("Vault");
        if (vault == null || !vault.isEnabled()) {
            diagnostic = "Vault is absent or disabled.";
            return Optional.empty();
        }
        try {
            Class<?> economy = Class.forName("net.milkbowl.vault.economy.Economy", true, vault.getClass().getClassLoader());
            Object provider = owner.getServer().getServicesManager().load((Class) economy);
            if (provider == null) {
                diagnostic = "Vault is present but no economy provider is registered.";
                return Optional.empty();
            }
            Method deposit = economy.getMethod("depositPlayer", OfflinePlayer.class, double.class);
            Method withdraw = economy.getMethod("withdrawPlayer", OfflinePlayer.class, double.class);
            Class<?> response = deposit.getReturnType();
            Method success = response.getMethod("transactionSuccess");
            current = new Binding(provider, deposit, withdraw, success);
            binding = current;
            diagnostic = "Vault economy provider is ready.";
            return Optional.of(current);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException error) {
            diagnostic = "Vault economy API is incompatible: " + concise(error);
            return Optional.empty();
        }
    }

    private static String concise(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private record Binding(Object provider, Method deposit, Method withdraw, Method success) {}
}

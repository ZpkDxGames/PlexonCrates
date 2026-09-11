from pathlib import Path

path = Path('src/main/java/com/antondev/crates/service/OpeningService.java')
text = path.read_text(encoding='utf-8')

def once(old, new, label):
    global text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'{label}: expected one match, found {count}')
    text = text.replace(old, new, 1)

once('''            options.add(new PaymentOption(transaction,\n                    new KeyPaymentPlanner.Availability(keyId, physical, 0, priority++), 0));''',
     '''            options.add(new PaymentOption(transaction,\n                    new KeyPaymentPlanner.Availability(keyId, physical, 0, priority++), 0, false));''',
     'physical PaymentOption')

once('''    static String plexonKeysPaymentTransactionId(UUID openingId) {''',
     '''    public static String plexonKeysPaymentTransactionId(UUID openingId) {''',
     'transaction identity visibility')

once('''            capacities.add(plugin.database().loadVirtualKeyBalance(player.getUniqueId(), keyId)\n                    .thenApply(balance -> paymentCapacity(crate, physical, balance.balance())));''',
     '''            if (plugin.keys().usesPlexonKeysWallet(keyId)) {\n                long providerBalance;\n                try {\n                    providerBalance = plugin.keys().plexonKeysBalance(player.getUniqueId(), keyId);\n                } catch (RuntimeException error) {\n                    plugin.getLogger().log(Level.WARNING,\n                            "Could not read PlexonKeys capacity for " + keyId, error);\n                    providerBalance = 0L;\n                }\n                capacities.add(CompletableFuture.completedFuture(paymentCapacity(crate, physical, providerBalance)));\n            } else {\n                capacities.add(plugin.database().loadVirtualKeyBalance(player.getUniqueId(), keyId)\n                        .thenApply(balance -> paymentCapacity(crate, physical, balance.balance())));\n            }''',
     'mass-opening provider balance')

path.write_text(text, encoding='utf-8')
print('Applied RC2 compile and wallet corrections.')

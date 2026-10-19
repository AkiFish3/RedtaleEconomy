# Redtale Economy

Economy mod for the [Redtale](https://space.bilibili.com/3546831095073406) Minecraft server.

## Info

| Item | Value |
|------|-------|
| Mod ID | `redtale_economy` |
| MC version | 1.20.1 |
| Platform | Fabric |
| Requires | Fabric API (optional: Numismatic, FTB Quests, FTB Ranks / LuckPerms) |

## Features

- **Economy** — balances stored in copper with multi-denomination display; multi-wallet (premium / free / voucher, voucher consumed FIFO)
- **Shop** — paged delivery (the client only receives the current page), stock, purchase limits, price floor/ceiling with a per-edit delta guard, deletes go to a trash folder
- **Check-in** — streaks, looped reward table, makeup tickets (`makeup_ticket` item)
- **Daily tasks** — budget and weight solver that refuses infeasible sets instead of fudging them
- **Storage** — switchable `json` / `mysql`, ledger (daily or monthly) plus snapshots, two-way migration with dry-run and full backup
- **Permissions** — probes FTB Ranks → LuckPerms → OP, falling back to OP only when neither is present
- **Aliases** — implemented via `redirect`, so permission checks still follow the target command
> Every number, interval, cap, path, toggle and message lives in `config/redtale/*.json` and is hot-reloaded by `/redtale reload`.

Soft integrations (the mod boots without them): Numismatic as an external balance provider, FTB Quests with a
`redtale_economy:money` reward type.

## Commands

| Command | Permission | Scope |
|---------|------------|-------|
| `/redtale help` | `redtale.use` | Command index |
| `/redtale info` / `modules` | `redtale.use` | Runtime state and module toggles |
| `/redtale reload` / `save` | `redtale.admin` | Hot reload / flush to disk (with backup) |
| `/redtale edit <file>` | `redtale.admin` | Edit scalar config in game |
| `/redtale lang <code>` | `redtale.admin` | Switch language |
| `/redtale migrate provider\|storage\|identity ... [confirm]` | `redtale.owner` | Switch balance source / storage backend / reattach an old UUID |
| `/quests` | `redtale.use` | Forward to FTB Quests |

Migration commands always report counts first and back up to `<root>/backup/<action>-<timestamp>/` before touching data.

## Build

**JDK 17** is required (Gradle 8.3 / Loom 1.3.7 do not support JDK 21+). Keep the JDK path out of the repo:

```properties
# ~/.gradle/gradle.properties
org.gradle.java.home=/path/to/jdk-17
```

Third-party mod jars are not distributed here (see [`libs/README.md`](libs/README.md)):

```bash
./gradlew build
# build/libs/redtale_economy-<version>.jar
```

## Credits

Built with AI assistance from **DeepSeek** and **CodeBuddy**.

## Join the server

QQ group: **1065844449**

## License

LGPL-3.0 License

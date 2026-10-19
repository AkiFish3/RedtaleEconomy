# Redtale Economy

Economy mod for the Redtale Minecraft server. Fabric 1.20.1 / Java 17.

Every number, interval, cap, path, toggle and message lives in `config/redtale/*.json` and is hot-reloaded
by `/redtale reload`. Code only carries defaults.

## Modules

| Module | Scope |
|---|---|
| economy | Balances stored in copper; multi-denomination display; multi-wallet (premium / free / voucher, voucher consumed FIFO) |
| shop | Paged shop (client only receives the current page), stock, purchase limits, price floor/ceiling with per-edit delta guard, deletes go to a trash folder |
| checkin | Daily check-in with streak and looped reward table, makeup tickets (`makeup_ticket` item) |
| tasks | Daily tasks with a budget/weight solver that refuses infeasible sets instead of fudging them |
| storage | Pluggable `json` / `mysql` backend, ledger (daily or monthly), snapshots, two-way migration with dry-run and full backup |
| premium | Grant and spend premium currency |
| aliases | Command aliases via `redirect`, so permission checks still follow the target command |

Soft integrations (all optional, the mod boots without them):

- **Numismatic** — external balance provider (`/redtale migrate provider numismatic`)
- **FTB Quests** — registers a `redtale_economy:money` reward type
- **FTB Ranks / LuckPerms** — permission providers; falls back to OP only when neither is present

## Build

**JDK 17** is required (Gradle 8.3 / Loom 1.3.7 do not support JDK 21+). Keep the JDK path out of the repo:

```properties
# ~/.gradle/gradle.properties
org.gradle.java.home=/path/to/jdk-17
```

Third-party mod jars are not distributed here (see [`libs/README.md`](libs/README.md)), then:

```bash
./gradlew build
# build/libs/redtale_economy-<version>.jar
```

## Commands

`/redtale help` lists everything. Notable ones: `reload`, `save`, `edit`, `lang`, `modules`, `info`, plus
`/redtale migrate provider|storage|identity ... [confirm]`. Migration commands always report counts first and
back up to `<root>/backup/<action>-<timestamp>/` before touching data.

## Config

```
core.json  economy.json  shop/settings.json  checkin.json  premium.json
storage.json  ftbq.json  sdm_migrate.json  commands.json  modules.json
```

Paths accept absolute values or the `%server%` placeholder. With the MySQL backend the password can live in a
separate file (`mysql.passwordFile`) and the JDBC driver is loaded from a configured jar at runtime, so nothing
secret or proprietary needs to be committed.

## Tests

```bash
./gradlew test
```

## License

CC0 1.0 Universal — see [LICENSE](LICENSE).

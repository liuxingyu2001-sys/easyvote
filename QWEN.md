# EasyVote — QWEN.md

## Project Overview

EasyVote is a lightweight Minecraft Paper/Folia server plugin that implements a Votifier-compatible vote listener. It receives vote notifications from Minecraft server listing websites (via Votifier v1 RSA and legacy/custom TCP formats; standard v2 is not implemented), dispatches configurable in-game rewards, and tracks cumulative vote milestones.

- **Language:** Java 21
- **Build:** Maven (shaded fat JAR)
- **Server API:** Paper / Folia 1.21.1+ (`paper-api`)
- **Storage:** SQLite (WAL mode) via `sqlite-jdbc` (loaded as a Paper library, not shaded)
- **JSON parsing:** `json-simple` (shaded into the JAR)
- **Output artifact:** `target/Liu-EasyVote-<version>.jar`
- **All user-facing text (logs, messages, config comments) is in Chinese (中文).**

## Architecture

```
VotifierServer (TCP, port 10022)
  ├─ Votifier v1.9: RSA-decrypted "VOTE ..." text protocol
  ├─ Legacy plaintext JSON (not standard Votifier v2)
  └─ Custom Votifier format: fixed-width binary-ish layout
        │
        ▼
    VoteEvent (Bukkit custom event, fired on async scheduler)
        │
        ▼
    VoteListener (@EventHandler)
        ├─ VoteHistory.recordVoteAndQueue() → atomic vote + pending reward, global first-vote detection
        ├─ Reward dispatch via Bukkit.getGlobalRegionScheduler() (Folia-safe)
        │   └─ console commands with %player%, %service%, %address%, %uuid% variables
        ├─ RewardDelivery → persisted per-command progress and retry on failure
        └─ MilestoneTracker.markIfNotReceived() → mark only after successful delivery
```

### Key Classes (`src/main/java/com/easyvote/`)

| Class | Role |
|---|---|
| `EasyVotePlugin` | Main plugin entry point; lifecycle, config loading, component wiring |
| `VotifierServer` | TCP server accepting Votifier v1/legacy JSON/custom connections |
| `RSAKeyManager` | RSA key pair generation, Base64 encoding, vote payload decryption |
| `VoteListener` | Bukkit event handler; reward resolution and dispatch |
| `VoteHistory` | Vote counting, first-vote detection, per-player/service stats (SQLite) |
| `MilestoneTracker` | Atomic milestone marking via `INSERT OR IGNORE` (SQLite) |
| `DatabaseManager` | SQLite connection, schema creation, CSV→SQLite migration |
| `EasyVoteCommand` | `/easyvote` command executor (reload, pubkey, votestats, votes, testvote, clearvotes) |
| `EasyVoteTabCompleter` | Tab completion for `/easyvote` subcommands |
| `EasyVoteExpansion` | Optional PlaceholderAPI registration, persistence across PAPI reload |
| `VotePlaceholders` | Read-only context/named-player queries; no online-player lookup |
| `RewardDelivery` | Per-command progress, failure retention and retry |
| `VoteEvent` | Custom Bukkit event carrying vote data |

## Building and Running

```bash
# Build shaded JAR
mvn clean package -q

# Build and copy to test servers (project skill)
/mvnp

# Output
target/Liu-EasyVote-<version>.jar
```

Deploy by placing the JAR in a Paper/Folia server's `plugins/` directory. On first run the plugin auto-generates RSA keys and creates `plugins/EasyVote/config.yml` and `data.db`.

JUnit 4 tests use temporary SQLite databases. Run `mvn test`; tests cover reward retries, transaction rollback, protocol parsing, and PlaceholderAPI queries.

## Configuration

Runtime config: `plugins/EasyVote/config.yml` (default template: `src/main/resources/config.yml`).

- Votifier TCP port defaults to **10022** (not the standard 8192).
- RSA keys are stored inline in config (`public-key` / `private-key`).
- Reward keys are `votifier.rewards.first-vote` and `votifier.rewards.vote`, across all sites.
- Every vote is queued durably before delivery; offline rewards retry on join. First votes do not also receive ordinary rewards.
- Cumulative milestones configured under `votifier.cumulative.milestones`.
- `votifier.daily-vote-limit` defaults to 1 per player across all sites; 0 means unlimited. Reload applies changes. Limits use server receipt time and local calendar days, persist across restarts, and also apply to test votes. Rejected votes do not affect counts or rewards; pending rewards remain deliverable.
- `debug: true` saves per-vote detail files to `plugins/EasyVote/debug/`.

## Development Conventions

- **Folia compatibility is mandatory.** Reward dispatch must go through `Bukkit.getGlobalRegionScheduler().run()`, never `Bukkit.getScheduler()`. Vote events are fired on the async scheduler.
- **Player names are stored lowercase** in the database (`playerName.toLowerCase(Locale.ROOT)`).
- **Database access** is synchronized on the shared `DatabaseManager` monitor, including vote + pending-reward transactions; all queries use `PreparedStatement`.
- **Optional PlaceholderAPI** uses a `provided` dependency and `softdepend`. Only instantiate the expansion after detecting the API; unregister it on disable. Player queries are case-insensitive and accept offline players.
- **No dependency injection or external frameworks** — plain Java with Bukkit/Paper API.
- **Logging** uses `plugin.getLogger()` with Chinese messages.
- **Config updates** are handled in `EasyVotePlugin.updateConfig()` which adds missing sections on load.

## Commands

| Command | Description | Permission |
|---|---|---|
| `/easyvote reload` | Reload configuration | `easyvote.admin` |
| `/easyvote pubkey` | Display Votifier public key | `easyvote.admin` |
| `/easyvote votestats [player]` | Show global or player vote statistics | `easyvote.admin` |
| `/easyvote votes [player]` | Show player votes and pending rewards; defaults to self | `easyvote.admin` |
| `/easyvote testvote <player> <service>` | Simulate a test vote | `easyvote.admin` |
| `/easyvote clearvotes [player]` | Clear vote data (all if no player) | `easyvote.admin` |

## PlaceholderAPI

Install PlaceholderAPI 2.11.6+ and restart; no eCloud download is needed. The expansion requires initialized vote storage and persists across `/papi reload`.

- `%easyvote_votes%`: context player's total votes across all services.
- `%easyvote_pending%`: pending ordinary/first-vote rewards, excluding milestones.
- `%easyvote_milestone%`: highest milestone already received, or 0.
- `%easyvote_total%`: server-wide votes; no player context required.
- Append `_<player>` to the three player metrics, e.g. `%easyvote_votes_Steve_Test%`, for named offline queries.
- Unknown variables, missing context and database errors return null (unresolved); unknown players return 0. Queries do not create records.
- Placeholder resolution has no `easyvote.admin` check; the consuming plugin controls display permissions.

See README.md for Chinese usage examples and reward-delivery limitations.

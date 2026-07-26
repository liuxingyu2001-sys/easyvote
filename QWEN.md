# EasyVote — QWEN.md

## Project Overview

EasyVote is a lightweight Minecraft Paper/Folia server plugin that implements a Votifier-compatible vote listener. It receives vote notifications from Minecraft server listing websites (via Votifier v1.9 and v2 protocols over TCP), dispatches configurable in-game rewards, and tracks cumulative vote milestones.

- **Language:** Java 21
- **Build:** Maven (shaded fat JAR)
- **Server API:** Paper / Folia 1.21.1+ (`paper-api`)
- **Storage:** SQLite (WAL mode) via `sqlite-jdbc` (loaded as a Paper library, not shaded)
- **JSON parsing:** `json-simple` (shaded into the JAR)
- **Output artifact:** `target/Liu-EasyVote-1.3.jar`
- **All user-facing text (logs, messages, config comments) is in Chinese (中文).**

## Architecture

```
VotifierServer (TCP, port 10022)
  ├─ Votifier v1.9: RSA-decrypted "VOTE ..." text protocol
  ├─ Votifier v2: JSON over TCP (no encryption)
  └─ Custom Votifier format: fixed-width binary-ish layout
        │
        ▼
    VoteEvent (Bukkit custom event, fired on async scheduler)
        │
        ▼
    VoteListener (@EventHandler)
        ├─ mapServiceName() → normalizes service to short key (e.g. "mczfw")
        ├─ VoteHistory.isFirstVoteForService() → first-vote detection
        ├─ Reward dispatch via Bukkit.getGlobalRegionScheduler() (Folia-safe)
        │   └─ console commands with %player%, %service%, %address%, %uuid% variables
        └─ MilestoneTracker.markIfNotReceived() → one-time cumulative rewards
```

### Key Classes (`src/main/java/com/easyvote/`)

| Class | Role |
|---|---|
| `EasyVotePlugin` | Main plugin entry point; lifecycle, config loading, component wiring |
| `VotifierServer` | TCP server accepting Votifier v1/v2/custom connections |
| `RSAKeyManager` | RSA key pair generation, Base64 encoding, vote payload decryption |
| `VoteListener` | Bukkit event handler; reward resolution and dispatch |
| `VoteHistory` | Vote counting, first-vote detection, per-player/service stats (SQLite) |
| `MilestoneTracker` | Atomic milestone marking via `INSERT OR IGNORE` (SQLite) |
| `DatabaseManager` | SQLite connection, schema creation, CSV→SQLite migration |
| `EasyVoteCommand` | `/easyvote` command executor (reload, pubkey, votestats, testvote, clearvotes) |
| `EasyVoteTabCompleter` | Tab completion for `/easyvote` subcommands |
| `VoteEvent` | Custom Bukkit event carrying vote data |

## Building and Running

```bash
# Build shaded JAR
mvn clean package -q

# Build and copy to test servers (project skill)
/mvnp

# Output
target/Liu-EasyVote-1.3.jar
```

Deploy by placing the JAR in a Paper/Folia server's `plugins/` directory. On first run the plugin auto-generates RSA keys and creates `plugins/EasyVote/config.yml` and `data.db`.

**No test framework is configured.** There are no unit or integration tests.

## Configuration

Runtime config: `plugins/EasyVote/config.yml` (default template: `src/main/resources/config.yml`).

- Votifier TCP port defaults to **10022** (not the standard 8192).
- RSA keys are stored inline in config (`public-key` / `private-key`).
- Reward keys map service short-names to command lists; `default` is the fallback.
- First-vote rewards use `first-vote-<service>` key naming.
- Cumulative milestones configured under `votifier.cumulative.milestones`.
- `debug: true` saves per-vote detail files to `plugins/EasyVote/debug/`.

## Development Conventions

- **Folia compatibility is mandatory.** Reward dispatch must go through `Bukkit.getGlobalRegionScheduler().run()`, never `Bukkit.getScheduler()`. Vote events are fired on the async scheduler.
- **Player names are stored lowercase** in the database (`playerName.toLowerCase()`).
- **Database access** uses `synchronized` connection management in `DatabaseManager.getConnection()`; all queries use `PreparedStatement`.
- **Service name mapping** is hardcoded in `VoteListener.mapServiceName()` — new vote sites require code changes there.
- **No dependency injection or external frameworks** — plain Java with Bukkit/Paper API.
- **Logging** uses `plugin.getLogger()` with Chinese messages.
- **Config updates** are handled in `EasyVotePlugin.updateConfig()` which adds missing sections on load.

## Commands

| Command | Description | Permission |
|---|---|---|
| `/easyvote reload` | Reload configuration | `easyvote.admin` |
| `/easyvote pubkey` | Display Votifier public key | `easyvote.admin` |
| `/easyvote votestats` | Show vote statistics | `easyvote.admin` |
| `/easyvote testvote <player> <service>` | Simulate a test vote | `easyvote.admin` |
| `/easyvote clearvotes [player]` | Clear vote data (all if no player) | `easyvote.admin` |

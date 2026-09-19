# EasyVote Plugin - Agent Guide

## Project Overview
Minecraft Paper plugin (Java 21) - Votifier-compatible vote listener with configurable rewards and cumulative milestone tracking.

## Build & Deploy
```bash
# Compile and test (does not deploy)
mvn clean package -q
# Or use the skill shortcut: /mvnp

# Output: target/Liu-EasyVote-<version>.jar (shaded fat JAR)
```

## Architecture

### Entry Point
- `EasyVotePlugin.java` - Main plugin class, manages lifecycle and component initialization

### Core Components
1. **VotifierServer** - TCP server (default port 10022) handling Votifier v1 RSA, legacy plaintext JSON (not standard v2), and custom packets
2. **VoteListener** - Processes vote events, dispatches rewards via console commands
3. **DatabaseManager** - SQLite storage (`data.db`) with WAL mode, auto-migrates from CSV
4. **MilestoneTracker** - Tracks received milestones; marked only after all reward commands succeed
5. **VoteHistory** - Vote counting across all services, atomic vote + pending-reward persistence

6. **RewardDelivery** - Saves per-command progress and preserves failed rewards for retry
7. **EasyVoteExpansion / VotePlaceholders** - Optional PlaceholderAPI expansion and read-only vote queries
8. **MilestoneRewards / MilestoneMenu** - Explicit manual claims, clickable eligibility reminders, paginated GUI and optional CraftEngine item previews

### Data Flow
```
VotifierServer → VoteEvent → VoteListener → reward commands (console)
                  ↓
            DatabaseManager (SQLite)
```

## Configuration
- `src/main/resources/config.yml` - Default config (copied on first run)
- Runtime config: `plugins/EasyVote/config.yml`
- `src/main/resources/cumulative.yml` → `plugins/EasyVote/cumulative.yml`: milestone enable flag, commands/display, GUI title, states and messages at the root level. Reload both files with `/easyvote reload`.
- `CumulativeConfig` migrates legacy `votifier.cumulative` only when the standalone file is absent; save the new file before removing the old section. Existing standalone files take precedence; invalid YAML must not silently activate defaults.
- Reward variables: `%player%`, `%player_name%`, `%service%`, `%address%`, `%uuid%`

## Key Conventions

### Reward System
- `votifier.rewards.first-vote` is used for a player's first vote across all services.
- `votifier.rewards.vote` is used for later votes; first votes do not also receive ordinary rewards.
- Legacy per-service keys are no longer used.
- Every vote and its pending reward are saved in one transaction before scheduling delivery.
- Commands that fail or throw remain retryable; normal retries skip completed commands.
- Milestones never auto-dispatch: votes/join/enable/reload only notify eligible players; GUI clicks explicitly claim and retry failed milestone commands.
- Milestone definitions retain `count` and `commands`, with LiuInvite-style `display` (`material`, `craftengine_model`, `name`, `lore`, optional `slot`) and a chat `description`.
- Claim eligibility and receipt checks share the database lock; existing received milestones remain consumed. Milestone service/address variables use the vote that unlocked that tier.
- `votifier.daily-vote-limit` defaults to 1 per player across all services; 0 disables it.
- Live votes check the daily limit and insert the vote/reward in the same synchronized transaction.
- Daily windows use server JVM timezone and `votes.received_at` (server receipt epoch milliseconds); rejected votes are not stored/rewarded. Existing rows are backfilled from legacy second/millisecond timestamps.

### PlaceholderAPI
- Optional `provided` Maven dependency and `softdepend` in `plugin.yml`; never shade the API.
- Registered only when PlaceholderAPI is enabled and vote storage is ready; unregistered on disable.
- `persist()` keeps the expansion across `/papi reload`.
- `%easyvote_votes%`, `%easyvote_pending%`, `%easyvote_milestone%` query the context player.
- Append `_<player>` to those keys to query a named offline player; `%easyvote_total%` queries all votes.
- Queries use lowercase names with `Locale.ROOT`; split the metric/name separator only once.
- Return `null` for unknown variables, absent player context, or database errors; zero means a successful query with no records.

### Folia Compatibility
Uses `Bukkit.getGlobalRegionScheduler().run()` for reward dispatch (not async scheduler).
Inventory creation/opening, chat item previews, and claim replies use the player's entity scheduler.

### Database
- SQLite with WAL journal mode
- Tables: `votes`, `milestones`, `pending_rewards`, `reward_progress`
- All VoteHistory and MilestoneTracker access is synchronized on the shared DatabaseManager.
- Auto-migrates `votes.csv` and `milestones.csv` on first run

## Commands
- `/easyvote rewards` - Open own milestone claim GUI (`easyvote.rewards`, default true); administrative subcommands still require `easyvote.admin`
- `/easyvote reload` - Reload configuration
- `/easyvote pubkey` - Show Votifier public key
- `/easyvote votestats [player]` - View global or player vote statistics
- `/easyvote votes [player]` - Player vote count and pending rewards; defaults to self
- `/easyvote testvote` - Send test vote
- `/easyvote clearvotes` - Clear vote history

## Gotchas
- Votifier port defaults to 10022 in config.yml (not 8192)
- RSA keys auto-generated on first run, stored in config
- Debug mode saves vote info to `plugins/EasyVote/debug/` directory
- Offline rewards persist until the player joins; a zero join delay runs next tick.
- Retry ordinary rewards on join, new votes, plugin enable, and `/easyvote reload` for online players; only remind for milestones, which require a manual claim/retry.
- Reward commands run through the global scheduler; compatibility also depends on the target command plugin.
- Run `mvn test` for the JUnit 4 / SQLite regression tests.

# EasyVote Plugin - Agent Guide

## Project Overview
Minecraft Paper plugin (Java 21) - Votifier-compatible vote listener with configurable rewards and cumulative milestone tracking.

## Build & Deploy
```bash
# Compile and copy to test servers (default: /home/p and /home/test/plugins)
mvn clean package -q
# Or use the skill shortcut: /mvnp

# Output: target/Liu-EasyVote-1.3.jar (shaded fat JAR)
```

## Architecture

### Entry Point
- `EasyVotePlugin.java` - Main plugin class, manages lifecycle and component initialization

### Core Components
1. **VotifierServer** - TCP server (default port 10022) handling Votifier v1.9 and v2 protocols
2. **VoteListener** - Processes vote events, dispatches rewards via console commands
3. **DatabaseManager** - SQLite storage (`data.db`) with WAL mode, auto-migrates from CSV
4. **MilestoneTracker** - Tracks cumulative vote milestones (one-time rewards per threshold)
5. **VoteHistory** - Vote counting and first-vote detection per service

### Data Flow
```
VotifierServer → VoteEvent → VoteListener → reward commands (console)
                  ↓
            DatabaseManager (SQLite)
```

## Configuration
- `src/main/resources/config.yml` - Default config (copied on first run)
- Runtime config: `plugins/EasyVote/config.yml`
- Reward variables: `%player%`, `%player_name%`, `%service%`, `%address%`, `%uuid%`

## Key Conventions

### Reward System
- Keys map service names to command lists (e.g., `mczfw`, `first-vote-mczfw`)
- `default` key is fallback for unknown services
- First-vote rewards use `first-vote-<service>` naming

### Service Name Mapping (VoteListener.java:181-195)
```java
"mczfw" ← contains "mczfw"
"wdsjfwq" ← contains "服务器站" or "wdsjfwq"
// Otherwise: split on "." and use first part
```

### Folia Compatibility
Uses `Bukkit.getGlobalRegionScheduler().run()` for reward dispatch (not async scheduler).

### Database
- SQLite with WAL journal mode
- Tables: `votes` (id, player_name, service_name, address, timestamp), `milestones` (player_name, count, timestamp)
- Auto-migrates `votes.csv` and `milestones.csv` on first run

## Commands
- `/easyvote reload` - Reload configuration
- `/easyvote pubkey` - Show Votifier public key
- `/easyvote votestats` - View vote statistics
- `/easyvote testvote` - Send test vote
- `/easyvote clearvotes` - Clear vote history

## Gotchas
- Votifier port defaults to 10022 in config.yml (not 8192)
- RSA keys auto-generated on first run, stored in config
- Debug mode saves vote info to `plugins/EasyVote/debug/` directory
- Player must be online to receive rewards (silently skipped otherwise)

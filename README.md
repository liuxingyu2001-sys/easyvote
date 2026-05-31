# EasyVote

[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/projects/jdk/21/)
[![Spigot](https://img.shields.io/badge/Spigot-1.21-yellow.svg)](https://www.spigotmc.org/)

**EasyVote** is a lightweight Spigot/Paper plugin that provides a built-in Votifier-compatible vote listener with configurable per-service rewards. It supports Votifier v1 (RSA-encrypted), Votifier v2 (JSON), and legacy custom vote formats — all in one simple plugin.

---

## Table of Contents

- [Features](#features)
- [Requirements](#requirements)
- [Installation](#installation)
- [Configuration](#configuration)
- [Votifier (Vote System)](#votifier-vote-system)
  - [How It Works](#how-it-works)
  - [Vote Rewards Configuration](#vote-rewards-configuration)
  - [Service Name Mapping](#service-name-mapping)
  - [Supported Votifier Protocols](#supported-votifier-protocols)
  - [Debug Mode](#debug-mode)
- [In-Game Commands](#in-game-commands)
- [Permissions](#permissions)
- [Building from Source](#building-from-source)
- [File Structure](#file-structure)
- [FAQ](#faq)
- [License](#license)

---

## Features

| Feature | Description |
|---------|-------------|
| **Votifier** | Built-in Votifier v1 & v2 listener with RSA-2048 decryption |
| **Vote Rewards** | Per-service first-vote & recurring rewards with variable substitution |
| **Vote History** | Persistent CSV-backed vote log and in-memory statistics |
| **Tab Completion** | Full tab completion for all subcommands |
| **Reload Safe** | `/easyvote reload` regenerates config if deleted |

## Requirements

| Component | Version / Notes |
|-----------|----------------|
| **Java** | 21+ |
| **Server** | Spigot / Paper 1.21+ |

## Installation

1. Download `EasyVote-1.3.jar` from [Releases](https://github.com/liuxingyu2001-sys/apiCommand/releases).
2. Place the jar into your server's `plugins/` directory.
3. Restart the server.
4. Edit `plugins/EasyVote/config.yml` to configure vote rewards.
5. Copy the Votifier public key (printed in console on startup) to your voting sites.
6. Run `/easyvote reload` to apply config changes without restarting.

> **Note:** On first startup, the plugin auto-generates an RSA-2048 key pair. The public key is printed in the console in PEM format.

## Configuration

All settings live in `plugins/EasyVote/config.yml`.

```yaml
# Votifier 投票功能配置
votifier:
  enabled: true               # Enable built-in Votifier server
  host: "0.0.0.0"             # Listen address
  port: 10022                 # Votifier port (set this on voting sites)
  max-threads: 10             # Thread pool size for vote handling
  verbose: false              # Log detailed vote info to console
  public-key: ""              # Auto-generated on first run — DO NOT edit manually
  private-key: ""             # Auto-generated on first run — DO NOT edit manually
  rewards:
    default:                  # Fallback rewards for any unrecognised service
      - "money give %player% 2000 -s"
      - "msg %player% &a感谢你为服务器投票！"
    mczfw:                    # Regular rewards for mczfw.com
      - "money give %player% 2000 -s"
      - "msg %player% &a感谢在找服网投票！"
    first-vote-mczfw:         # FIRST-TIME rewards for mczfw.com
      - "msg %player% &6&l首次在找服网投票！感谢你的投票！"
      - "money give %player% 3000"
      - "give %player% diamond 3"

debug: false                  # Write per-vote debug files to plugins/EasyVote/debug/
```

### Reward Variables

Use these placeholders in your reward commands:

| Variable | Description |
|----------|-------------|
| `%player%` | Player name |
| `%player_name%` | Same as `%player%` |
| `%service%` | Voting site / service name |
| `%address%` | Voter's IP address |
| `%uuid%` | Player's UUID |

---

## Votifier (Vote System)

EasyVote includes its own Votifier server — you do **not** need a separate Votifier plugin. Voting sites send encrypted vote notifications to the Votifier port, and EasyVote decrypts them using the RSA key pair, then dispatches rewards.

### How It Works

```
Voting Site ──(encrypted vote)──▶ EasyVote Votifier Server
                                        │
                                        ▼
                              RSA Decryption (2048-bit)
                                        │
                                        ▼
                              VoteEvent dispatched
                                        │
                                        ▼
                              VoteListener checks config
                                        │
                               ┌────────┴────────┐
                               ▼                 ▼
                        first-vote-<site>?   <site> rewards
                               │                 │
                               └────────┬────────┘
                                        ▼
                              Commands executed as console
                              Vote recorded to votes.csv
```

### Vote Rewards Configuration

Rewards are defined per voting site under `votifier.rewards` in `config.yml`. Three priority levels:

```yaml
votifier:
  rewards:
    # Priority 1 — First-vote bonus (only fires the FIRST time a player votes from this site)
    first-vote-mczfw:
      - "msg %player% &6&l首次在找服网投票！"
      - "money give %player% 3000"

    # Priority 2 — Regular rewards for this specific site
    mczfw:
      - "money give %player% 2000 -s"

    # Priority 3 — Fallback (fires when no site-specific config matches)
    default:
      - "money give %player% 2000 -s"
      - "msg %player% &a感谢投票！"
```

> **Key naming rules:** Do NOT include dots (`.`) in reward keys. Use the short form — e.g. `mczfw` (not `mczfw.com`).

### Service Name Mapping

When a vote arrives, the plugin maps the incoming service name to a config key:

| Incoming Service Name | Maps To | Reason |
|-----------------------|---------|--------|
| `list.mczfw.com` | `mczfw` | Contains "mczfw" substring |
| `www.mczfw.com` | `mczfw` | Contains "mczfw" substring |
| `wdsjfwq.com` | `wdsjfwq` | Contains "wdsjfwq" substring |
| Any domain with `.` | First segment before `.` | Fallback rule |
| Anything else | As-is (lowercased) | Direct match |

### Supported Votifier Protocols

| Protocol | Format | Description |
|----------|--------|-------------|
| Votifier v1 | `VOTE <service> <player> <ip> <timestamp>` | RSA-2048 OAEP/SHA-256 (fallback to PKCS1) |
| Votifier v2 | JSON `{"serviceName":"...","username":"...","address":"...","timestamp":...}` | Plain JSON — no encryption needed |
| Custom format | Binary `VOTIFIER<content>` with 32-byte fields | Legacy non-standard format |

### Debug Mode

Set `debug: true` in config to write per-vote debug files under `plugins/EasyVote/debug/`. Each file records:

- Player name, service name, source IP
- Timestamp (human-readable + epoch)
- Whether it was a first vote
- Cumulative vote counts

---

## In-Game Commands

All commands require the `easyvote.admin` permission (default: OP).

| Command | Description |
|---------|-------------|
| `/easyvote reload` | Reload config.yml and regenerates it if deleted |
| `/easyvote pubkey` | Display the Votifier RSA public key (PEM format) |
| `/easyvote votestats` | Show total vote count |
| `/easyvote testvote <player> <service>` | Simulate a vote to test reward configuration |
| `/easyvote clearvotes [player]` | Clear vote data — all if no player specified |

---

## Permissions

| Permission | Default | Description |
|------------|---------|-------------|
| `easyvote.admin` | OP | Access to all `/easyvote` subcommands |

---

## Building from Source

**Prerequisites:** JDK 21, Maven 3.8+

```bash
git clone https://github.com/liuxingyu2001-sys/apiCommand.git
cd apiCommand
git checkout votifier-only
mvn clean package
```

The shaded jar will be at `target/EasyVote-1.3.jar`.

### Dependencies

| Dependency | Scope | Purpose |
|------------|-------|---------|
| `spigot-api` 1.21-R0.1 | provided | Bukkit/Spigot API |
| `json-simple` 1.1.1 | shaded | JSON parsing in Votifier v2 handler |

---

## File Structure

```
EasyVote/
├── pom.xml                              # Maven build descriptor
├── README.md
└── src/main/
    ├── java/com/easyvote/
    │   ├── EasyVotePlugin.java          # Plugin entry point, config, lifecycle
    │   ├── EasyVoteCommand.java         # /easyvote command executor
    │   ├── EasyVoteTabCompleter.java    # Tab completion provider
    │   ├── VotifierServer.java          # Votifier TCP listener (v1/v2/custom)
    │   ├── RSAKeyManager.java           # RSA-2048 key generation & decryption
    │   ├── VoteEvent.java               # Bukkit event for incoming votes
    │   ├── VoteListener.java            # Event handler — dispatches rewards
    │   └── VoteHistory.java             # CSV-backed vote record storage & stats
    └── resources/
        ├── config.yml                   # Default configuration
        └── plugin.yml                   # Plugin metadata
```

---

## FAQ

**Q: Does this replace VotePlugin / NuVotifier?**
A: Yes — EasyVote has a built-in Votifier server. You can remove standalone Votifier plugins.

**Q: I deleted config.yml and `/easyvote reload` doesn't work?**
A: It does work — the reload command calls `saveDefaultConfig()` first, so the config is regenerated from the jar's default before reloading.

**Q: How do I see my Votifier public key after startup?**
A: Run `/easyvote pubkey` in-game. The key is also printed to console on startup.

---

## License

MIT

---

*Author: **liuxingyu** &nbsp;|&nbsp; Version: **1.3** &nbsp;|&nbsp; Minecraft **1.21+***

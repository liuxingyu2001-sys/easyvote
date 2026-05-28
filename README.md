# ApiCommand

[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/projects/jdk/21/)
[![Spigot](https://img.shields.io/badge/Spigot-1.21-yellow.svg)](https://www.spigotmc.org/)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

**ApiCommand** is a Spigot/Paper plugin that exposes a RESTful HTTP API for executing Minecraft server commands remotely. It also includes a built-in Votifier-compatible vote listener and SkinsRestorer skin management integration — giving you a single, unified plugin for external control, vote rewards, and player skins.

---

## Table of Contents

- [Features](#features)
- [Requirements](#requirements)
- [Installation](#installation)
- [Configuration](#configuration)
- [API Endpoints](#api-endpoints)
  - [Authentication](#authentication)
  - [POST /api/command](#post-apicommand)
  - [POST /api/skin](#post-apiskin)
  - [POST /api/skin/clear](#post-apiskinclear)
  - [GET /api/health](#get-apihealth)
  - [GET /api/stats](#get-apistats)
  - [GET /api/vote/stats](#get-apivotestats)
  - [GET /api/vote/history](#get-apivotehistory)
- [Response Format](#response-format)
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

| Category | Feature |
|----------|---------|
| **HTTP API** | Execute any server command via REST (POST /api/command) |
| **Authentication** | Bearer token auth — configurable API key in config.yml |
| **Rate Limiting** | Per-IP request throttling with configurable limits |
| **Skin API** | Set / clear player skins via HTTP (requires SkinsRestorer) |
| **Health Check** | GET /api/health — liveness probe for monitoring |
| **Statistics** | GET /api/stats, /api/vote/stats, /api/vote/history |
| **Votifier** | Built-in Votifier v1 & v2 listener with RSA-2048 decryption |
| **Vote Rewards** | Per-service first-vote & recurring rewards with variable substitution |
| **Vote History** | Persistent CSV-backed vote log and in-memory statistics |
| **Reasonable Soft-Depends** | SkinsRestorer is optional — everything else works without it |
| **Tab Completion** | Full tab completion for all subcommands |

## Requirements

| Component | Version / Notes |
|-----------|----------------|
| **Java** | 21+ |
| **Server** | Spigot / Paper 1.21+ (Spigot API 1.21-R0.1) |
| **SkinsRestorer** | Optional — v15.8+ (only needed for /api/skin endpoints) |

## Installation

1. Download the latest `ApiCommand-1.2.jar` from [Releases](https://github.com/your-repo/apicommand/releases).
2. Place the jar into your server's `plugins/` directory.
3. Restart the server (or run `plugman load ApiCommand` if you use PlugMan).
4. Edit `plugins/ApiCommand/config.yml` — **change the default API key immediately**.
5. Run `/apicommand reload` to apply changes without restarting.

> **Note:** On first startup, the plugin auto-generates a random API key and RSA key pair for Votifier. The RSA public key is printed in the console — copy it to your vote listing sites.

## Configuration

All settings live in `plugins/ApiCommand/config.yml`.

```yaml
# ═══ API Server ═══
api-key: "aabbcc"           # CHANGE THIS — use a strong random key
api-port: 10278             # HTTP listen port
api-enabled: true           # Set to false to disable the HTTP API entirely
max-connections: 10         # Max concurrent HTTP connections
request-timeout: 15000      # Command execution timeout in milliseconds
enable-rate-limit: true     # Enable per-IP rate limiting
max-requests-per-minute: 600

# ═══ Votifier ═══
votifier:
  enabled: true             # Enable built-in Votifier server
  host: "0.0.0.0"           # Listen address
  port: 10022               # Votifier port (set this on voting sites)
  max-threads: 10           # Thread pool size for vote handling
  verbose: false            # Log detailed vote info to console
  public-key: ""            # Auto-generated on first run — DO NOT edit manually
  private-key: ""           # Auto-generated on first run — DO NOT edit manually
  rewards:
    default:                # Fallback rewards for any unrecognised service
      - "money give %player% 2000 -s"
      - "msg %player% &a感谢你为服务器投票！"
    mczfw:                  # Regular rewards for mczfw.com
      - "money give %player% 2000 -s"
    first-vote-mczfw:       # FIRST-TIME rewards for mczfw.com
      - "serveritem give xcg 1 %player%"
      - "msg %player% &6&l首次投票奖励！"

debug: false                # Write per-vote debug files to plugins/ApiCommand/debug/
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

## API Endpoints

### Authentication

All mutating endpoints require an `Authorization` header:

```
Authorization: Bearer <your-api-key>
```

The API key is set in `config.yml` under `api-key`.

### POST /api/command

Execute a server command as console.

```
POST /api/command
Authorization: Bearer <api-key>
Content-Type: text/plain

say Hello, world!
```

**cURL example:**
```bash
curl -X POST http://localhost:10278/api/command \
  -H "Authorization: Bearer aabbcc" \
  -H "Content-Type: text/plain" \
  -d "say Hello"
```

**JavaScript fetch example:**
```js
fetch('http://localhost:10278/api/command', {
    method: 'POST',
    headers: {
        'Authorization': 'Bearer aabbcc',
        'Content-Type': 'text/plain'
    },
    body: 'say Hello'
})
```

### POST /api/skin

Set a player's skin via SkinsRestorer. The player must be online.

```
POST /api/skin
Authorization: Bearer <api-key>
Content-Type: application/json

{
    "player": "Steve",
    "skinUrl": "https://example.com/skin.png",
    "skinType": "classic"
}
```

| Field | Type | Required | Notes |
|-------|------|----------|-------|
| `player` | string | Yes | Player name (must be online) |
| `skinUrl` | string | Yes | Direct URL to a skin texture PNG |
| `skinType` | string | No | `classic` (default) or `slim` |

### POST /api/skin/clear

Clear a player's skin (reset to default). Player must be online.

```
POST /api/skin/clear
Authorization: Bearer <api-key>
Content-Type: application/json

{ "player": "Steve" }
```

### GET /api/health

Health-check endpoint — returns server uptime info. No auth required.

```json
{ "status": "healthy", "uptime": "1712345678" }
```

### GET /api/stats

API usage statistics. No auth required.

```json
{
    "totalRequests": 42,
    "failedRequests": 2,
    "activeConnections": 1,
    "totalExecutionTime": 5230,
    "averageExecutionTime": 124
}
```

### GET /api/vote/stats

Aggregated vote statistics by service & player. No auth required.

### GET /api/vote/history

Most recent 100 vote records. No auth required.

---

## Response Format

All responses are JSON.

**Success:**
```json
{ "success": true, "message": "Command executed" }
```

**Error:**
```json
{ "error": "Unauthorized", "message": "Invalid or missing API key" }
```

### HTTP Status Codes

| Code | Meaning |
|------|---------|
| 200 | Success |
| 400 | Bad request — missing or invalid parameters |
| 401 | Unauthorized — invalid or missing API key |
| 405 | Method not allowed — use the correct HTTP method |
| 429 | Rate limited — too many requests from this IP |
| 500 | Internal server error — command execution failed |
| 503 | Service unavailable — optional dependency not installed (e.g. SkinsRestorer) |

---

## Votifier (Vote System)

ApiCommand includes its own Votifier server — you do **not** need a separate Votifier plugin. Voting sites send encrypted vote notifications to the Votifier port, and ApiCommand decrypts them using the RSA key pair, then dispatches rewards you've configured.

### How It Works

```
Voting Site ──(encrypted vote)──▶ ApiCommand Votifier Server (port 10022)
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
      - "serveritem give xcg 1 %player%"
      - "msg %player% &6&l首次在找服网投票！"

    # Priority 2 — Regular rewards for this specific site
    mczfw:
      - "money give %player% 2000 -s"
      - "msg %player% &a感谢投票！获得 2000 金币"

    # Priority 3 — Fallback (fires when no site-specific config matches)
    default:
      - "money give %player% 2000 -s"
      - "msg %player% &a感谢投票！"
```

> **Key naming rules:** Do NOT include dots (`.`) in reward keys. Use the short form — e.g. `mczfw` (not `mczfw.com`), `wdsjfwq` (not `我的世界找服网`).

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

Set `debug: true` in config to write per-vote debug files under `plugins/ApiCommand/debug/`. Each file records:

- Player name, service name, source IP
- Timestamp (human-readable + epoch)
- Whether it was a first vote
- Cumulative vote counts

---

## In-Game Commands

All commands require the `apicommand.admin` permission (default: OP).

| Command | Description |
|---------|-------------|
| `/apicommand reload` | Reload config.yml without restarting |
| `/apicommand key` | Display the current API key |
| `/apicommand pubkey` | Display the Votifier RSA public key (PEM format) |
| `/apicommand status` | Show API server status and settings |
| `/apicommand stats` | Quick API statistics overview |
| `/apicommand votestats` | Show total vote count and per-service breakdown |
| `/apicommand testvote <player> <service>` | Simulate a vote to test reward configuration |
| `/apicommand setskin <player> <url> [type]` | Set a player's skin (classic or slim) |
| `/apicommand clearskin <player>` | Reset a player's skin to default |
| `/apicommand clearvotes [player]` | Clear vote data — all if no player specified |

---

## Permissions

| Permission | Default | Description |
|------------|---------|-------------|
| `apicommand.admin` | OP | Access to all `/apicommand` subcommands |

---

## Building from Source

**Prerequisites:** JDK 21, Maven 3.8+

```bash
git clone https://github.com/your-repo/apicommand.git
cd apicommand
mvn clean package
```

The shaded jar will be at `target/ApiCommand-1.2.jar`.

The build runs these Maven plugins:
- `maven-compiler-plugin` — Java 21 target
- `maven-shade-plugin` — produces a fat jar (no external dependencies needed at runtime)

### Dependencies

| Dependency | Scope | Purpose |
|------------|-------|---------|
| `spigot-api` 1.21-R0.1 | provided | Bukkit/Spigot API |
| `skinsrestorer-api` 15.8.2 | provided | Skin management (optional) |
| `json-simple` 1.1.1 | shaded | JSON parsing in Votifier v2 handler |

---

## File Structure

```
ApiCommand/
├── pom.xml                              # Maven build descriptor
├── README.md
├── plugins/ApiCommand/debug/            # Debug vote logs (when debug:true)
└── src/main/
    ├── java/com/apicommand/
    │   ├── ApiCommandPlugin.java        # Plugin entry point, config, lifecycle
    │   ├── ApiCommand.java              # /apicommand command executor
    │   ├── ApiCommandTabCompleter.java   # Tab completion provider
    │   ├── ApiServer.java               # HTTP API server (all endpoints)
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
A: Yes — ApiCommand has a built-in Votifier server. You can remove standalone Votifier plugins.

**Q: SkinsRestorer isn't working — what's wrong?**
A: In Velocity/BungeeCord proxy setups, the backend server needs database access for SkinsRestorer. Either configure MySQL in SkinsRestorer's config, or set `server.proxyMode.detection: DISABLED`. The rest of ApiCommand works fine without SkinsRestorer.

**Q: Can I change the API key without restarting?**
A: Yes — edit `config.yml`, then run `/apicommand reload`.

**Q: How do I see my Votifier public key after startup?**
A: Run `/apicommand pubkey` in-game. The key is also printed to console on startup.

**Q: What happens if the API key is still the default?**
A: On first run, the plugin auto-generates a random UUID as the API key. However, if your config predates this behavior and still has the old default `aabbcc`, change it immediately.

---

## License

MIT — see [LICENSE](LICENSE) for details.

---

*Author: **liuxingyu** &nbsp;|&nbsp; Version: **1.2** &nbsp;|&nbsp; Minecraft **1.21+***

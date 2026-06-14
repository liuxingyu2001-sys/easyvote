# EasyVote

轻量级 Votifier 投票监听插件，支持 Folia，SQLite 持久化。

## 功能

- **Votifier 监听**：接收 Votifier 投票通知
- **自定义奖励**：可配置投票奖励（物品、金币、指令）
- **里程碑奖励**：累计投票达到指定次数触发额外奖励
- **SQLite 存储**：投票记录持久化，支持离线奖励补发
- **Folia 兼容**：完全支持 Folia 调度器

## 命令

| 命令 | 说明 | 权限 |
|------|------|------|
| `/easyvote reload` | 重载配置 | `easyvote.admin` |
| `/easyvote pubkey` | 查看 RSA 公钥 | `easyvote.admin` |
| `/easyvote votestats [玩家]` | 查看投票统计 | `easyvote.admin` |
| `/easyvote testvote [玩家]` | 模拟投票测试 | `easyvote.admin` |
| `/easyvote clearvotes <玩家>` | 清除投票记录 | `easyvote.admin` |

## 依赖

- **Votifier**（服务端需安装 Votifier 插件）
- **可选**: Vault（金币奖励）

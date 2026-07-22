# EasyVote

轻量级 Votifier 兼容投票监听插件，支持自定义投票奖励与累计里程碑追踪。

## 功能特性

- 兼容 Votifier v1.9 和 v2 协议
- 支持 Folia（异步调度安全）
- 可配置的投票奖励（按投票网站区分）
- 首次投票专属奖励
- 累计投票里程碑奖励（每位玩家每里程碑仅触发一次）
- SQLite 数据存储，自动从旧版 CSV 迁移
- RSA 密钥对首次运行自动生成
- 调试模式（保存投票详情到文件）

## 环境要求

- Paper / Folia 1.21.1+
- Java 21+

## 安装

1. 下载 `Liu-EasyVote-1.3.jar`
2. 放入服务器 `plugins/` 目录
3. 重启服务器
4. 将控制台输出的 Votifier 公钥复制到投票网站

## 命令

| 命令 | 说明 | 权限 |
|------|------|------|
| `/easyvote reload` | 重新加载配置 | `easyvote.admin` |
| `/easyvote pubkey` | 显示 Votifier 公钥 | `easyvote.admin` |
| `/easyvote votestats` | 查看投票统计 | `easyvote.admin` |
| `/easyvote testvote <玩家> <网站>` | 模拟测试投票 | `easyvote.admin` |
| `/easyvote clearvotes [玩家]` | 清除投票数据（不指定则清除全部） | `easyvote.admin` |

## 配置

配置文件位于 `plugins/EasyVote/config.yml`。

### 奖励变量

| 变量 | 说明 |
|------|------|
| `%player%` | 玩家名 |
| `%player_name%` | 玩家名（同 `%player%`） |
| `%service%` | 投票网站名称 |
| `%address%` | 投票来源 IP |
| `%uuid%` | 玩家 UUID |

### 奖励键名规则

- 键名对应投票网站，使用简短名称（如 `mczfw` 代表 `mczfw.com`）
- 首次投票奖励使用 `first-vote-<网站名>` 命名
- `default` 为未知网站的兜底奖励

### 累计里程碑

在 `votifier.cumulative.milestones` 中配置，每个里程碑触发一次性额外奖励：

```yaml
cumulative:
  enabled: true
  milestones:
    - count: 10
      commands:
        - "give %player% diamond 10"
    - count: 50
      commands:
        - "give %player% netherite_ingot 5"
```

## 构建

```bash
mvn clean package
```

输出：`target/Liu-EasyVote-1.3.jar`

## 许可

作者：liuxingyu2001

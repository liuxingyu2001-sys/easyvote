# EasyVote

轻量级 Votifier 兼容投票监听插件，支持自定义投票奖励与累计里程碑追踪。

## 功能特性

- 兼容 Votifier v1 RSA 协议（1.9 握手）；现有明文 JSON 接口不等同于标准 Votifier v2
- 支持 Folia（异步调度安全）
- 可配置的首次与普通投票奖励（所有网站合并计数）
- 首次投票专属奖励
- 累计投票里程碑奖励（每位玩家每里程碑仅触发一次）
- SQLite 数据存储，自动从旧版 CSV 迁移
- RSA 密钥对首次运行自动生成
- 调试模式（保存投票详情到文件）

## 环境要求

- Paper / Folia 1.21.1+
- Java 21+

## 安装

1. 下载 `Liu-EasyVote-1.3.2.jar`
2. 放入服务器 `plugins/` 目录
3. 重启服务器
4. 将控制台输出的 Votifier 公钥复制到投票网站

## 命令

| 命令 | 说明 | 权限 |
|------|------|------|
| `/easyvote reload` | 重新加载配置 | `easyvote.admin` |
| `/easyvote pubkey` | 显示 Votifier 公钥 | `easyvote.admin` |
| `/easyvote votestats [玩家名]` | 查看全服或指定玩家的投票统计 | `easyvote.admin` |
| `/easyvote votes [玩家名]` | 查询累计票数及待发投票奖励数；玩家不填姓名查自己，控制台需填写 | `easyvote.admin` |
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

- `votifier.rewards.first-vote`：玩家全服第一次投票的奖励。
- `votifier.rewards.vote`：之后每次投票的奖励；首次不会同时执行普通奖励。
- 所有网站合并计数，玩家名不区分大小写，离线玩家也可查询。
- 旧的 `mczfw`、`first-vote-mczfw`、`default` 等网站键名不再参与发奖，请改为上述两个键名。

### 待发奖励与重试

投票记录和待发奖励在同一数据库事务中保存，在线玩家也先入队。离线奖励会在上线后延迟发放（`votifier.join-reward-delay-seconds`，默认 5 秒；0 表示下一 tick）。

奖励命令返回失败、抛出异常或玩家下线时保留未完成进度。玩家再次上线、再次投票、插件启用或执行 `/easyvote reload` 时，会尝试给在线玩家补发。里程碑全部命令成功后才标记已领取；查询的待发条数不包括里程碑。

每条成功命令都保存进度，正常重试跳过已成功命令。可修正尚未执行的命令后 reload；部分发放的奖励若修改了已执行命令的前缀或顺序，将继续使用原命令快照以避免错发。不要在部分发放期间重排命令。

命令返回成功仅表示命令接受执行，其他插件静默拒绝奖励仍需查其日志。服务器恰在命令执行后、进度保存前崩溃时，重试可能重复该条命令；外部奖励命令与 SQLite 无法组成同一事务。旧版本已删除的待发记录或已误标记的里程碑无法自动恢复。

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

输出：`target/Liu-EasyVote-1.3.2.jar`

## 许可

作者：liuxingyu2001

# EasyVote

轻量级 Votifier 兼容投票监听插件，支持自定义投票奖励与累计里程碑追踪。

> **前置插件**：必须安装 **LiuLib 1.0.5+**。EasyVote 的 SQLite/MySQL 连接池、JDBC 驱动、调度器、物品规格解析与 CraftEngine 物品构建由 LiuLib 提供；缺少 LiuLib 时服务端会按 `depend` 拒绝加载本插件。可选安装 **liuitemlib** 后可使用 `lib:物品库ID` 引用（已列入 `softdepend`）。

## 功能特性

- 兼容 Votifier v1 RSA 协议（1.9 握手）；现有明文 JSON 接口不等同于标准 Votifier v2
- 支持 Folia（异步调度安全）
- 可配置的首次与普通投票奖励（所有网站合并计数）
- 首次投票专属奖励
- 累计投票里程碑手动领取，达标发送可点击提醒，支持 CraftEngine 物品预览
- SQLite（单服，默认）/ MySQL（多服共享）数据存储，自动从旧版 CSV 迁移
- 多服并发安全：每日上限、待发奖励租约、里程碑领取均使用数据库条件写入，同一条投票只会被一个子服处理
- RSA 密钥对首次运行自动生成
- 调试模式（保存投票详情到文件）
- 可选 PlaceholderAPI 投票查询变量，支持当前玩家和指定离线玩家

## 环境要求

- Paper / Folia 1.21.1+（本版本使用 Paper 1.21.11 API 编译）
- Java 21+
- **LiuLib 1.0.5+**（硬依赖）

## 安装

1. 下载 `Liu-EasyVote-1.4.1.jar` 与 `LiuLib-1.0.5.jar`
2. 放入服务器 `plugins/` 目录
3. 重启服务器
4. 将控制台输出的 Votifier 公钥复制到投票网站
5. 多服部署时把所有子服的 `database` 指向同一个 MySQL 库，并保持相同的 `table-prefix`

## 命令

| 命令 | 说明 | 权限 |
|------|------|------|
| `/easyvote rewards` | 打开自己的里程碑奖励领取界面 | `easyvote.rewards`（默认所有玩家） |
| `/easyvote reload` | 重新加载配置 | `easyvote.admin` |
| `/easyvote pubkey` | 显示 Votifier 公钥 | `easyvote.admin` |
| `/easyvote votestats [玩家名]` | 查看全服或指定玩家的投票统计 | `easyvote.admin` |
| `/easyvote votes [玩家名]` | 查询累计票数及待发投票奖励数；玩家不填姓名查自己，控制台需填写 | `easyvote.admin` |
| `/easyvote testvote <玩家> <网站>` | 模拟测试投票 | `easyvote.admin` |
| `/easyvote clearvotes [玩家]` | 清除投票数据（不指定则清除全部） | `easyvote.admin` |

## PlaceholderAPI 查询变量

安装 PlaceholderAPI 2.11.6+ 后重启服务器，EasyVote 会自动注册内置扩展，无需从 eCloud 下载。未安装 PlaceholderAPI 不影响投票与发奖。需要启用 Votifier 且投票数据库初始化成功；`/papi reload` 后扩展仍保留。

可在支持 PlaceholderAPI 的菜单、计分板、聊天等插件中使用：

| 变量 | 含义 |
|------|------|
| `%easyvote_votes%` | 当前玩家累计投票次数，合并所有网站 |
| `%easyvote_pending%` | 当前玩家待发投票奖励条数，不含里程碑 |
| `%easyvote_milestone%` | 当前玩家已领取的最高里程碑次数，未领取为 0 |
| `%easyvote_total%` | 全服累计投票次数，无需玩家上下文 |
| `%easyvote_votes_Steve%` | 指定玩家 Steve 的累计投票次数 |
| `%easyvote_pending_Steve%` | 指定玩家 Steve 的待发投票奖励条数 |
| `%easyvote_milestone_Steve%` | 指定玩家 Steve 已领取的最高里程碑次数 |

将 `Steve` 替换为实际玩家名，支持离线玩家、下划线姓名和大小写不敏感查询。不存在的玩家返回 `0`，查询不会创建投票记录。缺少玩家上下文、未知变量或数据库错误时保留原变量，不会伪装成 0 次投票。

验证示例（需具备 PlaceholderAPI 的解析命令权限）：

```text
/papi parse me %easyvote_votes%
/papi parse me %easyvote_pending%
/papi parse me %easyvote_votes_Steve%
/papi parse me %easyvote_total%
```

这些是给其他插件使用的查询变量，与下文奖励命令中的 `%player%` 等内置替换变量分别处理。查询变量不要求玩家拥有 `easyvote.admin`；展示及解析命令权限由使用它的插件控制。

## 配置

主配置位于 `plugins/EasyVote/config.yml`；累计里程碑奖励、领取界面与消息单独放在 `plugins/EasyVote/cumulative.yml`。`/easyvote reload` 会同时重载两个文件。

### 数据库

```yaml
database:
  type: sqlite          # sqlite = 单服本地文件；mysql = 多服共享
  table-prefix: ""      # 多服共享 MySQL 时建议统一前缀，例如 easyvote_
  sqlite:
    file: data.db
  mysql:
    host: 127.0.0.1
    port: 3306
    database: easyvote
    username: root
    password: ""
    use-ssl: false
    pool-size: 8
```

- 修改 `database.type` 或连接参数后必须**重启服务端**；`/easyvote reload` 只重载 YAML 配置
- MySQL 模式下投票记录与待发奖励在同一数据库事务中写入；多个子服同时收到同一投票时，每日上限与待发奖励租约保证只有一条被处理
- 里程碑标记使用独立的原子条件写入，与投票奖励事务解耦
- 待发奖励带 `claimed_by` / `claimed_at` 租约（5 分钟）；子服崩溃后租约会过期并被其它子服重新领取
- 里程碑在命令开始执行前先原子标记，命令失败会回滚标记并保留可重试进度
- 所有子服需系统时间同步；投票日期以接收服务器的本地时区自然日计算

### 每日投票上限

```yaml
votifier:
  daily-vote-limit: 1
```

默认每位玩家每天最多计入 **1 次**投票，所有网站合并计算，玩家名不区分大小写。设为 `0` 表示不限；负数按默认值 1 处理。旧配置会自动补入此项，修改后执行 `/easyvote reload` 生效。

按服务器 JVM 本地时区的自然日统计，每天零点恢复额度，新投票使用服务器接收时间，不信任网站提供的时间戳。次数持久化到数据库，重启或领取奖励不会恢复额度；升级时旧记录使用已有时间戳（兼容秒和毫秒）推算日期。

超限投票会记录提示日志，但不增加累计票数、不触发里程碑、不生成奖励，也不会推迟到次日发放。已入队的离线奖励仍正常补发。`/easyvote testvote` 同样受限；`/easyvote clearvotes` 删除投票记录时也会清除对应每日计数。

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

奖励命令返回失败、抛出异常或玩家下线时保留未完成进度。玩家再次上线、再次投票、插件启用或执行 `/easyvote reload` 时，会尝试给在线玩家补发首次/普通投票奖励，并提醒可领取的里程碑。里程碑只在玩家点击领取时执行，失败后再次点击续发，全部命令成功后才标记已领取；查询的待发条数不包括里程碑。

每条成功命令都保存进度，正常重试跳过已成功命令。可修正尚未执行的命令后 reload；部分发放的奖励若修改了已执行命令的前缀或顺序，将继续使用原命令快照以避免错发。不要在部分发放期间重排命令。

命令返回成功仅表示命令接受执行，其他插件静默拒绝奖励仍需查其日志。服务器恰在命令执行后、进度保存前崩溃时，重试可能重复该条命令；外部奖励命令与 SQLite/MySQL 无法组成跨系统事务。旧版本已删除的待发记录或已误标记的里程碑无法自动恢复。

### 累计里程碑

在独立文件 `cumulative.yml` 的 `milestones` 中配置，每位玩家每档只能手动领取一次。达标后收到“已达到多少次、可领取哪些奖励”的聊天提醒，点击整条消息打开界面，悬停预览物品。普通玩家也可以使用 `/easyvote rewards`（不带参数的 `/easyvote` 也会为非管理员打开界面）。

同一次在线期间每档只提醒一次；重新上线、插件启用、reload 后会再次提醒未领取档位。离线达标也会在上线后提醒，沿用 `join-reward-delay-seconds` 延迟。首次/普通奖励发放失败不会阻止里程碑提醒和领取。

格式参考 LiuInvite：`commands` 决定实际奖励，`display` 决定界面与悬停外观，`description` 决定聊天里的奖励说明；这些展示字段不会自动发放物品，请与命令保持一致。

```yaml
enabled: true
gui-title: "&6累计投票奖励"
milestones:
  - count: 10
    description: "&b钻石 ×10 &7+ &e5000 金币"
    display:
      slot: 20
      material: DIAMOND
      # 可选，填入实际 CE 物品 ID；优先于 material
      # craftengine_model: "namespace:item_id"
      name: "&a累计投票 %count% 次奖励"
      lore:
        - "&7奖励：&b钻石 ×10"
        - "&7奖励：&e5000 金币"
    commands:
      - "give %player% diamond 10"
      - "money give %player% 5000"
```

`display.craftengine_model` 与 LiuInvite 使用相同字段。安装并启用 CraftEngine 后，直接构建 CE 物品，保留其模型数据和原有物品说明；未安装、ID 无效或 API 不兼容时回退 `material`，默认箱子，并输出一次警告。未填写 `display.name` 时保留 CE 原物品名。聊天悬停和界面使用相同图标。实际领取仍执行 `commands`，CE 奖励请配置服务器所用的 CE 发放命令。

### 物品规格（display.material / states.*.material / craftengine_model）

`display.material`、`states.locked/available/received.material` 以及 `display.craftengine_model` 都会先交给 LiuLib 的物品规格解析器，支持以下写法（解析失败时自动回退原有 `material` 材质名或 CraftEngine 模型逻辑，老配置行为不变）：

| 写法 | 说明 |
|------|------|
| `DIAMOND` / `minecraft:diamond` | 原版材质（与原先一致） |
| `DIAMOND:3` / `DIAMOND*3` | 原版材质 + 数量 3；展示图标沿用规格自带数量 |
| `ce:namespace:item_id` / `craftengine:namespace:item_id` | CraftEngine 物品（与裸 CE ID `namespace:item_id` 等价） |
| `base64:...` / `nbt:...` / `bytes:...` / `item:base64:...` | Paper 二进制序列化物品，完整保留附魔、NBT、组件与 PDC |
| `lib:物品库ID` | 引用 liuitemlib 物品库条目（需安装并启用 liuitemlib） |

提醒消息的悬停预览与领取界面使用同一个物品，因此编码物品（`base64:` / `lib:`）同样能显示真实外观；`name`/`lore` 仍按配置覆盖，未配置时保留规格自带的名称与说明。物品发放仍由 `commands` 执行，需要发放同类物品请配置对应命令。

`display.slot` 为 0–44；45–53 为翻页与统计栏。重复或非法槽位自动安排空位，超过 45 档自动分页。`states.locked/available/received` 可配置状态 `lore`（追加），以及可选 `material`、`craftengine_model`、`name` 覆盖。状态只指定原版 `material` 时会替换原 CE 图标。文本支持 `&` 颜色或 MiniMessage 格式。

`messages.available` 支持 `%count%`（档位）、`%votes%`（当前票数）、`%rewards%`（奖励说明）。奖励说明缺省时使用 `display.lore`，再回退 `display.name` 或档位名称。领取命令中的 `%service%`、`%address%` 使用按入库顺序解锁该档位的投票记录。

升级保留旧的 `count + commands` 配置、已领取记录与部分发放进度，已有达标未领取档位直接转为可手动领取。首次升级且 `cumulative.yml` 不存在时，自动将旧 `config.yml` 的 `votifier.cumulative` 整段迁移到新文件根级，保存成功后移除旧段。已存在的 `cumulative.yml` 优先，不会被旧配置覆盖；若同时存在旧段会在日志提示忽略。新文件 YAML 格式错误时 reload 报错并保留当前里程碑配置。旧档位请自行补充 `description` 和 `display`，以显示准确的奖励内容与 CE 图标。修改后执行 `/easyvote reload` 生效。

## 构建

```bash
mvn clean package
```

输出：`target/Liu-EasyVote-1.4.1.jar`（瘦 jar，运行时依赖 LiuLib）

## 许可

作者：liuxingyu2001

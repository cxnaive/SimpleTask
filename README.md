# SimpleTask

一款适用于 Minecraft Folia/Paper 服务器的玩家任务系统插件。

## 功能特性

- **9种任务类型**: 发言(CHAT)、合成(CRAFT)、钓鱼(FISH)、消耗(CONSUME)、挖掘(BREAK)、收获(HARVEST)、提交(SUBMIT)、击杀(KILL)、繁殖(BREED)
- **任务分类系统**: 支持 daily/weekly/monthly/limited/permanent 五种任务类别
- **6种过期策略**: 每日、每周、每月、相对时间、固定时间段、永久
- **可配置自动领取**: 任务完成后自动发放奖励
- **进度追踪**: 实时进度显示，异步数据库操作
- **奖励系统**: 金币(XConomy)、物品、命令奖励
- **防刷检测**: 防止玩家通过放置-破坏方块刷挖掘任务进度
- **CraftEngine 支持**: 完整兼容自定义物品和方块
- **GUI 界面**: 用户友好的背包界面管理任务
- **多服同步**: MySQL 后端支持跨服务器数据同步

## 环境要求

- Java 21+
- Folia 或 Paper 1.21+
- (可选) XConomy - 用于经济奖励
- (可选) CraftEngine - 用于自定义物品支持

## 安装方法

1. 从发布页面下载最新版本
2. 将 JAR 文件放入服务器的 `plugins/` 文件夹
3. 启动服务器生成默认配置文件
4. 编辑 `plugins/SimpleTask/config.yml` 和 `plugins/SimpleTask/tasks.yml`
5. 重启服务器或使用 `/taskadmin reload` 重载配置

## 从源码构建

```bash
./gradlew shadowJar
```

构建后的 JAR 文件位于 `build/libs/SimpleTask-1.0.3.jar`。

## 任务分类系统

### 预置分类

| 分类 | 描述 | 默认并行数 | 过期策略 | 图标 |
|------|------|-----------|----------|------|
| daily | 每日任务 | 5 | 每日 04:00 刷新 | 时钟 |
| weekly | 周常任务 | 3 | 每周一 04:00 刷新 | 指南针 |
| monthly | 月度任务 | 2 | 每月1日 04:00 刷新 | 绿宝石 |
| limited | 限时挑战 | 2 | 7天后过期 | TNT |
| permanent | 永久成就 | 10 | 永不过期 | 下界之星 |

> **提示**: 以上为预置分类，你可以在 `config.yml` 的 `task-categories` 中：
> - **修改**预置分类的名称、图标、描述、并行数量、过期策略、刷新费用等
> - **关闭**不需要的分类（设置 `enabled: false`）
> - **添加**自定义的新分类

### 过期策略

| 策略 | 描述 | 配置参数 |
|------|------|----------|
| DAILY | 每日刷新 | reset-time |
| WEEKLY | 每周刷新 | reset-day-of-week, reset-time |
| MONTHLY | 每月刷新 | reset-day-of-month, reset-time |
| RELATIVE | 相对时间 | default-duration (如 7d) |
| PERMANENT | 永不过期 | 无 |
| FIXED | 固定时间段 | fixed-start, fixed-end |

### 自定义分类示例

你可以在 `config.yml` 中添加自定义任务分类：

```yaml
task-categories:
  # 自定义一个"活动任务"分类
  event:
    id: "event"
    enabled: true
    display:
      name: "<light_purple><bold>活动任务"
      lore:
        - "<gray>限时活动任务"
        - "<gray>活动结束后消失"
      item: "minecraft:nether_star"
      slot: 5
    assignment:
      max-concurrent: 3
      auto-assign: true
      auto-claim: true           # 活动任务自动领取奖励
      expire-policy: "fixed"     # 固定时间段
    fixed:
      start: "2026-03-01T00:00:00"
      end: "2026-03-15T00:00:00"
    reroll:
      enabled: false             # 活动任务不允许刷新
```

然后在 `tasks.yml` 中将任务的 `category` 设置为 `event` 即可。

## 配置文件

### config.yml

主配置文件，包含数据库、任务分类、GUI 和消息设置。

```yaml
# 数据库设置 (h2 单机, mysql 多服)
database:
  type: h2  # 或 mysql

# 任务分类配置
task-categories:
  daily:
    enabled: true
    display:
      name: "<gold><bold>每日任务"
      item: "minecraft:clock"
      slot: 0
    assignment:
      max-concurrent: 5        # 最大并行任务数
      auto-assign: true        # 自动分配任务
      auto-claim: false        # 完成后自动领取奖励
      expire-policy: "daily"   # 过期策略
    reset:
      time: "04:00"            # 重置时间
    reroll:
      enabled: true
      cost: 100.0              # 刷新费用
      max-count: 3             # 最大刷新次数

# 挖掘任务防刷检测
anti-cheat:
  enabled: true
  time-window: 3600            # 缓存时间(秒)，默认60分钟
```

### tasks.yml

在此定义任务模板。示例：

```yaml
# 挖掘钻石任务
mine_diamonds:
  name: "钻石矿工"
  type: BREAK
  category: daily              # 所属分类
  target-item: minecraft:diamond_ore
  target-amount: 10
  description: "挖掘10个钻石矿"
  icon: minecraft:diamond_pickaxe
  weight: 10
  reward:
    money: 100
    items:
      - item: minecraft:diamond
        amount: 5

# 钓鱼任务
fish_salmon:
  name: "鲑鱼渔夫"
  type: FISH
  category: daily
  target-item: minecraft:salmon
  target-amount: 5
  description: "钓上5条鲑鱼"
  icon: minecraft:fishing_rod
  weight: 15
  reward:
    money: 50
    commands:
      - "give {player} minecraft:cod 10"
```

## 命令列表

### 玩家命令

| 命令 | 描述 | 权限 |
|------|------|------|
| `/task` | 打开任务分类界面 | simpletask.use |
| `/task [分类]` | 直接打开指定分类 | simpletask.use |

### 管理员命令

| 命令 | 描述 | 权限 |
|------|------|------|
| `/taskadmin` | 打开管理界面 | simpletask.admin |
| `/taskadmin reloadconfig` | 重载配置文件 | simpletask.admin |
| `/taskadmin reloadfromdb` | 从数据库重载模板 | simpletask.admin |
| `/taskadmin import [all\|key]` | 导入任务模板 | simpletask.admin |
| `/taskadmin list` | 列出所有模板 | simpletask.admin |
| `/taskadmin delete <key>` | 禁用任务模板 | simpletask.admin |
| `/taskadmin reroll <分类> <玩家\|all>` | 重新抽取指定分类任务 | simpletask.admin |
| `/taskadmin rerollall <分类> <玩家\|all>` | 强制刷新指定分类所有任务 | simpletask.admin |
| `/taskadmin assign <分类> <任务key> <玩家\|all>` | 给玩家分配指定任务 | simpletask.admin |
| `/taskadmin resetreroll <分类> <玩家\|all>` | 重置刷新次数 | simpletask.admin |

## 任务类型详解

| 类型 | 描述 | 目标物品示例 |
|------|------|-------------|
| CHAT | 发送聊天消息 | "你好" (关键词包含匹配) |
| CRAFT | 合成物品 | minecraft:diamond_pickaxe |
| FISH | 钓鱼 | minecraft:cod |
| CONSUME | 吃/喝物品 | minecraft:golden_apple |
| BREAK | 破坏方块 | minecraft:stone |
| HARVEST | 收获作物 | minecraft:wheat |
| SUBMIT | 提交物品 | minecraft:diamond |
| KILL | 击杀生物 | minecraft:zombie |
| BREED | 繁殖动物 | minecraft:cow |

### 多物品匹配

`target-item` 支持单个物品或列表，满足任意一个即算进度：

```yaml
# 单个物品
target-item: minecraft:oak_log

# 多个物品（任一匹配）
target-item:
  - minecraft:oak_log
  - minecraft:birch_log
  - minecraft:spruce_log
```

### 不设置目标物品

如果不设置 `target-item`，则所有同类事件都计入进度：

```yaml
# 任何聊天都计数
type: CHAT
target-amount: 10

# 任何鱼都计数
type: FISH
target-amount: 5
```

## 数据库支持

### H2 (默认)
适合单机服务器，数据存储在本地文件。

```yaml
database:
  type: h2
  h2:
    filename: simpletask
```

### MySQL
适合多服务器共享数据。

```yaml
database:
  type: mysql
  mysql:
    host: localhost
    port: 3306
    database: simpletask
    username: root
    password: password
    pool-size: 10
```

## 模板版本控制

每个任务模板都有 `version` 字段，用于检测模板变更：

```yaml
mine_diamonds:
  name: "钻石矿工"
  type: BREAK
  version: 2  # 版本号，修改后重新导入会触发同步
```

### 工作流程

1. **首次导入**: 在 `tasks.yml` 中定义任务后，使用 `/taskadmin import all` 导入到数据库
2. **修改模板**: 修改 `tasks.yml` 中的任务配置，并增加 `version` 号
3. **重载配置**: 使用 `/taskadmin reloadconfig` 加载修改后的配置到内存
4. **重新导入**: 使用 `/taskadmin import <任务key>` 或 `import all` 将更新同步到数据库
5. **自动同步**: 如果配置了 `template.sync-interval`，系统会自动检测**其他服务器**导入的模板变更并同步到本服（用于多服共享数据库场景）

### 注意事项

- 版本号只在导入时读取，用于判断数据库中的模板是否需要更新
- 玩家已接取的任务不会自动变更，只有新抽取的任务会使用最新模板
- 使用 `/taskadmin reloadfromdb` 可以从数据库重新加载模板到内存（用于多服同步场景）

## 更新日志

### 1.0.3
- 新增任务分类系统（daily/weekly/monthly/limited/permanent）
- 新增 6 种过期策略
- 新增可配置的自动领取奖励功能
- GUI 新增"进行中"状态显示
- 修复任务进度满后 completed 未同步到数据库的问题
- 修复分配任务时模板 category 与数据库不一致的问题
- assign 命令添加类别不一致警告
- 将"每日任务"统一改名为"任务系统"

## 开源协议

MIT 协议 - 详见 LICENSE 文件。

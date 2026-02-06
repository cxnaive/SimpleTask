# SimpleTask

一款适用于 Minecraft Folia/Paper 服务器的玩家每日任务系统插件。

## 功能特性

- **9种任务类型**: 发言(CHAT)、合成(CRAFT)、钓鱼(FISH)、消耗(CONSUME)、挖掘(BREAK)、收获(HARVEST)、提交(SUBMIT)、击杀(KILL)、繁殖(BREED)
- **每日任务分配**: 基于权重随机抽取任务分配给玩家
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

构建后的 JAR 文件位于 `build/libs/SimpleTask-1.0.0.jar`。

## 配置文件

### config.yml

主配置文件，包含数据库、GUI 和消息设置。

```yaml
# 数据库设置 (h2 单机, mysql 多服)
database:
  type: h2  # 或 mysql

# 每日任务设置
daily-tasks:
  daily-count: 5        # 每日任务数量
  auto-claim: false     # 是否自动领取奖励

# 挖掘任务防刷检测
anti-cheat:
  enabled: true
  time-window: 3600     # 缓存时间(秒)，默认60分钟
```

### tasks.yml

在此定义任务模板。示例：

```yaml
# 挖掘钻石任务
mine_diamonds:
  name: "钻石矿工"
  type: BREAK
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
  target-item: minecraft:salmon
  target-amount: 5
  description: "钓上5条鲑鱼"
  icon: minecraft:fishing_rod
  weight: 15
  reward:
    money: 50
    commands:
      - "give {player} minecraft:fish 10"
```

## 命令列表

### 玩家命令

| 命令 | 描述 | 权限 |
|---------|-------------|------------|
| `/task` | 打开每日任务界面 | simpletask.use |

### 管理员命令

| 命令 | 描述 | 权限 |
|---------|-------------|------------|
| `/taskadmin` | 打开管理界面 | simpletask.admin |
| `/taskadmin reloadconfig` | 重载配置文件 | simpletask.admin |
| `/taskadmin reloadfromdb` | 从数据库重载模板 | simpletask.admin |
| `/taskadmin import [all\|key]` | 导入任务模板 | simpletask.admin |
| `/taskadmin list` | 列出所有模板 | simpletask.admin |
| `/taskadmin delete <key>` | 禁用任务模板 | simpletask.admin |
| `/taskadmin reroll <player\|all>` | 重新抽取每日任务 | simpletask.admin |
| `/taskadmin assign <player\|all> <task>` | 给玩家分配任务 | simpletask.admin |

## 任务类型详解

| 类型 | 描述 | 目标物品示例 |
|------|-------------|---------------------|
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
  version: 2  # 版本号，修改后会触发同步
```

修改版本号后，使用 `/taskadmin reloadfromdb` 或等待自动同步即可更新玩家任务。

## 开源协议

MIT 协议 - 详见 LICENSE 文件。

# SimpleTask - 玩家每日任务系统插件

## 项目概述
兼容 Folia/Paper 的玩家任务系统插件，支持每日任务抽取、进度追踪、奖励发放。

## 技术栈
- **构建工具**: Gradle (Kotlin DSL) + Shadow 插件
- **服务端API**: Folia API 1.21.11
- **数据库**: HikariCP + H2 (本地) / MySQL (跨服)
- **经济系统**: XConomy API (软依赖)
- **自定义物品**: CraftEngine API (软依赖)
- **序列化**: Gson

## 项目结构
```
SimpleTask/
├── build.gradle.kts / settings.gradle.kts / gradlew
├── src/main/
│   ├── java/dev/user/simpletask/
│   │   ├── SimpleTaskPlugin.java
│   │   ├── api/
│   │   │   └── TaskAPI.java              # API接口 (预留任务链扩展)
│   │   ├── command/
│   │   │   ├── SimpleTaskCommand.java    # /task 玩家命令
│   │   │   └── AdminCommand.java         # /taskadmin 管理命令
│   │   ├── config/
│   │   │   └── ConfigManager.java
│   │   ├── database/
│   │   │   ├── DatabaseManager.java      # 连接池管理 (H2/MySQL)
│   │   │   └── DatabaseQueue.java        # 异步操作队列
│   │   ├── economy/
│   │   │   └── EconomyManager.java       # XConomy集成
│   │   ├── gui/
│   │   │   ├── AbstractGUI.java          # GUI基类
│   │   │   ├── GUIManager.java
│   │   │   ├── DailyTaskGUI.java         # 每日任务GUI (自动居中)
│   │   │   └── AdminTaskGUI.java
│   │   ├── listener/
│   │   │   ├── GUIListener.java
│   │   │   └── TaskListener.java         # 聊天/合成/钓鱼等监听
│   │   ├── task/
│   │   │   ├── TaskManager.java          # 任务核心管理
│   │   │   ├── TaskTemplate.java         # 任务模板
│   │   │   ├── PlayerTask.java           # 玩家任务实例
│   │   │   ├── TaskType.java             # 任务类型枚举
│   │   │   ├── TemplateSyncManager.java  # 模板同步管理
│   │   │   └── Reward.java               # 奖励系统
│   │   └── util/
│   │       ├── ItemUtil.java             # 物品工具 (CE支持)
│   │       ├── MessageUtil.java          # 消息发送
│   │       └── TimeUtil.java
│   └── resources/
│       ├── plugin.yml
│       ├── config.yml
│       └── tasks.yml                     # 任务模板配置文件
```

## 核心功能实现

### 1. 数据库架构
- **DatabaseManager**: 使用 HikariCP 连接池，支持 H2 和 MySQL
- **DatabaseQueue**: 异步数据库操作队列，自动连接管理
- **DriverShim**: 支持 PlugMan 重载的数据库驱动管理

### 2. 任务系统
- **任务类型**: CHAT (发言), CRAFT (合成), FISH (钓鱼), CONSUME (消耗), BREAK (挖掘), HARVEST (收获), SUBMIT (提交), KILL (击杀), BREED (繁殖)
- **权重抽取**: 按权重随机抽取任务，可配置每日任务数量
- **进度追踪**: 实时缓存 + 数据库同步，支持跨服进度同步
- **模板版本**: 每个任务独立版本号，支持版本控制和变更检测

### 3. GUI 系统
- **54格大箱子界面**
- **自动居中算法**: 任务在可用区域内自动居中显示
- **状态显示**: 已领取(绿色)、可领取(黄色)、进行中(灰色)
- **进度条**: 20格可视化进度条
- **提交任务**: 点击任务图标扫描背包并扣除物品

### 4. 事件监听
- **聊天事件**: 支持指定关键词匹配 (包含匹配，不区分大小写)
- **合成事件**: 支持 shift-click 批量合成，支持多物品匹配
- **钓鱼**: 支持指定钓获物品
- **消耗**: 支持 CraftEngine 物品，支持蛋糕（特殊处理）
- **挖掘/收获**: 区分作物成熟状态，成熟作物同时触发 HARVEST 和 BREAK
- **击杀**: 监听 EntityDeathEvent，支持指定实体类型
- **繁殖**: 监听 EntityBreedEvent，支持指定动物类型

### 5. 奖励系统
- **金币奖励**: XConomy 集成
- **物品奖励**: 支持原版和 CraftEngine 物品
- **命令奖励**: 支持执行控制台命令

### 6. 物品匹配系统
- **多物品支持**: `target-item` 可以是字符串或列表，任意匹配
- **方块/物品ID兼容**: 自动处理单复数差异 (carrot <-> carrots)
- **CE物品支持**: CraftEngine 自定义方块和物品识别
- **灵活匹配**: HARVEST/BREAK 类型自动标准化ID比较

## 配置文件说明

### config.yml
```yaml
# 数据库设置
database:
  type: h2  # 或 mysql
  # ...

# 每日任务设置
daily-tasks:
  daily-count: 5
  auto-claim: false

# 模板同步设置
template:
  sync-interval: 0  # 0=禁用自动同步

# 数据保留设置
data:
  retention-days: 7

# 任务类型名称
task-types:
  CHAT: "发言"
  CRAFT: "合成"
  FISH: "钓鱼"
  CONSUME: "消耗"
  BREAK: "挖掘"
  HARVEST: "收获"
  SUBMIT: "提交"
  KILL: "击杀"
  BREED: "繁殖"

# 消息设置
messages:
  prefix: "<gold>[每日任务] <reset>"
  task-completed: "<green>恭喜！你完成了任务: <yellow>{task_name}"
  task-progress-milestone: "<gray>任务 <yellow>{task_name} <gray>进度: <yellow>{progress}/{target} <gray>({percent}%)"
  # ...
```

### tasks.yml
独立的任务模板配置文件：
```yaml
# 任务模板格式
task_key:
  type: CHAT/CRAFT/FISH/CONSUME/BREAK/HARVEST/SUBMIT/KILL/BREED
  version: 1                          # 任务版本号
  target-item: "minecraft:item_key"   # 单物品
  target-item:                        # 多物品列表（任意匹配）
    - "minecraft:item1"
    - "minecraft:item2"
  target-amount: 10
  description: "任务描述"
  icon: "minecraft:writable_book"     # GUI 显示图标
  weight: 50                          # 抽取权重
  reward:
    money: 100.0
    items:
      - item: "minecraft:diamond"
        amount: 5
    commands:
      - "give {player} emerald 1"
```

## 任务类型详解

### CHAT (发言)
- **触发**: 玩家聊天
- **target-item**: 可选，指定关键词（消息包含关键词即匹配）
- **示例**: 说 "hello" 5次，或任意发言50次

### CRAFT (合成)
- **触发**: 合成物品
- **target-item**: 可选，指定合成结果物品
- **特点**: 支持 shift-click 批量计算，支持多物品匹配

### FISH (钓鱼)
- **触发**: 钓起物品
- **target-item**: 可选，指定钓获物品
- **示例**: 钓起10条鳕鱼，或任意宝藏物品3个

### CONSUME (消耗)
- **触发**: 吃食物/喝药水/吃蛋糕
- **target-item**: 可选，指定消耗的物品
- **特殊处理**: 蛋糕不会触发 `PlayerItemConsumeEvent`，使用 `PlayerInteractEvent` 检测右键点击蛋糕方块
- **支持类型**: 原版蛋糕、蜡烛蛋糕等所有 `*_CAKE` 变种

### BREAK (挖掘)
- **触发**: 破坏方块
- **target-item**: 可选，指定方块类型
- **特点**: 成熟作物收获时同时触发 HARVEST

### HARVEST (收获)
- **触发**: 收获成熟作物
- **支持作物**: 小麦、胡萝卜、马铃薯、甜菜根、可可豆、下界疣、南瓜、西瓜、甘蔗、仙人掌、竹子、甜浆果、发光浆果
- **特点**: 可配置任意作物或指定类型

### SUBMIT (提交)
- **触发**: 点击任务图标
- **特点**: 扫描背包扣除物品，支持 CraftEngine 物品
- **target-item**: 需要提交的物品

### KILL (击杀)
- **触发**: 击杀实体
- **target-item**: 实体类型 ID (如 "minecraft:zombie")
- **示例**: 击杀20只僵尸，或任意敌对生物50只

### BREED (繁殖)
- **触发**: 繁殖动物
- **target-item**: 动物实体类型 ID
- **示例**: 繁殖10头牛，或任意动物30只

## 管理命令

| 命令 | 说明 |
|------|------|
| `/task` | 打开每日任务 GUI |
| `/taskadmin` | 打开管理 GUI |
| `/taskadmin import [all/任务key]` | 导入任务模板到数据库 |
| `/taskadmin list` | 查看任务列表 |
| `/taskadmin delete <任务key>` | 禁用任务模板（软删除） |
| `/taskadmin reroll <玩家名/all>` | 重新抽取任务 |
| `/taskadmin reload` | 重载配置 |

## 数据库表结构

```sql
-- 任务模板表
task_templates:
  - id (PK), task_key (UNIQUE), version
  - task_type, target_item, target_amount
  - reward_money, reward_items, reward_commands
  - description, icon, weight, task_data (JSON), enabled

-- 玩家每日任务表
player_daily_tasks:
  - player_uuid, task_key, task_date (PK)
  - task_version, task_type, target_item, target_amount
  - current_progress, completed, claimed, task_data

-- 玩家任务重置记录表
player_task_reset:
  - player_uuid (PK), last_reset_date, reset_server
```

## 跨服同步
- 使用 MySQL 实现跨服数据同步
- 实时数据库同步（每次进度变化立即写入）
- 定时检查每日重置（每分钟）
- 数据清理（自动清理过期数据）
- 模板版本控制（按版本号同步变更）

## 兼容性处理

### PlayerChat 插件兼容
PlayerChat 插件会取消 `AsyncPlayerChatEvent` 并触发自定义事件。
解决方案：使用 `HIGH` 优先级在原版事件被取消前捕获。

```java
@EventHandler(priority = EventPriority.HIGH)
public void onAsyncPlayerChat(AsyncPlayerChatEvent event) {
    // 在 PlayerChat (HIGHEST) 之前处理
    String message = event.getMessage();
    taskManager.updateProgress(player, TaskType.CHAT, message, 1);
}
```

### CraftEngine 兼容
使用反射检测 CE 是否可用，支持 CE 自定义方块识别：
```java
// 检测 CE 是否可用
ceAvailable = checkCEAvailable();

// 获取方块 key（支持 CE 自定义方块）
public static String getBlockKey(Block block) {
    if (ceAvailable) {
        String ceKey = getCEBlockKey(block);
        if (ceKey != null) return ceKey;
    }
    return "minecraft:" + block.getType().name().toLowerCase();
}
```

### BlockBreakEvent 双触发
成熟作物收获时同时触发 HARVEST 和 BREAK：
```java
boolean isHarvestable = isHarvestableCrop(block) && isFullyGrown(block);
if (isHarvestable) {
    taskManager.updateProgress(player, TaskType.HARVEST, itemKey, 1);
}
taskManager.updateProgress(player, TaskType.BREAK, itemKey, 1);
```

## 版本控制

### 任务模板版本
- 每个任务有独立的 `version` 字段
- 导入时会覆盖数据库中的旧版本
- 自动同步只同步版本号更高的模板

### 配置示例
```yaml
harvest_wheat_32:
  type: HARVEST
  version: 1          # 初始版本
  target-item: "minecraft:wheat"
  target-amount: 32
```

修改任务后增加版本号：
```yaml
harvest_wheat_32:
  type: HARVEST
  version: 2          # 更新版本
  target-item: "minecraft:wheat"
  target-amount: 64   # 修改了数量
```

## 构建
```bash
./gradlew shadowJar
```
产物：`build/libs/SimpleTask-1.0.0.jar`

## 部署
1. 放入 `plugins/` 目录
2. 启动服务器生成配置文件
3. 编辑 `tasks.yml` 配置任务模板
4. 执行 `/taskadmin import` 导入模板
5. 完成！

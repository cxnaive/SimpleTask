# SimpleTask - 玩家任务系统插件

## 项目概述
兼容 Folia/Paper 的玩家任务系统插件，支持多分类任务、多种过期策略、进度追踪、奖励发放。

## 技术栈
- **构建工具**: Gradle (Kotlin DSL) + Shadow 插件
- **服务端API**: Folia API 1.21
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
│   │   │   └── TaskAPI.java              # API接口
│   │   ├── command/
│   │   │   ├── SimpleTaskCommand.java    # /task 玩家命令
│   │   │   └── AdminCommand.java         # /taskadmin 管理命令
│   │   ├── config/
│   │   │   └── ConfigManager.java
│   │   ├── database/
│   │   │   ├── DatabaseManager.java      # 连接池管理 (H2/MySQL)
│   │   │   ├── DatabaseQueue.java        # 异步操作队列
│   │   │   └── DatabaseMigration.java    # 数据库迁移
│   │   ├── economy/
│   │   │   └── EconomyManager.java       # XConomy集成
│   │   ├── gui/
│   │   │   ├── AbstractGUI.java          # GUI基类
│   │   │   ├── GUIManager.java
│   │   │   ├── TaskCategoryGUI.java      # 任务分类主界面
│   │   │   ├── CategoryTaskGUI.java      # 分类任务详情界面
│   │   │   └── AdminTaskGUI.java
│   │   ├── listener/
│   │   │   ├── GUIListener.java
│   │   │   └── TaskListener.java         # 聊天/合成/钓鱼等监听
│   │   ├── task/
│   │   │   ├── TaskManager.java          # 任务管理入口类
│   │   │   ├── TaskTemplate.java         # 任务模板
│   │   │   ├── PlayerTask.java           # 玩家任务实例
│   │   │   ├── TaskType.java             # 任务类型枚举
│   │   │   ├── Reward.java               # 奖励系统
│   │   │   ├── ExpirePolicy.java         # 过期策略枚举
│   │   │   ├── ExpirePolicyConfig.java   # 过期策略配置
│   │   │   ├── TemplateSyncManager.java  # 模板同步管理
│   │   │   ├── category/
│   │   │   │   └── TaskCategory.java     # 任务类别配置
│   │   │   └── manager/
│   │   │       ├── TaskCacheManager.java # 任务缓存管理
│   │   │       ├── TaskExpireManager.java# 任务过期管理
│   │   │       ├── TaskGenerator.java    # 任务生成器
│   │   │       ├── TaskProgressManager.java # 任务进度管理
│   │   │       ├── TaskScheduler.java    # 定时调度器
│   │   │       ├── RerollManager.java    # 刷新管理器
│   │   │       └── DatabaseUtils.java    # 数据库工具
│   │   └── util/
│   │       ├── ItemUtil.java             # 物品工具 (CE支持)
│   │       ├── MessageUtil.java          # 消息发送
│   │       ├── TimeUtil.java             # 时间工具
│   │       ├── TimeZoneConfig.java       # 时区配置
│   │       ├── ExpireUtil.java           # 过期判断工具
│   │       ├── GUIComponentBuilder.java  # GUI组件构建器
│   │       └── NBTPathUtils.java         # NBT路径解析
│   └── resources/
│       ├── plugin.yml
│       ├── config.yml                    # 主配置（含分类配置）
│       └── tasks.yml                     # 任务模板配置文件
```

## 核心功能实现

### 1. 任务分类系统
- **预置分类**: daily, weekly, monthly, limited, permanent
- **自定义分类**: 可在 config.yml 中添加/修改/禁用分类
- **分类配置**: 名称、图标、Lore、槽位、并行数量、过期策略、刷新设置

### 2. 过期策略
| 策略 | 描述 | 配置参数 |
|------|------|----------|
| DAILY | 每日刷新 | reset-time |
| WEEKLY | 每周刷新 | reset-day-of-week, reset-time |
| MONTHLY | 每月刷新 | reset-day-of-month, reset-time |
| RELATIVE | 相对时间 | default-duration (如 7d) |
| PERMANENT | 永不过期 | 无 |
| FIXED | 固定时间段 | fixed-start, fixed-end |

### 3. 任务管理器架构
TaskManager 作为入口类，委托给专门的管理器：
- **TaskCacheManager**: 玩家任务内存缓存
- **TaskExpireManager**: 过期检测与刷新
- **TaskGenerator**: 任务随机生成
- **TaskProgressManager**: 进度更新与奖励
- **RerollManager**: 刷新次数管理
- **TaskScheduler**: 定时任务调度

### 4. 任务类型
CHAT (发言), CRAFT (合成), FISH (钓鱼), CONSUME (消耗), BREAK (挖掘), HARVEST (收获), SUBMIT (提交), KILL (击杀), BREED (繁殖)

### 5. 奖励系统
- **金币奖励**: XConomy 集成
- **物品奖励**: 支持原版和 CraftEngine 物品，背包满时掉落脚下
- **命令奖励**: 支持执行控制台命令
- **自动领取**: 可配置 auto-claim 自动发放奖励

### 6. 时区支持
- 统一使用 TimeZoneConfig 管理时区
- FIXED 策略支持本地时间格式（如 `2026-03-01T00:00:00`）

## 配置文件说明

### config.yml
```yaml
# 时区设置
timezone: "system"  # 或 "Asia/Shanghai" 等

# 数据库设置
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
      max-concurrent: 5
      auto-assign: true
      auto-claim: false      # 完成后自动领取奖励
      expire-policy: "daily"
    reset:
      time: "04:00"
    reroll:
      enabled: true
      cost: 100.0
      max-count: 3

# 消息设置
messages:
  prefix: "<gold>[任务系统] <reset>"
  task-completed: "<green>恭喜！你完成了任务: <yellow>{task_name}"
  task-completed-auto: "<green>恭喜！你完成了任务: <yellow>{task_name} <green>，奖励已自动发放: <yellow>{reward}"
```

### tasks.yml
```yaml
task_key:
  type: CHAT/CRAFT/FISH/CONSUME/BREAK/HARVEST/SUBMIT/KILL/BREED
  category: daily              # 所属分类
  version: 1                   # 任务版本号
  target-item: "minecraft:item_key"
  target-amount: 10
  description: "任务描述"
  icon: "minecraft:writable_book"
  weight: 50
  reward:
    money: 100.0
    items:
      - item: "minecraft:diamond"
        amount: 5
    commands:
      - "give {player} emerald 1"
```

## 管理命令

| 命令 | 说明 |
|------|------|
| `/task` | 打开任务分类界面 |
| `/task [分类]` | 直接打开指定分类 |
| `/taskadmin` | 打开管理界面 |
| `/taskadmin import [all/任务key]` | 导入任务模板 |
| `/taskadmin list` | 查看任务列表 |
| `/taskadmin delete <任务key>` | 禁用任务模板 |
| `/taskadmin reroll <分类> <玩家/all>` | 重新抽取任务 |
| `/taskadmin rerollall <分类> <玩家/all>` | 强制刷新所有任务 |
| `/taskadmin assign <分类> <任务key> <玩家/all>` | 分配指定任务 |
| `/taskadmin resetreroll <分类> <玩家/all>` | 重置刷新次数 |
| `/taskadmin reloadconfig` | 重载配置 |

## 数据库表结构

```sql
-- 任务模板表
task_templates:
  - id (PK), task_key (UNIQUE), version
  - task_data (JSON), enabled

-- 玩家任务表
player_daily_tasks:
  - player_uuid, task_key, assigned_at (PK)
  - category, task_version, current_progress
  - completed, claimed, task_data (JSON)

-- 刷新次数表
player_category_reroll:
  - player_uuid, category_id (PK)
  - reroll_count, last_reset_time

-- 分类重置记录表
player_category_reset:
  - player_uuid, category_id (PK)
  - last_reset_date
```

## 过期判断逻辑

### 周期编号法（DAILY/WEEKLY/MONTHLY）
将时间映射到周期编号，编号不同即过期：
- DAILY: 基于重置时间计算日周期编号
- WEEKLY: 基于重置日+重置时间计算周周期编号
- MONTHLY: 基于重置日+重置时间计算月周期编号

### FIXED 策略
- 检查当前时间是否在 fixedStart 和 fixedEnd 之间
- 活动结束后不生成新任务
- 支持本地时间格式和 ISO-8601 格式

## 构建
```bash
./gradlew shadowJar
```
产物：`build/libs/SimpleTask-1.0.3.jar`

## 部署
1. 放入 `plugins/` 目录
2. 启动服务器生成配置文件
3. 编辑 `config.yml` 配置分类（可选）
4. 编辑 `tasks.yml` 配置任务模板
5. 执行 `/taskadmin import all` 导入模板
6. 完成！

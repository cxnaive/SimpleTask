# Minecraft 26.2 内容与任务兼容性检查

2026-10-10 完成 SimpleTask 2.0.2 的专项检查，覆盖全部 11 种任务类型。
编译 API 为 `dev.folia:folia-api:26.2.build.7-beta`，构建和运行使用 Java 25。
本报告补充 [2.0.1 API 升级报告](RESULTS.md)，重点检查内容 ID、食物、作物和事件行为。

## 版本内容差异

使用验证驱动固定的 Minecraft Data 数据比较版本，并在真实服务器上核对 Bukkit API：

| 比较范围 | 检查结果 |
| --- | --- |
| 26.1 到 26.2 | 增加 28 个方块 ID、31 个物品 ID、1 个实体 ID；没有新增原版食物或农作物 |
| 项目原 API 1.21.4 到 26.2 | 物品 ID 差集为 153、实体 ID 差集为 10，包含新增或更名 ID；全部通过真实 API 创建/注册表检查 |
| 旧铁链 ID | `minecraft:chain` 已更名为 `minecraft:iron_chain`，物品创建、任务匹配和 GUI 提交兼容旧配置 |

26.2 新内容主要是硫磺、朱砂及其建筑方块，另有 `sulfur_cube_bucket`、
`sulfur_cube_spawn_egg`、`music_disc_bounce` 和新实体 `sulfur_cube`。
本次补齐的仙人掌花、火把花、瓶子草、海带和浆果收获分支中，有些内容早于 26.2，
不能仅升级 API 就视为已适配。

## 全部任务类型

| 类型 | 兼容性结论、修复与验证方式 |
| --- | --- |
| CHAT | 现有聊天监听可用；原生 26.2 协议客户端发送消息，验证关键词、进度与完成 |
| CRAFT | 使用结果槽实际物品，保留动态配方组件；事件测试验证普通/Shift 点击数量、实际组件条件、锻造结果及取消事件 |
| FISH | 按实际钓获物品 ID 匹配，无旧物品白名单；事件测试验证新物品匹配及取消，不表示原版钓鱼会产出该物品 |
| CONSUME | 按实际消费物品匹配，支持饮品、双手及带 food/consumable 组件的物品；蛋糕改为依据实际咬数变化计数，详见下文 |
| BREAK | 按方块 ID 匹配，支持新方块；原生客户端验证硫磺和朱砂，原有回归检查取消事件及放置防刷 |
| HARVEST | 补齐仙人掌花、成熟火把花/瓶子草、海带、甜浆果破坏与两种带浆果洞穴藤蔓；原生客户端检查成熟度、上下半部、右键收获、防刷和海带连锁掉落 |
| SUBMIT | 新物品通过正常背包扫描、GUI 点击、扣除和完成流程；原生客户端验证硫磺及旧铁链目标，原有回归检查组件条件和自动领奖 |
| KILL | 使用死亡实体的注册 ID，无旧实体枚举白名单；7 种新生物通过真实服务端玩家伤害触发死亡并增加进度 |
| BREED | 使用繁殖事件中幼体的实体 ID；鹦鹉螺事件测试验证新动物匹配与取消，未完成客户端实际繁殖验收 |
| COMMAND | 原生客户端验证带参数命令触发匹配、进度与完成 |
| PAPI | 真实玩家等级通过测试扩展参与周期条件判断，扩展断言在玩家所属线程运行；通过完成、奖励与持久化回归 |

KILL 实测实体为 `camel_husk`、`copper_golem`、`happy_ghast`、`nautilus`、
`parched`、`sulfur_cube`、`zombie_nautilus`。10 个实体 ID 的注册表检查不等于
全部实体都完成了实际击杀验收。硫磺立方怪分裂不属于 BREED，吞物品不属于玩家 CONSUME。

## 食物、饮品与蛋糕

- Bukkit `Material.isEdible()` 返回的 40 种物品，加药水和牛奶，共 42 种物品通过
  `PlayerItemConsumeEvent` 测试；使用副手，并验证取消事件不计数。
- 原生客户端实际消费苹果、蜂蜜、迷之炖菜、药水、牛奶、紫颂果、金苹果，以及
  添加 `minecraft:food` / `minecraft:consumable` 组件的石头。
- 食用前明确设置饱食度；满饱食度场景验证了仍可消费的饮品、紫颂果、金苹果及组件食物。
- 普通蛋糕完整七口全部计数，包含最后一口使方块消失的情况；蜡烛蛋糕食用也计数。
  放蜡烛、点火、灭火及满饱食度点击不增加进度。所有蛋糕食用目标统一为 `minecraft:cake`。

普通物品继续使用消费事件的实际 ItemStack，未增加只允许原版食物的限制。
蛋糕使用 `EntityChangeBlockEvent` 的前后方块状态判断实际咬数变化，
不依赖玩家点击推测是否吃到了蛋糕，也不依赖统计保存是否开启。

## 收获与匹配修复

- 破坏成熟瓶子草时，检查上下半部；未成熟瓶子草和火把花不计收获。
- 直接放置的成熟火把花、仙人掌花不计收获；火把花从种子长成时清除放置标记，
  骨粉催熟后的真实破坏操作已通过验证。
- 海带扫描兼容 `KELP_PLANT` 中段和 `KELP` 顶端，排除玩家放置部分并防止重复计数。
- 右键收获依据 `PlayerHarvestBlockEvent` 的实际掉落物，覆盖 `CAVE_VINES` 和
  `CAVE_VINES_PLANT`；空藤蔓再次点击不计数。
- 方块/掉落物别名使用明确的原版作物映射；自定义命名空间保留精确 ID。
  移除任意去掉末尾 `s` 的处理，避免混淆 `sulfur_bricks` 等新方块。

以上检查通过正常模板导入、加载和任务分配创建专项任务，未直接伪造玩家任务缓存。
默认任务模板没有增加新内容任务；管理员可在 `tasks.yml` 中配置对应 `target-item`。

## 最终验证结果

`./gradlew build validationProbeJar` 成功，JUnit 33 项全部通过。
两轮使用同一个最终生产 JAR，均完成原有 40 项回归和新增 35 项专项检查。

| 环境 | 数据库与 CraftEngine | 结果 | 本机原始报告 |
| --- | --- | --- | --- |
| Folia 26.2 build 7 | H2 2.3.232；无 CraftEngine | 75/75 PASS | [results.json](../reports/simpletask-folia-26.2-lBVR6Y/results.json) |
| Leaf 26.2 build 124（Paper 分支） | H2 2.3.232；CraftEngine 26.9.2 | 75/75 PASS | [results.json](../reports/simpletask-leaf-26.2-qyHEtn/results.json) |

两轮日志检查均为 `logIssues: []`，服务器正常停服，退出码为 0，未强制终止。
依赖插件的固定版本和哈希见 [artifacts.json](automation/artifacts.json)。
Folia 无 CE 场景故意请求不存在的自定义物品时有一条精确匹配的预期告警，原始日志保留。

生产产物：`build/libs/SimpleTask-2.0.2.jar`。
SHA-256：`a1f2e723876f1764bb73e51e44a3be47ddc7ded00dcfe716a4202cbe3e0d5511`。
JAR 内插件版本为 2.0.2、API 版本为 26.2，不含探针或测试类。
原始报告和测试目录不进入 Git，报告链接指向本工作区保留的证据。

## 复现与边界

依赖准备、EULA 和测试服隔离方式见 [README.md](README.md)。从项目根目录运行：

```sh
./gradlew build validationProbeJar
SIMPLETASK_LIBRARY_CACHE=/path/to/stopped/test-server/libraries \
  node validation/folia/automation/pipeline.mjs --accept-eula --port=25631
SIMPLETASK_LIBRARY_CACHE=/path/to/stopped/test-server/libraries \
  node validation/folia/automation/pipeline.mjs --accept-eula --leaf --ce --port=25633
```

已实测的消费、蛋糕、作物及 GUI 操作使用原生 26.2 协议；世界方块、物品、饱食度等
前置状态由测试探针准备。事件测试验证真实服务端的监听、匹配、进度和数据库链路，
未完成真人客户端完整的合成、锻造、钓鱼和繁殖操作。

未穷举所有植物物理连锁掉落，例如破坏仙人掌支撑使花掉落；没有验证普通官方 Paper，
Leaf 仅作为 Paper 分支证据。本次 2.0.2 没有数据库改动，也未重跑 MySQL；
2.0.1 的 MySQL 实测结果保留在历史报告中，不作为本次产物的 MySQL 验证。
其它 PAPI 扩展、生产插件组合、跨 region 交互、跨服同步和长期并发压力仍在本轮范围之外。

# Minecraft 26.2 验证结果

2026-10-10 完成 SimpleTask 2.0.1 的 26.2 API 升级与验证。
编译依赖为 `dev.folia:folia-api:26.2.build.7-beta`，构建和运行要求 Java 25。
NBTAPI 升级至 2.16.1、PlaceholderAPI 编译依赖升级至 2.12.3；Gradle 为 9.3.1。
CraftEngine 编译 API 保持 26.7，本轮运行验证使用 26.9.2。

## 已通过的验证

`./gradlew build validationProbeJar` 成功，JUnit 16 项全部通过。
离线测试覆盖全部 11 种任务类型的 JSON 往返、目标匹配、PAPI 表达式解析、
每日/每周/月度/相对/固定/永久过期判定和无重复的加权抽取。

以下三轮使用同一个生产 JAR，每轮 40 项功能检查全部通过，日志复核未发现未解释的
插件告警、异常或 Folia 线程归属错误；服务器均正常停止，没有强制终止。

| 运行环境 | 数据库与依赖 | 结果 | 本机原始报告 |
| --- | --- | --- | --- |
| Folia 26.2 build 7 | H2 2.3.232；无 CraftEngine | 40/40 PASS | [results.json](../reports/simpletask-folia-26.2-2ZRo09/results.json) |
| Folia 26.2 build 7 | MySQL 8.4；CraftEngine 26.9.2 | 40/40 PASS | [results.json](../reports/simpletask-folia-26.2-mtMszr/results.json) |
| Leaf 26.2 build 124（Paper 分支） | H2 2.3.232；无 CraftEngine | 40/40 PASS | [results.json](../reports/simpletask-leaf-26.2-FrcqWY/results.json) |

三轮均加载 NBTAPI 2.16.1、XConomy-Paper 2.26.3、VaultUnlocked 2.20.1 和本地
PlaceholderAPI 2.12.3 Folia 构建。所有发行包的实际 SHA-256 见
[artifacts.json](automation/artifacts.json)。PAPI 测试针对这一固定二进制和测试扩展，
不将相同版本号的其它发行包或其它扩展自动视为已验证。

生产产物：`build/libs/SimpleTask-2.0.1.jar`。
SHA-256：`0a2964e26eda706d17f19176e161fd789df104f5379abe570a42b87b888efc54`。
JAR 内 `plugin.yml` 的插件版本为 2.0.1、API 版本为 26.2；不含探针或测试类。
报告和服务器工作目录不进入 Git；本文中的报告链接指向本次工作区保留的证据。

## 功能覆盖

| 功能 | 本轮检查方式与结果 |
| --- | --- |
| CHAT / COMMAND | 原生 26.2 客户端聊天、带参数命令触发进度与完成 |
| CRAFT | 真实服务器上的 Bukkit 事件 fixture；普通点击计 4、Shift 点击计 8，合计 12；取消事件不计数 |
| FISH / CONSUME / KILL / BREED | 对应 Bukkit 事件 fixture 到任务完成及数据库保存；FISH 按钓获事件次数计数 |
| BREAK | 破坏事件 fixture 计数；取消事件和玩家放置的方块不计数 |
| HARVEST | 同次掉落的 2＋3 个马铃薯合并为 5，自动奖励只发一次 |
| SUBMIT | 原生 GUI 点击实际扣物品、刷新 GUI、保存进度；NBT 不匹配时拒绝提交；自动领奖分类自动发奖 |
| PAPI | 周期轮询读取真实玩家等级；测试扩展断言玩家线程归属；达到阈值后完成和自动发奖 |
| GUI | 主分类、分类跳转、管理员界面、提交、领奖、刷新均通过真实网络窗口与点击验证 |
| 奖励 | 手动/自动金币、物品、控制台命令；重复领取不重复发奖；背包满时物品掉落玩家脚下 |
| CraftEngine | 26.9.2 的自定义物品构建、数量、物品 ID、自定义方块放置与 ID；未安装时不存在的自定义物品返回屏障 |
| 刷新 | 付费扣费、次数递增一次、上限、管理员重置、强制刷新；无替代模板时保留任务并回滚次数 |
| 配置与模板 | `tasks.yml`＋`tasks_extra.yml` 导入、配置重载、从数据库重载模板 |
| 管理 | 删除玩家任务、分配指定任务且保留其它分类缓存、强制刷新后的立即可用缓存 |
| 过期 | 将测试任务移至两年前，经 GUI 触发每日/每周/月度/相对过期；永久任务保留；固定活动结束后删除且不补任务 |
| 持久化 | H2 与 MySQL 的完成/领奖状态及余额在客户端重连、服务器正常重启后保持一致 |

## 验证中修复的问题

- PAPI 轮询、管理员 GUI 和提交后的 GUI 更新改用玩家所属线程。
- SUBMIT 完成时使用统一完成流程，遵守分类的自动领奖配置。
- 管理员 `assign` 合并新任务缓存，不再清空其它分类。
- 刷新成功后立即加载该分类的新任务缓存；失败时回滚任务删除和刷新次数。
- XConomy 和 PlaceholderAPI 只在依赖插件已成功启用时激活。
- 未安装 CraftEngine 时，自定义物品创建先检查可用性。
- 重新生成 Gradle 9.3.1 wrapper，修复旧脚本的参数处理错误，并支持 Java 25。

早期失败轮次及日志仍保存在 `validation/reports/`。其中验证配置缺少 XConomy 所需
的 Vault、初始 GUI 数据包尚未包含物品、领奖后的旧刷新窗口尚未结束，以及恢复
固定活动配置后过早发送客户端请求，属于测试环境或驱动问题；最终驱动等待实际
物品、窗口刷新和配置生效。Leaf 首轮固定活动恢复步骤因此超时，修正等待后完整复验
为 PASS。功能性缓存、自动领奖和事务问题则在生产代码中修复后复验。

`log-audit.json` 使用最终日志规则重新检查三轮完整日志。故意请求
`missing:unknown` 的一条告警单独识别；原始日志保留离线测试服、终端、JOML Unsafe
和 Legacy Material 初始化的环境提示，不将它们描述为插件异常。

## 边界与复现

事件 fixture 验证真实 26.2 服务端上的监听、进度、数据库和奖励链路，
没有模拟真人客户端完整的钓鱼、合成、食用或繁殖动作。锻造台合成、蛋糕食用、
右键收获和多层甘蔗等监听分支未逐项实测。没有验证普通 Paper 官方发行包；Leaf
结果仅作为 Paper 分支的运行证据。MySQL 测试使用新建的隔离数据库，没有连接
生产数据库，也没有执行双服务器同步或已有旧表的升级迁移测试。

本轮没有覆盖其它 PAPI 扩展、生产权限/聊天/代理插件组合、全部管理命令、
长期定时刷新、跨 region 玩家互动、GUI 拖拽/全部分页操作、压力及并发故障注入。
这些范围不应仅依据编译成功或本轮 PASS 推断为已通过。

可重复操作和依赖准备见 [README.md](README.md)。

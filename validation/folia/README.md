# SimpleTask 26.2 兼容性验证

生产构建及离线回归：

```sh
JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ./gradlew build validationProbeJar
```

`SimpleTaskValidation.jar` 是独立测试插件，不会打入生产 JAR。
测试服只绑定 `127.0.0.1`，每轮创建独立 `/tmp/simpletask-*-26.2-*` 目录，
使用全新的世界、H2 数据和 XConomy 数据。测试进程正常停止后保留目录和证据。
驱动只管理自身启动的服务器；端口占用时直接失败。

准备 Node.js 22+ 协议依赖：

```sh
cd validation/folia/automation
npm run protocol:prepare
npm ci --ignore-scripts
```

协议数据使用固定的 Minecraft Data 上游提交和官方生成器，原生 26.2 协议号 776；
不需要 ViaVersion。已有本地验证缓存时可直接复用 `cache/minecraft-data-26.2-68ea7b59-308134a8.tgz`。

服务端和依赖插件由 `artifacts.json` 固定 SHA-256。Folia、Leaf、Mojang、NBTAPI、
XConomy 和 Vault 有下载 URL；PAPI 和 CraftEngine 使用相邻项目实服验证中已保存的
本地发行包，须将相同哈希的文件放入 `automation/cache/`。
本轮 PAPI 为 SimpleHomeland 验证所用的 2.12.3 Folia 构建，不能用版本号相同但哈希
不同的 JAR 替代本轮证据。CraftEngine 为 `thw_servers/26.2/upgrades/` 的 26.9.2。

阅读并接受 https://aka.ms/MinecraftEULA 后运行：

```sh
npm run validate -- --accept-eula
npm run validate -- --accept-eula --leaf --port=25633
```

可以通过 `SIMPLETASK_LIBRARY_CACHE=/path/to/stopped/test-server/libraries` 复用已停止的
26.2 测试服依赖缓存；只复制 `libraries`，不复制数据库、世界或业务配置。

MySQL 场景使用专用测试库 `simpletask_qa` 和测试账户 `simpletask_qa`，测试密码
`qa_test_only`。例如创建独立、可丢弃的 Docker 容器：

```sh
docker run --detach --rm --name simpletask-qa-mysql-26-2 \
  --publish 127.0.0.1:23316:3306 \
  --env MYSQL_RANDOM_ROOT_PASSWORD=yes \
  --env MYSQL_DATABASE=simpletask_qa \
  --env MYSQL_USER=simpletask_qa \
  --env MYSQL_PASSWORD=qa_test_only mysql:8.4
docker exec --env MYSQL_PWD=qa_test_only simpletask-qa-mysql-26-2 \
  mysql --user=simpletask_qa --database=simpletask_qa --execute='SELECT 1'
npm run validate -- --accept-eula --ce --mysql-port=23316 --port=25632
docker stop simpletask-qa-mysql-26-2
```

待 MySQL 查询成功再启动测试。每轮需使用空的测试库；不得指定已有业务数据库。
容器由运行者创建和停止，驱动不管理其它数据库进程。

证据保存至 `validation/reports/<测试目录名>/`，包含逐项 JSON 结果、插件哈希、
服务端日志、客户端事件和玩家所属线程的状态快照。`PASS` 要求全部功能步骤成功、
正常停服且没有未解释的插件告警、异常、线程归属错误。故意请求不存在的 CE 物品会
产生一条精确匹配的已知告警；其它告警不会按此规则忽略。

驱动先执行原有 40 项回归，再执行 `content.mjs` 的内容专项检查。
CHAT、COMMAND、GUI、SUBMIT、手动领奖/刷新、代表性食物/饮品的食用、蛋糕操作、
作物破坏/右键收获及硫磺/朱砂挖掘来自原生协议客户端。
全部可食用物品 ID、合成/锻造结果组件、钓获和繁殖使用玩家所属线程上的 Bukkit
事件 fixture；新生物击杀使用实际服务端伤害与死亡事件。
事件 fixture 不代表真人实际完成钓鱼、合成、锻造或繁殖的端到端验收。
测试玩家使用生存模式并设置无敌，避免超平坦世界自然刷怪干扰；每次食用前明确
设置饱食度，客户端仍需完成实际消费动作。
过期场景将隔离测试任务的分配时间移至两年前，再通过真实 GUI 请求触发过期处理。
PAPI 测试扩展读取玩家等级，并明确要求在玩家所属线程执行。

本轮结论、覆盖边界及原始报告见 [RESULTS.md](RESULTS.md) 和
[CONTENT-26.2.md](CONTENT-26.2.md)。

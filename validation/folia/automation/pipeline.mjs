import assert from 'node:assert/strict';
import { copyFile, cp, mkdir, mkdtemp, readFile, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { appendFileSync } from 'node:fs';
import { artifacts, driver, root, sha256, verifiedDownload, offlineUuid } from './environment.mjs';
import { assertPortFree, managedProcess, until, logIssues } from './harness.mjs';
import { connectBot } from './bot.mjs';
import { itemName } from './protocol.mjs';

const args = process.argv.slice(2);
const platform = args.includes('--leaf') ? 'leaf' : 'folia';
const withCE = args.includes('--ce');
const port = Number(args.find(x => x.startsWith('--port='))?.split('=')[1] ?? 25631);
const mysqlPort = Number(args.find(x => x.startsWith('--mysql-port='))?.split('=')[1] ?? 0);
if (!args.includes('--accept-eula')) throw new Error('Read https://aka.ms/MinecraftEULA and pass --accept-eula before starting a test server.');
await assertPortFree(port);
const workspace = await mkdtemp(join(tmpdir(), `simpletask-${platform}-26.2-`));
const reportDir = join(root, 'validation/reports', workspace.split('/').at(-1));
await mkdir(reportDir, { recursive: true });
const report = { platform, withCE, workspace, port, steps: [], status: 'RUNNING',
  eventFixtures: ['CRAFT', 'FISH', 'CONSUME', 'BREAK', 'HARVEST', 'KILL', 'BREED'],
  nativeProtocol: ['CHAT', 'COMMAND', 'GUI', 'SUBMIT', 'manual reward', 'reroll'],
  notRun: ['real fishing/crafting/eating/breeding client mechanics', 'production proxy', 'cross-server MySQL synchronization', 'long-running load'] };
report.database = mysqlPort ? 'MySQL 8.4' : 'H2 2.3.232';
await mkdir(join(workspace, 'plugins/SimpleTask'), { recursive: true });
await mkdir(join(workspace, 'cache'), { recursive: true });
for (const name of [platform, 'mojang', 'nbt', 'papi', 'economy', 'vault', ...(withCE ? ['ce'] : [])]) {
  const artifact = artifacts[name];
  const path = join(driver, 'cache', artifact.filename);
  await verifiedDownload(artifact, path);
  await copyFile(path, join(workspace, name === platform ? 'server.jar' : name === 'mojang' ? `cache/${artifact.filename}` : `plugins/${artifact.filename}`));
}
await copyFile(join(root, 'build/libs/SimpleTask-2.0.1.jar'), join(workspace, 'plugins/SimpleTask.jar'));
await copyFile(join(root, 'build/libs/SimpleTaskValidation.jar'), join(workspace, 'plugins/SimpleTaskValidation.jar'));
for (const name of ['config.yml', 'tasks.yml', 'tasks_extra.yml']) {
  await copyFile(join(root, 'validation/folia/fixtures', name), join(workspace, 'plugins/SimpleTask', name));
}
if (mysqlPort) {
  const path = join(workspace, 'plugins/SimpleTask/config.yml');
  let config = await readFile(path, 'utf8');
  config = config.replace('type: h2', 'type: mysql').replace('port: 3306', `port: ${mysqlPort}`)
    .replace('database: simpletask', 'database: simpletask_qa').replace('username: root', 'username: simpletask_qa')
    .replace('password: password', 'password: qa_test_only');
  await writeFile(path, config);
}
await writeFile(join(workspace, 'eula.txt'), 'eula=true\n');
await writeFile(join(workspace, 'server.properties'), `server-ip=127.0.0.1\nserver-port=${port}\nonline-mode=false\nenforce-secure-profile=false\nview-distance=2\nsimulation-distance=2\nspawn-protection=0\nlevel-type=minecraft:flat\ngenerator-settings={"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains"}\ngenerate-structures=false\nmax-players=3\n`);
const username = 'ST_QA_Player';
await writeFile(join(workspace, 'ops.json'), JSON.stringify([{ uuid: offlineUuid(username), name: username, level: 4, bypassesPlayerLimit: true }]));
// Cache server dependencies by copying an already stopped local 26.2 validation's
// libraries if explicitly supplied. No world, database or credentials are reused.
const libraryCache = process.env.SIMPLETASK_LIBRARY_CACHE;
if (libraryCache) await cp(libraryCache, join(workspace, 'libraries'), { recursive: true });
await mkdir(join(workspace, 'plugins/XConomy/playerdata'), { recursive: true });
await copyFile(join(root, 'validation/folia/fixtures/xconomy.yml'), join(workspace, 'plugins/XConomy/config.yml'));
await mkdir(join(workspace, 'plugins/NBTAPI'), { recursive: true });
await writeFile(join(workspace, 'plugins/NBTAPI/config.yml'), 'bStats:\n  enabled: false\nupdateCheck:\n  enabled: false\n');
let server, bot, sequence = 0;
const startServer = () => managedProcess('/usr/lib/jvm/java-25-openjdk-amd64/bin/java',
  ['-Xms256M', '-Xmx1536M', '-XX:ActiveProcessorCount=4', '-Dfile.encoding=UTF-8', '-jar', 'server.jar', '--nogui'],
  { cwd: workspace, log: join(reportDir, 'server.log') });
const ready = async () => {
  await until('server startup', () => { server.check(); return /Done \(/.test(server.state.tail); }, { timeout: 180_000 });
  assert.match(server.state.tail, /SimpleTask has been enabled!/);
  assert.match(server.state.tail, /26\.2/);
};
const step = async (name, action) => {
  const entry = { name, status: 'RUNNING' }; report.steps.push(entry);
  try { entry.evidence = await action(); entry.status = 'PASS'; console.log(`PASS ${name}`); }
  catch (error) { entry.status = 'FAIL'; entry.error = error.stack; throw error; }
  finally { await writeFile(join(reportDir, 'results.json'), JSON.stringify(report, null, 2)); }
};
const probe = async (action, ...params) => {
  const id = `p${++sequence}`;
  server.command(`stprobe ${id} ${action} ${username} ${params.join(' ')}`.trim());
  const path = join(workspace, 'plugins/SimpleTaskValidation/evidence', id + '.json');
  const value = await until(`probe ${action}`, async () => {
    server.check();
    try { return JSON.parse(await readFile(path, 'utf8')); } catch (error) { if (error.code !== 'ENOENT' && !(error instanceof SyntaxError)) throw error; }
  });
  assert.ok(!value.error, value.error); return value;
};
const status = () => probe('status');
const taskState = (snapshot, key) => snapshot.tasks.find(t => t.key === key);
const waitTask = (key, predicate) => until(`task ${key}`, async () => {
  const snapshot = await status(); const task = taskState(snapshot, key);
  return task && predicate(task, snapshot) ? snapshot : false;
}, { timeout: 15_000, interval: 100 });
const openCategory = async category => {
  bot.closeWindow();
  bot.command('/task ' + category);
  await bot.wait('category menu', () => bot.state.current?.title === category && bot.state.current.items?.some(i => itemName(i).includes('qa_')));
};
const findTask = key => {
  const slot = bot.state.current.items.findIndex(i => itemName(i).includes(key));
  assert.ok(slot >= 0, `Task absent in GUI: ${key}`); return slot;
};
const claim = async (key, category = 'daily') => {
  await openCategory(category);
  const offset = bot.state.windows.length;
  bot.click(findTask(key));
  const result = await waitTask(key, (task, snapshot) => task.claimed && snapshot.database.find(r => r.key === key)?.claimed);
  await bot.wait('reward refresh menu', () => bot.state.windows.length > offset &&
    bot.state.current?.title === category && bot.state.current.items?.some(i => itemName(i).includes(key)));
  return result;
};
try {
  server = startServer(); await ready();
  report.artifacts = Object.fromEntries(Object.entries(artifacts).filter(([name]) => name !== 'protocolData'));
  report.jarSha256 = sha256(await readFile(join(workspace, 'plugins/SimpleTask.jar')));
  server.command('taskadmin import all');
  bot = connectBot(username, port, event => appendFileSync(join(reportDir, 'bot-events.jsonl'), JSON.stringify(event) + '\n'), () => server.check());
  await bot.ready();
  await step('import and automatic assignment, extra task file', async () => {
    return until('fifteen assigned tasks', async () => {
      const snapshot = await status();
      return snapshot.templates.length === 16 && snapshot.tasks.length === 15 && snapshot.database.length === 15 ? snapshot : false;
    }, { timeout: 20_000 });
  });
  await step('NBT components and matching on 26.2', async () => {
    const value = await probe('nbt'); assert.equal(value.exists, true); assert.equal(value.exact, true);
    assert.equal(value.numeric, true); assert.equal(value.wrong, false); return value;
  });
  await step('custom item behavior without/with CraftEngine', async () => {
    const value = await probe('ce', withCE ? 'default:topaz' : 'missing:unknown');
    if (withCE) {
      assert.equal(value.available, true); assert.equal(value.key, 'default:topaz');
      assert.equal(value.blockPlaced, true); assert.equal(value.blockKey, 'default:topaz_ore');
    }
    else { assert.equal(value.available, false); assert.equal(value.material, 'BARRIER'); }
    return value;
  });
  await step('main category GUI and category navigation', async () => {
    bot.closeWindow(); bot.command('/task');
    await bot.wait('main category window', () => bot.state.current?.items?.some(i => itemName(i).includes('daily')));
    const slot = bot.state.current.items.findIndex(i => itemName(i).includes('daily')); assert.ok(slot >= 0); bot.click(slot);
    await bot.wait('daily window from category click', () => bot.state.current?.title === 'daily' && bot.state.current.items?.some(i => itemName(i).includes('qa_')));
    return { title: bot.state.current.title, tasksVisible: bot.state.current.items.filter(i => itemName(i).startsWith('qa_')).length };
  });
  await step('admin GUI on owning Folia region', async () => {
    bot.closeWindow(); bot.command('/taskadmin');
    await bot.wait('admin GUI', () => bot.state.current?.items && bot.state.current.title !== 'daily');
    assert.equal((await status()).gui, 'AdminTaskGUI'); bot.closeWindow(); return { opened: true };
  });
  await step('CHAT from native protocol', async () => {
    bot.command('QA_TASK_CHAT'); return waitTask('qa_chat', task => task.completed);
  });
  await step('COMMAND with arguments from native protocol', async () => {
    bot.command('/stprobe_noop extra'); return waitTask('qa_command', task => task.completed);
  });
  await step('cancelled and placed block fixtures do not count', async () => {
    await probe('event', 'break-cancelled'); await probe('event', 'placed');
    assert.equal(taskState(await status(), 'qa_break').progress, 0); return { progress: 0 };
  });
  await step('CRAFT normal and shift click event fixtures', async () => {
    await probe('event', 'craft-cancelled'); assert.equal(taskState(await status(), 'qa_craft').progress, 0);
    await probe('event', 'craft'); await waitTask('qa_craft', task => task.progress === 4);
    await probe('event', 'shiftcraft'); return waitTask('qa_craft', task => task.completed && task.progress === 12);
  });
  for (const [event, key, target] of [['break', 'qa_break', 1], ['fish', 'qa_fish', 2], ['consume', 'qa_consume', 1], ['kill', 'qa_kill', 1], ['breed', 'qa_breed', 1]]) {
    await step(`${event.toUpperCase()} event fixture`, async () => {
      await probe('event', event);
      if (event === 'fish') {
        await waitTask(key, task => task.progress === 1);
        await probe('event', event);
      }
      return waitTask(key, task => task.completed && task.progress === target);
    });
  }
  await step('HARVEST combined drops and automatic reward', async () => {
    const before = await status(); await probe('event', 'harvest');
    const after = await waitTask('qa_harvest', (task, snapshot) => task.claimed && snapshot.commandRewards === before.commandRewards + 1);
    assert.equal(after.balance, before.balance + 5);
    assert.equal(after.inventory['minecraft:emerald'], (before.inventory['minecraft:emerald'] ?? 0) + 1); return after;
  });
  await step('PAPI polling evaluates on owning player region and grants once', async () => {
    const before = await status(); assert.equal(taskState(before, 'qa_papi').completed, false);
    await probe('level', '7');
    const after = await waitTask('qa_papi', (task, snapshot) => task.claimed && snapshot.commandRewards === before.commandRewards + 1);
    assert.equal(after.balance, before.balance + 5); return after;
  });
  await step('SUBMIT via native GUI consumes actual inventory and refreshes', async () => {
    await probe('give', 'minecraft:diamond', '2'); await openCategory('daily'); bot.click(findTask('qa_submit'));
    const after = await waitTask('qa_submit', task => task.completed);
    assert.equal(after.inventory['minecraft:diamond'] ?? 0, 0); assert.equal(after.gui, 'CategoryTaskGUI'); return after;
  });
  await step('SUBMIT with NBT rejects unmatched items and accepts components', async () => {
    await probe('give', 'minecraft:diamond', '1'); await openCategory('nbt'); bot.click(findTask('qa_nbt'));
    const rejected = await status(); assert.equal(taskState(rejected, 'qa_nbt').progress, 0);
    await probe('give', 'minecraft:diamond', '1', '7'); bot.click(findTask('qa_nbt'));
    const after = await waitTask('qa_nbt', task => task.completed); assert.equal(after.inventory['minecraft:diamond'], 1); return after;
  });
  await step('SUBMIT honors automatic category rewards', async () => {
    const before = await status(); await probe('give', 'minecraft:gold_ingot', '1');
    await openCategory('automatic'); bot.click(findTask('qa_auto_submit'));
    const after = await waitTask('qa_auto_submit', (task, snapshot) => task.claimed && snapshot.commandRewards === before.commandRewards + 1);
    assert.equal(after.inventory['minecraft:gold_ingot'] ?? 0, 0); assert.equal(after.balance, before.balance + 5); return after;
  });
  await step('manual money/item/command reward and duplicate claim prevention', async () => {
    const before = await status(); await claim('qa_chat');
    let after = await until('all reward channels', async () => { const s = await status(); return s.commandRewards === before.commandRewards + 1 && s.inventory['minecraft:emerald'] === before.inventory['minecraft:emerald'] + 1 ? s : false; });
    assert.equal(after.balance, before.balance + 5);
    await openCategory('daily'); bot.click(findTask('qa_chat')); after = await status();
    assert.equal(after.balance, before.balance + 5); assert.equal(after.commandRewards, before.commandRewards + 1); return after;
  });
  for (const key of ['qa_command', 'qa_craft', 'qa_break', 'qa_fish', 'qa_consume', 'qa_kill', 'qa_submit']) {
    await step(`manual claim ${key}`, () => claim(key));
  }
  await step('full inventory drops reward at player location', async () => {
    const before = await status(); await probe('fill'); await claim('qa_breed');
    const after = await until('overflow reward entity', async () => {
      const s = await status(); return s.droppedEmeralds > before.droppedEmeralds ? s : false;
    });
    assert.equal(after.inventory['minecraft:stone'], 36 * 64); await probe('clear'); return after;
  });
  await step('paid reroll count, max limit, and reset command', async () => {
    const before = await status();
    for (let i = 1; i <= 2; i++) {
      await openCategory('reroll');
      const slot = bot.state.current.items.findIndex(item => itemName(item).includes('刷新任务')); assert.ok(slot >= 0);
      const offset = bot.state.windows.length; bot.click(slot);
      await bot.wait('reroll reopens menu', () => bot.state.windows.length > offset && bot.state.current?.items);
      await until('reroll charge', async () => (await status()).balance === before.balance - i * 10);
      assert.equal((await status()).rerolls.find(r => r.category === 'reroll').count, i);
    }
    await openCategory('reroll');
    bot.click(bot.state.current.items.findIndex(item => itemName(item).includes('刷新')));
    assert.equal((await status()).balance, before.balance - 20);
    server.command(`taskadmin resetreroll reroll ${username}`);
    await until('reroll count reset', async () => (await status()).rerolls.find(r => r.category === 'reroll').count === 0);
    await openCategory('reroll'); return status();
  });
  await step('remove, assign, and force reroll admin commands', async () => {
    bot.closeWindow(); server.command(`taskadmin remove expiry qa_expiry ${username}`);
    await until('removed task', async () => !taskState(await status(), 'qa_expiry'));
    server.command(`taskadmin assign expiry qa_expiry ${username}`);
    const assigned = await waitTask('qa_expiry', task => task.progress === 0);
    assert.equal(assigned.tasks.length, 15, 'assign must preserve all other category caches');
    server.command(`taskadmin rerollall expiry ${username}`);
    return waitTask('qa_expiry', task => task.progress === 0);
  });
  await step('failed reroll with no alternative preserves task and count', async () => {
    const before = await status(); await openCategory('expiry');
    const offset = bot.state.messages.length; bot.click(50);
    await bot.wait('no alternative template message', () => bot.state.messages.slice(offset).some(m => m.text.includes('没有更多可用')));
    const after = await status();
    assert.equal(taskState(after, 'qa_expiry').progress, taskState(before, 'qa_expiry').progress);
    assert.equal(after.database.length, before.database.length);
    assert.equal(after.rerolls.find(r => r.category === 'expiry')?.count ?? 0, 0); return after;
  });
  await step('configuration and database template reload', async () => {
    server.command('taskadmin reloadconfig'); server.command('taskadmin reloadfromdb');
    return until('reloaded sixteen templates', async () => { const s = await status(); return s.templates.length === 16 ? s : false; });
  });
  for (const policy of ['daily', 'weekly', 'monthly', 'relative']) {
    await step(`${policy} expiration removes old task and assigns new progress`, async () => {
      bot.closeWindow(); const aged = await probe('age', 'expiry', policy); assert.equal(aged.aged, 1);
      assert.equal(taskState(await status(), 'qa_expiry').progress, 1);
      await openCategory('expiry');
      return waitTask('qa_expiry', (task, snapshot) => task.progress === 0 && !task.completed &&
        snapshot.database.find(row => row.key === 'qa_expiry')?.progress === 0);
    });
  }
  await step('permanent task survives old assignment time', async () => {
    bot.closeWindow(); await probe('age', 'expiry', 'permanent'); await openCategory('expiry');
    const after = await status(); assert.equal(taskState(after, 'qa_expiry').progress, 1); return after;
  });
  await step('ended fixed period removes task and blocks new assignment', async () => {
    bot.closeWindow(); await probe('age', 'expiry', 'fixed'); bot.command('/task expiry');
    await until('fixed period task removed', async () => !taskState(await status(), 'qa_expiry'));
    server.command('taskadmin reloadconfig');
    await until('restored permanent policy', async () => (await status()).expiryPolicy === 'PERMANENT');
    await openCategory('expiry'); return waitTask('qa_expiry', task => task.progress === 0);
  });
  const persistent = await status();
  await step('disconnect/reconnect preserves completed and claimed progress', async () => {
    bot.closeWindow(); await bot.close();
    await until('server saw disconnect', () => /ST_QA_Player left the game/.test(server.state.tail));
    bot = connectBot(username, port, () => {}, () => server.check()); await bot.ready();
    const after = await waitTask('qa_chat', task => task.completed && task.claimed); assert.equal(after.balance, persistent.balance); return after;
  });
  await step('normal shutdown and restart preserves database tasks', async () => {
    await bot.close(); report.firstShutdown = await server.stop(); assert.equal(report.firstShutdown.code, 0); assert.equal(report.firstShutdown.forced, false);
    server = startServer(); await ready(); bot = connectBot(username, port, () => {}, () => server.check()); await bot.ready();
    const after = await waitTask('qa_chat', task => task.completed && task.claimed);
    assert.equal(after.balance, persistent.balance); assert.equal(after.database.length, persistent.database.length); return after;
  });
  report.status = 'PASS';
} catch (error) {
  report.status = 'FAIL'; report.error = error.stack; console.error(error.stack);
} finally {
  if (bot) await bot.close();
  if (server) report.shutdown = await server.stop();
  const logs = await readFile(join(reportDir, 'server.log'), 'utf8').catch(() => '');
  report.logIssues = logIssues(logs);
  report.expectedLogs = logs.split('\n').filter(line => /\[SimpleTask\] 未知的物品: missing:unknown$/.test(line));
  if (report.status === 'PASS' && report.logIssues.length) report.status = 'NEEDS_REVIEW';
  if (report.shutdown?.forced || report.shutdown?.code !== 0) report.status = 'FAIL';
  await cp(join(workspace, 'plugins/SimpleTaskValidation/evidence'), join(reportDir, 'probe'), { recursive: true }).catch(() => {});
  await writeFile(join(reportDir, 'results.json'), JSON.stringify(report, null, 2));
  console.log(`${report.status}: ${reportDir}`);
  if (report.status !== 'PASS') process.exitCode = 1;
}

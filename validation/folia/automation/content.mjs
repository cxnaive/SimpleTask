import assert from 'node:assert/strict';
import minecraftData from 'minecraft-data';
import { until } from './harness.mjs';

export async function checkContent({ probe, bot, step, status, waitTask, openCategory, findTask, report }) {
  const current = minecraftData('26.2');
  const previous = minecraftData('26.1');
  const original = minecraftData('1.21.4');
  const delta = (key, source = previous) => {
    const names = new Set(source[key].map(value => value.name));
    return current[key].filter(value => !names.has(value.name)).map(value => value.name);
  };
  const newContent = Object.fromEntries(['blocksArray', 'itemsArray', 'entitiesArray', 'foodsArray'].map(key => [key, delta(key)]));
  const sinceOriginal = Object.fromEntries(['blocksArray', 'itemsArray', 'entitiesArray'].map(key => [key, delta(key, original)]));
  let registry;
  await step('26.2 content delta and Bukkit item/entity registry', async () => {
    registry = await probe('registry', sinceOriginal.itemsArray.join(','), sinceOriginal.entitiesArray.join(','));
    for (const item of sinceOriginal.itemsArray) assert.equal(registry.items[`minecraft:${item}`], `minecraft:${item}`);
    for (const entity of sinceOriginal.entitiesArray) assert.equal(registry.entities[entity], `minecraft:${entity}`);
    assert.equal(registry.oldChain, 'minecraft:iron_chain');
    assert.equal(registry.newEntity, 'minecraft:sulfur_cube');
    assert.deepEqual(newContent.foodsArray, []);
    assert.ok(!newContent.blocksArray.some(name => current.blocksByName[name].states.some(state => state.name === 'age')));
    report.contentDelta = newContent;
    report.contentSinceOriginalAPI = sinceOriginal;
    return { newContent, sinceOriginal, registry };
  });

  const task = (type, target, condition) => probe('content-task', type, target, ...(condition ? [condition] : []));
  const progress = snapshot => snapshot.tasks.find(value => value.key === 'qa_content')?.progress;
  const expectProgress = value => waitTask('qa_content', item => item.progress === value);
  const blockState = () => probe('block-state');
  const prepare = async (data, placement = 'natural') => {
    const block = await probe('prepare-block', data, placement);
    return { x: block.x, y: block.y, z: block.z };
  };
  const dig = async location => {
    bot.digBlock(location);
    await new Promise(resolve => setTimeout(resolve, 600));
    bot.digBlock(location, 2);
    return until('block destroyed by native client', async () => {
      const state = await blockState();
      return ['AIR', 'WATER'].includes(state.type) ? state : false;
    });
  };

  await step('all Bukkit edible items and drinks through CONSUME fixtures, cancellation and offhand', async () => {
    const keys = [...new Set([...registry.foods, 'minecraft:potion', 'minecraft:milk_bucket'])];
    await task('CONSUME', keys.join(','));
    await probe('content-event', 'consume-cancelled', keys[0]);
    assert.equal(progress(await status()), 0);
    for (const [index, key] of keys.entries()) {
      await probe('content-event', 'consume', key);
      await expectProgress(index + 1);
    }
    return { eventFixtures: keys, consumed: progress(await status()), hand: 'OFF_HAND', cancelledProgress: 0 };
  });

  for (const [key, hunger, component] of [
    ['minecraft:apple', 10], ['minecraft:honey_bottle', 20], ['minecraft:suspicious_stew', 10],
    ['minecraft:potion', 20], ['minecraft:milk_bucket', 20], ['minecraft:chorus_fruit', 20],
    ['minecraft:golden_apple', 20], ['minecraft:stone', 20, 'component']
  ]) {
    await step(`native CONSUME ${key}${component ? ' with food/consumable components' : ''}`, async () => {
      await task('CONSUME', key);
      await probe('prepare-food', key, String(hunger), ...(component ? [component] : []));
      bot.useItem();
      const after = await expectProgress(1);
      assert.equal(after.inventory[key] ?? 0, 0);
      return { key, hungerBefore: hunger, hungerAfter: after.food, inventory: after.inventory, progress: progress(after) };
    });
  }

  await step('native cake: candles, ignition, extinguishing and full hunger do not count; eating does', async () => {
    await task('CONSUME', 'minecraft:cake');
    const location = await prepare('minecraft:cake[bites=0]');
    await probe('prepare-food', 'minecraft:candle', '10');
    bot.useBlock(location);
    await until('candle placed on cake', async () => (await blockState()).type === 'CANDLE_CAKE');
    assert.equal(progress(await status()), 0);
    await probe('prepare-food', 'minecraft:flint_and_steel', '10');
    bot.useBlock(location, 0.75);
    await until('candle lit', async () => (await blockState()).data.includes('lit=true'));
    assert.equal(progress(await status()), 0);
    await probe('prepare-food', 'minecraft:air', '10');
    bot.useBlock(location, 0.75);
    await until('candle extinguished', async () => (await blockState()).data.includes('lit=false'));
    assert.equal(progress(await status()), 0);
    const before = await status();
    bot.useBlock(location);
    const after = await expectProgress(1);
    assert.equal(after.cakeSlices, before.cakeSlices + 1);
    assert.equal((await blockState()).data, 'minecraft:cake[bites=1]');
    await probe('prepare-food', 'minecraft:air', '20'); bot.useBlock(location);
    await new Promise(resolve => setTimeout(resolve, 300));
    assert.equal(progress(await status()), 1);
    assert.equal((await blockState()).data, 'minecraft:cake[bites=1]');
    return { progress: 1, cakeSlices: after.cakeSlices, excludedActions: ['place candle', 'light candle', 'extinguish candle', 'full hunger'] };
  });

  await step('native plain cake counts every slice, including the final disappearing slice', async () => {
    await task('CONSUME', 'minecraft:cake');
    const location = await prepare('minecraft:cake[bites=0]');
    for (let slice = 1; slice <= 7; slice++) {
      await probe('prepare-food', 'minecraft:air', '10'); bot.useBlock(location);
      await expectProgress(slice);
    }
    assert.equal((await blockState()).type, 'AIR');
    return { progress: progress(await status()), slices: 7, cakeRemoved: true };
  });

  for (const [data, target, expected, placement] of [
    ['minecraft:torchflower_crop[age=1]', 'minecraft:torchflower', 0],
    ['minecraft:torchflower', 'minecraft:torchflower', 1, 'grown'],
    ['minecraft:torchflower', 'minecraft:torchflower', 0, 'placed'],
    ['minecraft:cactus_flower', 'minecraft:cactus_flower', 1],
    ['minecraft:cactus_flower', 'minecraft:cactus_flower', 0, 'placed'],
    ['minecraft:pitcher_crop[age=3,half=lower]', 'minecraft:pitcher_crop', 0],
    ['minecraft:pitcher_crop[age=4,half=lower]', 'minecraft:pitcher_crop', 1],
    ['minecraft:pitcher_crop[age=4,half=lower]', 'minecraft:pitcher_crop', 1, 'upper'],
    ['minecraft:sweet_berry_bush[age=1]', 'minecraft:sweet_berries', 0],
    ['minecraft:cave_vines[berries=false,age=0]', 'minecraft:glow_berries', 0],
    ['minecraft:cave_vines_plant[berries=true]', 'minecraft:cave_vines_plant', 1]
  ]) {
    await step(`native HARVEST break ${data} ${placement ?? 'natural'}`, async () => {
      await task('HARVEST', target);
      const location = await prepare(data, placement);
      const destroyed = await dig(location);
      const after = expected ? await expectProgress(expected) : await status();
      assert.equal(progress(after), expected);
      return { target, expected, destroyed, progress: progress(after), growthFixture: placement === 'grown' };
    });
  }

  await step('native bonemeal-grown torchflower clears seed placement protection', async () => {
    await task('HARVEST', 'minecraft:torchflower_crop');
    const location = await prepare('minecraft:torchflower_crop[age=1]', 'placed');
    await probe('prepare-food', 'minecraft:bone_meal', '10'); bot.useBlock(location);
    await until('torchflower matured by bonemeal', async () => (await blockState()).type === 'TORCHFLOWER');
    await probe('prepare-food', 'minecraft:air', '10'); await dig(location);
    return { progress: progress(await expectProgress(1)), nativeGrowth: true };
  });

  for (const data of ['minecraft:cave_vines[berries=true,age=0]', 'minecraft:cave_vines_plant[berries=true]']) {
    await step(`native HARVEST right click ${data}`, async () => {
      await task('HARVEST', 'minecraft:glow_berries');
      const location = await prepare(data); bot.useBlock(location);
      const after = await expectProgress(1);
      assert.ok((await blockState()).data.includes('berries=false'));
      bot.useBlock(location);
      await new Promise(resolve => setTimeout(resolve, 300));
      assert.equal(progress(await status()), 1);
      return { progress: progress(after), emptySecondHarvest: true };
    });
  }
  await step('native HARVEST three-block kelp column counts once and excludes placed base', async () => {
    await task('HARVEST', 'minecraft:kelp');
    const location = await prepare('kelp-column', 'placed');
    await dig(location); const after = await expectProgress(2);
    await new Promise(resolve => setTimeout(resolve, 300));
    assert.equal(progress(await status()), 2);
    return { progress: progress(after), excludedBase: true, mixedKelpAndKelpPlant: true };
  });
  await step('native BREAK sulfur and cinnabar blocks', async () => {
    await task('BREAK', 'minecraft:sulfur,minecraft:cinnabar');
    for (const [index, data] of ['minecraft:sulfur', 'minecraft:cinnabar'].entries()) {
      await dig(await prepare(data)); await expectProgress(index + 1);
    }
    return { progress: progress(await status()) };
  });
  await step('CRAFT actual result components and cancellation on 26.2', async () => {
    await task('CRAFT', 'minecraft:sulfur_slab', 'minecraft:custom_data.qa+7');
    await probe('content-event', 'craft-cancelled', 'minecraft:sulfur_slab');
    assert.equal(progress(await status()), 0);
    await probe('content-event', 'craft', 'minecraft:sulfur_slab');
    const after = await expectProgress(1);
    return { eventFixture: true, actualResultComponent: 7, recipeResultComponent: 'absent', progress: progress(after) };
  });
  await step('smithing CRAFT result components and cancellation on 26.2', async () => {
    await task('CRAFT', 'minecraft:netherite_sword', 'minecraft:custom_data.qa+7');
    await probe('content-event', 'smith-cancelled', 'minecraft:netherite_sword');
    assert.equal(progress(await status()), 0);
    await probe('content-event', 'smith', 'minecraft:netherite_sword');
    return { eventFixture: true, progress: progress(await expectProgress(1)) };
  });
  await step('native GUI SUBMIT accepts new sulfur items', async () => {
    await task('SUBMIT', 'minecraft:sulfur'); await probe('clear'); await probe('give', 'minecraft:sulfur', '1');
    await openCategory('content'); bot.click(findTask('qa_content'));
    const after = await waitTask('qa_content', value => value.completed);
    assert.equal(after.inventory['minecraft:sulfur'] ?? 0, 0);
    return { progress: progress(after), consumedItem: true };
  });
  await step('native GUI SUBMIT preserves old chain target after iron_chain rename', async () => {
    await task('SUBMIT', 'minecraft:chain'); await probe('clear'); await probe('give', 'minecraft:iron_chain', '1');
    await openCategory('content'); bot.click(findTask('qa_content'));
    const after = await waitTask('qa_content', value => value.completed);
    assert.equal(after.inventory['minecraft:iron_chain'] ?? 0, 0);
    return { progress: progress(after), oldTarget: 'minecraft:chain', actualItem: 'minecraft:iron_chain' };
  });
  await step('KILL new mobs since 1.21.4 through real server damage and death events', async () => {
    const mobs = ['camel_husk', 'copper_golem', 'happy_ghast', 'nautilus', 'parched', 'sulfur_cube', 'zombie_nautilus'];
    await task('KILL', mobs.map(mob => `minecraft:${mob}`).join(','));
    for (const [index, mob] of mobs.entries()) {
      await probe('content-event', 'kill', `minecraft:${mob}_spawn_egg`);
      await expectProgress(index + 1);
    }
    return { serverDamage: true, mobs, progress: progress(await status()) };
  });
  await step('BREED nautilus event fixture matches new animal and respects cancellation', async () => {
    await task('BREED', 'minecraft:nautilus');
    await probe('content-event', 'breed-cancelled');
    assert.equal(progress(await status()), 0);
    await probe('content-event', 'breed');
    return { eventFixture: true, progress: progress(await expectProgress(1)) };
  });
  await step('FISH accepts arbitrary caught items and respects cancellation on 26.2', async () => {
    await task('FISH', 'minecraft:music_disc_bounce');
    await probe('content-event', 'fish-cancelled', 'minecraft:music_disc_bounce');
    assert.equal(progress(await status()), 0);
    await probe('content-event', 'fish', 'minecraft:music_disc_bounce');
    return { eventFixture: true, notVanillaFishingLoot: true, progress: progress(await expectProgress(1)) };
  });
  report.nativeProtocol.push('food and drink consumption', 'cake consumption and candle interactions', 'crop breaking and right-click harvest', 'sulfur/cinnabar breaking');
  report.notRun = report.notRun.filter(value => value !== 'real fishing/crafting/eating/breeding client mechanics');
  report.notRun.push('real fishing, crafting, smithing and breeding client mechanics', 'sulfur cube splitting/swallowing are not BREED/player CONSUME tasks');
}

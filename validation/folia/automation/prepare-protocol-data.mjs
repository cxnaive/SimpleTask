import { mkdir, mkdtemp, readFile, rename, rm, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { artifacts, driver, exec, verifiedDownload } from './environment.mjs';

// Upstream has 26.2 data but has not published its Node wrapper yet. Package the
// pinned official sources with their own generator; never patch node_modules.
const cache = join(driver, 'cache');
await mkdir(cache, { recursive: true });
const sources = artifacts.protocolData;
for (const [name, artifact] of Object.entries(sources)) {
  await verifiedDownload(artifact, join(cache, `protocol-${name}-${artifact.commit}.tar.gz`));
}
const workspace = await mkdtemp(join(cache, 'protocol-package-'));
try {
  const wrapper = join(workspace, 'wrapper');
  await mkdir(wrapper);
  await exec('tar', ['-xzf', join(cache, `protocol-wrapper-${sources.wrapper.commit}.tar.gz`),
    '--strip-components=1', '-C', wrapper]);
  await exec('tar', ['-xzf', join(cache, `protocol-data-${sources.data.commit}.tar.gz`),
    '--strip-components=1', '-C', join(wrapper, 'minecraft-data')]);
  await exec(process.execPath, ['bin/generate_data.js'], { cwd: wrapper });
  const { stdout } = await exec(process.execPath, ['-e',
    "const data = require('./')('26.2'); if (!data || data.version.version !== 776) process.exit(1); console.log(JSON.stringify(data.version));"],
  { cwd: wrapper });
  const paths = JSON.parse(await readFile(join(wrapper, 'minecraft-data/data/dataPaths.json')));
  if (paths.pc['26.2'].protocol !== 'pc/26.2') throw new Error('26.2 must use its native upstream protocol schema');
  // npm 10 runs upstream's TypeScript prepare even with --ignore-scripts. The
  // generated runtime package needs no lifecycle scripts or dev dependencies.
  const manifestPath = join(wrapper, 'package.json');
  const manifest = JSON.parse(await readFile(manifestPath));
  delete manifest.scripts;
  delete manifest.devDependencies;
  manifest.simpletaskProtocolSources = sources;
  await writeFile(manifestPath, JSON.stringify(manifest, null, 2) + '\n');
  const packed = await exec('npm', ['pack', '--ignore-scripts', '--json', '--pack-destination', workspace],
    { cwd: wrapper, maxBuffer: 10_000_000 });
  const filename = `minecraft-data-26.2-${sources.data.commit.slice(0, 8)}-${sources.wrapper.commit.slice(0, 8)}.tgz`;
  await rename(join(workspace, JSON.parse(packed.stdout)[0].filename), join(cache, filename));
  console.log(`Prepared official protocol data: ${stdout.trim()}`);
  console.log('Run npm ci --ignore-scripts to install the locked protocol dependencies.');
} finally {
  await rm(workspace, { recursive: true, force: true });
}

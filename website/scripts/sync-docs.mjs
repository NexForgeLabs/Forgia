import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const websiteRoot = path.resolve(__dirname, '..');
const repoRoot = path.resolve(websiteRoot, '..');
const srcDocsDir = path.join(repoRoot, 'docs');
const dstDocsDir = path.join(websiteRoot, 'src', 'content', 'docs');

const BASE = '/Forgia';

const mappings = [
  ['README.en.md', 'overview.md', 'Overview'],
  ['README.zh.md', 'zh/overview.md', '项目介绍'],
  ['architecture.en.md', 'architecture.md', 'Architecture: Stock vs. Forgia'],
  ['architecture.zh.md', 'zh/architecture.md', '架构对比：原版 vs Forgia'],
  ['benchmarks.en.md', 'benchmarks.md', 'Live Benchmarks'],
  ['benchmarks.zh.md', 'zh/benchmarks.md', '线上实测性能'],
  ['configuration.en.md', 'configuration.md', 'Configuration & Tuning'],
  ['configuration.zh.md', 'zh/configuration.md', '配置与硬件调优'],
  ['status.en.md', 'status.md', 'Engineering Status'],
  ['status.zh.md', 'zh/status.md', '工程进度与状态'],
  ['modules/README.en.md', 'modules/index.md', 'Engine Modules'],
  ['modules/README.zh.md', 'zh/modules/index.md', '引擎模块总览'],
  ['modules/region-engine.en.md', 'modules/region-engine.md', 'Region Engine'],
  ['modules/region-engine.zh.md', 'zh/modules/region-engine.md', '区域调度引擎'],
  ['modules/entity-ticks.en.md', 'modules/entity-ticks.md', 'Entity Ticks'],
  ['modules/entity-ticks.zh.md', 'zh/modules/entity-ticks.md', '实体 Tick 切片'],
  ['modules/block-entities.en.md', 'modules/block-entities.md', 'Block Entities'],
  ['modules/block-entities.zh.md', 'zh/modules/block-entities.md', '方块实体沙箱与波次'],
  ['modules/chunk-ticks.en.md', 'modules/chunk-ticks.md', 'Chunk Ticks'],
  ['modules/chunk-ticks.zh.md', 'zh/modules/chunk-ticks.md', '区块随机刻'],
  ['modules/natural-spawning-and-poi.en.md', 'modules/natural-spawning-and-poi.md', 'Natural Spawning & POI'],
  ['modules/natural-spawning-and-poi.zh.md', 'zh/modules/natural-spawning-and-poi.md', '自然刷怪与 POI'],
  ['modules/cross-region-and-scheduled.en.md', 'modules/cross-region-and-scheduled.md', 'Cross-Region & Scheduled Ticks'],
  ['modules/cross-region-and-scheduled.zh.md', 'zh/modules/cross-region-and-scheduled.md', '跨区传播与计划刻'],
  ['modules/observability.en.md', 'modules/observability.md', 'Observability & Probes'],
  ['modules/observability.zh.md', 'zh/modules/observability.md', '可观测性与探针'],
  ['modules/player-ticks.en.md', 'modules/player-ticks.md', 'Experimental Player Ticks'],
  ['modules/player-ticks.zh.md', 'zh/modules/player-ticks.md', '实验性玩家 Tick'],
  ['api/README.en.md', 'api/index.md', 'API Overview'],
  ['api/README.zh.md', 'zh/api/index.md', 'API 总览'],
  ['api/parallel-api.en.md', 'api/parallel-api.md', 'ParallelApi'],
  ['api/parallel-api.zh.md', 'zh/api/parallel-api.md', 'ParallelApi'],
  ['api/block-entity-execution.en.md', 'api/block-entity-execution.md', 'Block Entity Execution'],
  ['api/block-entity-execution.zh.md', 'zh/api/block-entity-execution.md', '方块实体执行协议'],
  ['api/block-entity-ticks.en.md', 'api/block-entity-ticks.md', 'Block Entity Ticks'],
  ['api/block-entity-ticks.zh.md', 'zh/api/block-entity-ticks.md', '方块实体 Tick 适配'],
  ['api/block-entity-operations.en.md', 'api/block-entity-operations.md', 'Block Entity Operations'],
  ['api/block-entity-operations.zh.md', 'zh/api/block-entity-operations.md', '方块实体跨区操作'],
  ['api/natural-spawn-preparation.en.md', 'api/natural-spawn-preparation.md', 'Natural Spawn Preparation'],
  ['api/natural-spawn-preparation.zh.md', 'zh/api/natural-spawn-preparation.md', '刷怪规则准备协议'],
  ['api/schedulers-and-entity-interactions.en.md', 'api/schedulers-and-entity-interactions.md', 'Schedulers & Entity Interactions'],
  ['api/schedulers-and-entity-interactions.zh.md', 'zh/api/schedulers-and-entity-interactions.md', '调度器与实体交互'],
  ['adapters/README.en.md', 'adapters/index.md', 'Adapters Overview'],
  ['adapters/README.zh.md', 'zh/adapters/index.md', '适配器总览'],
  ['adapters/create-adapter.en.md', 'adapters/create-adapter.md', 'Create Adapter'],
  ['adapters/create-adapter.zh.md', 'zh/adapters/create-adapter.md', 'Create 机械动力适配'],
  ['adapters/mobspawncontroller-adapter.en.md', 'adapters/mobspawncontroller-adapter.md', 'MobSpawnController Adapter'],
  ['adapters/mobspawncontroller-adapter.zh.md', 'zh/adapters/mobspawncontroller-adapter.md', 'MobSpawnController 适配'],
];

function extractTitle(content, fallback) {
  const m = content.match(/^#\s+(.+)$/m);
  return m ? m[1].trim().replace(/^["']|["']$/g, '') : fallback;
}

function stripFirstH1(content) {
  return content.replace(/^#\s+.+\r?\n+/, '');
}

function rewriteLinks(content, isZh) {
  const prefix = isZh ? `${BASE}/zh` : BASE;
  return content
    // Rewrite asset paths to public /Forgia/assets/...
    .replace(/\]\((?:\.\.\/)*assets\/([^)]+)\)/g, `](${BASE}/assets/$1)`)
    .replace(/src="(?:\.\.\/)*assets\/([^"]+)"/g, `src="${BASE}/assets/$1"`)
    // Rewrite visualizer link
    .replace(/\]\((?:\.\.\/)*visualizer\/index\.html\)/g, `](${BASE}/visualizer/index.html)`)
    // Rewrite sibling .en.md / .zh.md links to Starlight routes
    .replace(/\]\((?:\.\/)?README\.(?:en|zh)\.md(#[^)]+)?\)/g, (_, hash = '') => `](${prefix}/overview/${hash})`)
    .replace(/\]\((?:\.\.\/)?README\.(?:en|zh)\.md(#[^)]+)?\)/g, (_, hash = '') => `](${prefix}/overview/${hash})`)
    .replace(/\]\((?:\.\.\/)?(modules|api|adapters)\/README\.(?:en|zh)\.md(#[^)]+)?\)/g, (_, dir, hash = '') => `](${prefix}/${dir}/${hash})`)
    .replace(/\]\((?:\.\.\/)?(modules|api|adapters)\/([a-z0-9-]+)\.(?:en|zh)\.md(#[^)]+)?\)/g, (_, dir, name, hash = '') => `](${prefix}/${dir}/${name}/${hash})`)
    .replace(/\]\((?:\.\/)?([a-z0-9-]+)\.(?:en|zh)\.md(#[^)]+)?\)/g, (_, name, hash = '') => `](${prefix}/${name}/${hash})`);
}

let synced = 0;
for (const [srcRel, dstRel, fallbackTitle] of mappings) {
  const srcPath = path.join(srcDocsDir, srcRel);
  if (!fs.existsSync(srcPath)) {
    console.warn(`[sync-docs] skip missing: ${srcRel}`);
    continue;
  }
  const raw = fs.readFileSync(srcPath, 'utf8');
  const title = extractTitle(raw, fallbackTitle);
  const isZh = dstRel.startsWith('zh/');
  let body = stripFirstH1(raw);
  body = rewriteLinks(body, isZh);

  const safeTitle = JSON.stringify(title);
  const frontmatter = `---\ntitle: ${safeTitle}\n---\n\n`;
  const dstPath = path.join(dstDocsDir, dstRel);
  fs.mkdirSync(path.dirname(dstPath), { recursive: true });
  fs.writeFileSync(dstPath, frontmatter + body, 'utf8');
  synced++;
}

// Sync static assets and visualizer
const assetsDst = path.join(websiteRoot, 'public', 'assets');
fs.mkdirSync(assetsDst, { recursive: true });
for (const file of fs.readdirSync(path.join(srcDocsDir, 'assets'))) {
  fs.copyFileSync(path.join(srcDocsDir, 'assets', file), path.join(assetsDst, file));
}
const visDst = path.join(websiteRoot, 'public', 'visualizer');
fs.mkdirSync(visDst, { recursive: true });
fs.copyFileSync(path.join(srcDocsDir, 'visualizer', 'index.html'), path.join(visDst, 'index.html'));

console.log(`[sync-docs] synced ${synced} markdown docs + assets + visualizer.`);

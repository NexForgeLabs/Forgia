import { defineConfig } from 'astro/config';
import starlight from '@astrojs/starlight';

export default defineConfig({
  site: 'https://nexforgelabs.github.io',
  base: '/Forgia',
  integrations: [
    starlight({
      title: 'Forgia',
      description: 'Folia-grade asynchronous regionized execution for the NeoForge 1.21.1 ecosystem.',
      logo: {
        src: './public/assets/forgia-mark.svg',
        alt: 'Forgia',
      },
      favicon: '/assets/forgia-mark.svg',
      defaultLocale: 'root',
      locales: {
        root: {
          label: 'English',
          lang: 'en',
        },
        zh: {
          label: '简体中文',
          lang: 'zh-CN',
        },
      },
      social: [
        { icon: 'github', label: 'GitHub', href: 'https://github.com/NexForgeLabs/Forgia' },
      ],
      editLink: {
        baseUrl: 'https://github.com/NexForgeLabs/Forgia/edit/main/docs/',
      },
      customCss: ['./src/styles/custom.css'],
      head: [
        {
          tag: 'meta',
          attrs: {
            name: 'theme-color',
            content: '#0b0f19',
          },
        },
      ],
      sidebar: [
        {
          label: 'Overview',
          translations: { 'zh-CN': '概览' },
          items: [
            { label: 'Introduction', translations: { 'zh-CN': '项目介绍' }, slug: 'overview' },
            { label: 'Architecture: Stock vs. Forgia', translations: { 'zh-CN': '架构对比：原版 vs Forgia' }, slug: 'architecture' },
            { label: 'Live Benchmarks', translations: { 'zh-CN': '线上实测性能' }, slug: 'benchmarks' },
            { label: 'Configuration & Tuning', translations: { 'zh-CN': '配置与硬件调优' }, slug: 'configuration' },
            { label: 'Engineering Status', translations: { 'zh-CN': '工程进度与状态' }, slug: 'status' },
          ],
        },
        {
          label: 'Engine Internals',
          translations: { 'zh-CN': '引擎内核' },
          items: [
            { label: 'Modules Overview', translations: { 'zh-CN': '模块总览' }, slug: 'modules' },
            { label: 'Region Engine', translations: { 'zh-CN': '区域调度引擎' }, slug: 'modules/region-engine' },
            { label: 'Entity Ticks', translations: { 'zh-CN': '实体 Tick 切片' }, slug: 'modules/entity-ticks' },
            { label: 'Block Entities', translations: { 'zh-CN': '方块实体沙箱与波次' }, slug: 'modules/block-entities' },
            { label: 'Chunk Ticks', translations: { 'zh-CN': '区块随机刻' }, slug: 'modules/chunk-ticks' },
            { label: 'Natural Spawning & POI', translations: { 'zh-CN': '自然刷怪与 POI' }, slug: 'modules/natural-spawning-and-poi' },
            { label: 'Cross-Region & Scheduled Ticks', translations: { 'zh-CN': '跨区传播与计划刻' }, slug: 'modules/cross-region-and-scheduled' },
            { label: 'Observability & Probes', translations: { 'zh-CN': '可观测性与探针' }, slug: 'modules/observability' },
            { label: 'Experimental Player Ticks', translations: { 'zh-CN': '实验性玩家 Tick' }, slug: 'modules/player-ticks' },
          ],
        },
        {
          label: 'Adapter & Developer API',
          translations: { 'zh-CN': '开发者与适配 API' },
          items: [
            { label: 'API Overview', translations: { 'zh-CN': 'API 总览' }, slug: 'api' },
            { label: 'ParallelApi', translations: { 'zh-CN': 'ParallelApi' }, slug: 'api/parallel-api' },
            { label: 'Block Entity Execution', translations: { 'zh-CN': '方块实体执行协议' }, slug: 'api/block-entity-execution' },
            { label: 'Block Entity Ticks', translations: { 'zh-CN': '方块实体 Tick 适配' }, slug: 'api/block-entity-ticks' },
            { label: 'Block Entity Operations', translations: { 'zh-CN': '方块实体跨区操作' }, slug: 'api/block-entity-operations' },
            { label: 'Natural Spawn Preparation', translations: { 'zh-CN': '刷怪规则准备协议' }, slug: 'api/natural-spawn-preparation' },
            { label: 'Schedulers & Interactions', translations: { 'zh-CN': '调度器与实体交互' }, slug: 'api/schedulers-and-entity-interactions' },
          ],
        },
        {
          label: 'Compat Adapters',
          translations: { 'zh-CN': '模组兼容适配器' },
          items: [
            { label: 'Adapters Overview', translations: { 'zh-CN': '适配器总览' }, slug: 'adapters' },
            { label: 'Create Adapter', translations: { 'zh-CN': 'Create 机械动力适配' }, slug: 'adapters/create-adapter' },
            { label: 'MobSpawnController Adapter', translations: { 'zh-CN': 'MobSpawnController 适配' }, slug: 'adapters/mobspawncontroller-adapter' },
          ],
        },
      ],
    }),
  ],
});

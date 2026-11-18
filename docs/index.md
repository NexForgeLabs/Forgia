# Forgia

**A regionized, multithreaded server core for NeoForge 1.21.1.**

Forgia explores how heavily modded Minecraft servers can safely use region-based parallel ticking while retaining the NeoForge modding environment.

<div class="grid cards" markdown>

-   :material-sitemap: **Regionized architecture**

    Region ownership and scheduling are the foundation for expanding safe parallel execution.

-   :material-puzzle: **NeoForge compatibility**

    Designed around the modded-server ecosystem rather than a plugin-only server model.

-   :material-chart-line: **Measured, evidence-led development**

    Performance claims are tied to documented workloads and repeatable tests.

</div>

## Project status

Forgia is an active engineering project, not a drop-in, production-ready Folia replacement. Its current implementation and limitations are documented in the repository. In particular, player ticking, some mod callbacks, and cross-region interactions may still be serialized or constrained; adaptive region merging and broader safe parallelism remain ongoing work.

Please read the current status and compatibility notes before using Forgia on a live server.

## Documentation

- **[English documentation](README.en.md)** — project overview and technical notes.
- **[简体中文文档](README.zh.md)** — 项目介绍与技术说明.
- **[Source code and issue tracker](https://github.com/NexForgeLabs/Forgia)** — follow development, report issues, and contribute.

## Design principles

1. **Correctness before concurrency.** Cross-region access must have explicit ownership and coordination.
2. **Compatibility matters.** Modded workloads are a first-class design constraint.
3. **Measure real workloads.** Benchmarks should state their hardware, configuration, workload, and limitations.
4. **Make limitations visible.** Experimental features and unsupported behavior should be clearly identified.

## Disclaimer

Forgia is experimental software. Back up worlds and configurations before testing. Do not assume that every NeoForge mod is safe under parallel execution; compatibility depends on the mod and the code path being exercised.

---

*Forgia is developed by [NexForgeLabs](https://github.com/NexForgeLabs).*

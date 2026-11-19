# 玩家区域 Tick（Player Region Tick）与载荷 API 验证工具

[English](README.md) | 中文

本目录包含玩家区域 Tick（P2–P5）、第三方模组影响面准入烟测（`run_clean_mod_smoke.py` 与 `clean-mod-probe/`）及声明式区域 Worker 载荷 API（`IRegionPayloadHandler`）的自动化验收矩阵、JDI/数据包帧独立校验器与审查清单脚本（`run_p2_suite_matrix.py`、`run_owner_routes.py`、`run_owner_packet_matrix.py`、`run_region_payload.py`、`run_region_payload_matrix.py`、`audit_p4_acceptance.py`、`audit_region_payload_acceptance.py` 等）。

中文设计、阶段实施与验收文档位于 `region-threading/`：
- [玩家区域 Tick 设计](../../region-threading/PLAYER-REGION-TICK-DESIGN.zh.md)
- [P3 验收说明](../../region-threading/PLAYER-REGION-TICK-P3.zh.md) · [P4 验收说明](../../region-threading/PLAYER-REGION-TICK-P4.zh.md) · [P5 验收与性能说明](../../region-threading/PLAYER-REGION-TICK-P5.zh.md)
- [玩家区域载荷 API](../../region-threading/PLAYER-REGION-PAYLOAD-API.zh.md)
- [生产准入合同](../../region-threading/PLAYER-PRODUCTION-ADMISSION-CONTRACT.zh.md) · [共享状态合同](../../region-threading/PLAYER-SHARED-STATE-CONTRACT.zh.md) · [空间足迹合同](../../region-threading/PLAYER-SPATIAL-FOOTPRINT-CONTRACT.zh.md)

各阶段验收命令、历史候选记录与完整校验参数请参考 [English README](README.md)。

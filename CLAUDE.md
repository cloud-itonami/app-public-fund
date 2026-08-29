# etzhayyim-project-public-fund — Public Fund App Rules

この repo は `etzhayyim/root` monorepo から抽出されたもの（`migration.edn`、`:status :extracted`）。
下記の `60-apps/…` / `70-tools/…` は抽出前の monorepo を指しており、**この repo には存在しない**。
消さずに残しているのは、後継が特定できていない（UNRESOLVED であって不在ではない）ため。
`nbb tools/verify.cljs` が、この 3 件を「未解決として明示的に許可した参照」として毎回列挙する。

- 共通ルール: `60-apps/CLAUDE.md` / `70-tools/CLAUDE.md` — 抽出前 monorepo の共有ルール。この repo に等価物は無い

## Overview

`pb.etzhayyim.com` — クラウドファンディング方式の公共資金ファンド。COFOG/ISIC/APQC ベースの起案・審査・分配。

## Domain Model

権威ソース: `docs/260303-public-fund-app-design.md`

- FundCampaign, Pledge, FundProgram, EligibilityPolicy, Application, Decision, Disbursement

## Education & Family Fund Agents

権威ソース: `docs/260315-education-family-fund-agents-design.md`

| Agent | ISCO | Name | COFOG | Role |
|---|---|---|---|---|
| Education Fund Manager | 1345 | 学 (Manabu) | 09 | 教育ファンド審査・予算配分 |
| Early Childhood Specialist | 2342 | 芽 (Mei) | 09.1 | 幼児教育プログラム評価 |
| Family Welfare Manager | 1344 | 結 (Yui) | 10.4 | 家庭支援ファンド管理 |
| Social Worker | 2635 | 心 (Kokoro) | 10.4, 10.7 | 個別ケース支援・セーフガード |

## Components

| Component | 役割 |
|---|---|
| `public-fund-orchestrator-component` | 旧 orchestrator (Adapter 方式) |
| `etzhayyim-wasm-pb-p8bl1cfn` | 標準 App (Command/Query/UI) |

## Nested Reference

- Daily Evolution: daily evolution multi-agent design
- Cross-project Matrix: `docs/260315-cross-project-matrix-conversation-design.md`
- Capability agents: `60-apps/etzhayyim-project-well-becoming/90-docs/260315-child-capability-agents-design.md`
  — **UNRESOLVED**（2026-08-29 実測）。fleet 内の後継を探して見つからなかった:
  `cloud-itonami/app-society6` / `kotoba-lang/open-wellbecoming` / `etzhayyim/actor-joucho`
  のいずれも `child-capability` / `260315` に一致するファイルを 0 件しか持たない。
  探し方が足りなかった可能性は残る —— 「無い」ではなく「引けなかった」と読むこと

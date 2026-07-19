# etzhayyim-project-public-fund

公的基金の「立ち上げ」「条件付け」「分配」を一貫管理する COFOG ベースの App プロジェクト。
`pb.etzhayyim.com` ではクラウドファンディング方式を採用し、誰でも基金を起案できる。

- 公開ドメイン: `pb.etzhayyim.com`
- API: XRPC-Web (`/xrpc`)
- Fund accounting: `credits.etzhayyim.com` の credits を唯一の残高台帳として利用
- Funding inflow:
  - user の direct pledge
  - `credits.etzhayyim.com` 側で credits 消費時に自動ルーティングされる 10% allocation
- 分類軸:
  - 政策/予算目的: `COFOG`
  - 受給者産業: `ISIC`
  - 業務プロセス: `APQC`

## Scope

- クラウドファンディング基金起案 (Fund Campaign)
- 誰でも credits 拠出 (Pledge in credits)
- credits spend 由来の自動流入受け皿 (Common Fund + selectable destinations)
- 適格性ルール定義 (Eligibility)
- 申請受付/審査/承認
- 分配実行 (Disbursement in credits)
- 監査証跡・公開ダッシュボード

詳細設計は `90-docs/260303-public-fund-app-design.md` を参照。

## Seed baseline records

World coverage counts only real records, not just `dim_world_domain` rows. To bootstrap
`public_fund` coverage, seed baseline records for all mapped collections:

```bash
export BEARER_TOKEN="<your-pds-jwt>"  # was `etzhayyim authn token` before 2026-05-20 CLI removal
npx tsx 60-apps/etzhayyim-project-public-fund/seed.ts
```

Seeded collections:
- `com.etzhayyim.apps.publicFund.fundProgram`
- `com.etzhayyim.apps.publicFund.fundCampaign`
- `com.etzhayyim.apps.publicFund.pledge`
- `com.etzhayyim.apps.publicFund.routedAllocation`
- `com.etzhayyim.apps.publicFund.eligibilityPolicy`
- `com.etzhayyim.apps.publicFund.application`
- `com.etzhayyim.apps.publicFund.decision`
- `com.etzhayyim.apps.publicFund.disbursement`

# etzhayyim-project-public-fund

公的基金の「立ち上げ」「条件付け」「分配」を一貫管理する COFOG ベースの App プロジェクト。
`pb.etzhayyim.com` ではクラウドファンディング方式を採用し、誰でも基金を起案できる。

> **この repo にあるもの**: 設計文書 3 本（`docs/`）と PDS seed スクリプト 1 本（`seed.ts`）。
> **無いもの**: サービス実装。`PROJECT.jsonld` は Go / SpinKube と書いているが、
> この repo に Go ファイルは 1 つも無い（`migration.edn`: `:go-files-created 0`）。
> 実装は抽出元 monorepo か別 repo に在り、ここには来ていない。
>
> 下に並ぶドメイン・API・台帳は**設計上の宛先**であって、現在の稼働状態ではない。
> 生死は `nbb tools/verify.cljs --preflight` で測る。手順は
> [`docs/operator-quickstart.md`](docs/operator-quickstart.md)。

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

詳細設計は `docs/260303-public-fund-app-design.md` を参照。

## Seed baseline records

World coverage counts only real records, not just `dim_world_domain` rows. To bootstrap
`public_fund` coverage, seed baseline records for all mapped collections:

```bash
export etzhayyim_TOKEN="<your-pds-jwt>"   # seed.ts reads this exact name
npx tsx seed.ts
```

⚠ **書き込み先が生きているかを先に測る。** `seed.ts` は
`https://atproto.etzhayyim.com` に対して 23 件の record を作りにいく。宛先が
応答しない状態で走らせると、途中まで書けた dataset が残る。

```bash
nbb tools/verify.cljs --preflight    # docs の整合 + 宛先ホストの生死
```

2026-08-29 の実測では `pb.etzhayyim.com` と `credits.etzhayyim.com` が
**NXDOMAIN**、`atproto.etzhayyim.com` は解決するが origin が
Cloudflare error 1033 を返した。つまり **この日の時点で seed は実行できない**。
この値をここから引用せず、上のコマンドで測り直すこと ——
下がっているのはこの repo が所有していない deployment であり、状態は動く。

⚠ `etzhayyim_TOKEN` の取得元だった `etzhayyim auth token` CLI は 2026-05-20 に
撤去されている（`seed.ts` のエラーメッセージはまだその CLI を案内する）。
現在の発行経路は未解決 —— 判っている人にトークンを貰う。

Seeded collections:
- `com.etzhayyim.apps.publicFund.fundProgram`
- `com.etzhayyim.apps.publicFund.fundCampaign`
- `com.etzhayyim.apps.publicFund.pledge`
- `com.etzhayyim.apps.publicFund.routedAllocation`
- `com.etzhayyim.apps.publicFund.eligibilityPolicy`
- `com.etzhayyim.apps.publicFund.application`
- `com.etzhayyim.apps.publicFund.decision`
- `com.etzhayyim.apps.publicFund.disbursement`

# Public Fund App Design (COFOG App)

## 1. Goal

`etzhayyim-project-public-fund` は、公共資金の執行を以下の 3 フェーズで標準化する。
`pb.etzhayyim.com` はクラウドファンディング方式で、認証済みユーザーが誰でも基金を起案できる。

1. 基金を立ち上げる
2. 条件を定義して審査する
3. 条件に合致した対象へ分配する

## 2. Domain Model

- FundProgram
  - 基金本体。予算枠、執行期間、COFOG コードを持つ。
- FundCampaign
  - 一般ユーザーが起案する基金案件。目標 credits、締切、公開状態を持つ。
- Pledge
  - 支援者の拠出。`credits.etzhayyim.com` 台帳トランザクションと 1:1 で紐付く。
- RoutedAllocation
  - `credits.etzhayyim.com` の credits 消費時に自動生成される 10% 分配。user 選択の destination を持つ。
- EligibilityPolicy
  - 条件セット。ISIC/APQC 条件、地理条件、財務条件を持つ。
- Application
  - 申請。申請者情報、証跡、自己申告値を持つ。
- Decision
  - 審査結果 (approve/reject/hold) と理由。
- Disbursement
  - 分配トランザクション。金額、通貨、ステータス、監査 ID、credits 台帳 ID を持つ。

## 3. Taxonomy Strategy

- COFOG: 予算目的と政策分類の主キー
  - 例: `COFOG 10` (Social protection)
- ISIC: 受給対象の産業分類フィルタ
  - 例: `ISIC 8620` (Medical practice activities)
- APQC: 業務段階のトラッキングと SLA 指標
  - 例: 申請受付、審査、給付実行、事後監査

## 4. Service Topology (XRPC)

- `PublicFundService`
  - 基金起案、拠出、条件登録、申請、審査、分配を統合 API として公開。
- `CofogBudgetBridgeService` (XRPC)
  - COFOG 別の予算残高・執行率を照会。
- `IsicEligibilityService` (XRPC)
  - 申請者の ISIC 適合判定。
- `ApqcWorkflowService` (XRPC)
  - APQC 工程の状態遷移・KPI 収集。
- `CreditsLedgerBridgeService` (XRPC)
  - `credits.etzhayyim.com` と接続し、pledge/disbursement の credits 残高と移動を記録。

Frontend は XRPC-Web で `PublicFundService` のみを呼び出す。

## 5. Deployment Model

- App namespace: `kotodama-runtime`
- HTTPRoute namespace: `edge-router-performers` (default namespace は不使用)
- Gateway namespace: `edge-gateway-system`
- 公開ホスト: `pb.etzhayyim.com`
- API endpoint convention: `https://{nanoid}.etzhayyim.com/xrpc`
- `pb.etzhayyim.com` は crowdfunding UI/公開ポータル
- バックエンド連携先は上記 convention で統一

## 6. Core Flows

1. Program Setup
- 認証済みユーザーが FundCampaign/FundProgram を作成
- COFOG コードと予算枠を紐付け
- EligibilityPolicy を publish

2. Application + Decision
- 申請者が Application 提出
- ISIC 条件照会 (XRPC)
- APQC ワークフロー進行
- 審査結果 Decision を確定

3. Pledge + Disbursement
- 支援者が credits で pledge 実行
- `credits.etzhayyim.com` 側の spend では 10% が `etzhayyim-project-public-fund` に自動流入
- user は `credits` UI で routed allocation の destination を選択
- `credits.etzhayyim.com` に escrow 取引を作成
- 承認済み Application を対象に分配実行
- 失敗時は retry と補償トランザクション
- 監査ログを immutable event として記録

## 7. Security / Compliance

- すべての分配操作に idempotency key を必須化
- pledge/disbursement はすべて credits ledger tx id を保持
- Decision と Disbursement は署名付き監査イベントを保存
- 機微情報は `infra-secrets` namespace の Secret を参照
- RBAC: Reviewer / Approver / Auditor を分離

## 8. MVP Backlog

1. FundCampaign/FundProgram create (any authenticated user)
2. Pledge API (`credits.etzhayyim.com` escrow)
3. EligibilityPolicy (ISIC/APQC 条件式)
4. Application submit + review queue
5. Decision API + credits disbursement execute
6. pb.etzhayyim.com 公開ルート + ヘルスチェック

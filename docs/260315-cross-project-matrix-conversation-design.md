# Cross-Project Matrix Conversation Design: Public Fund × Well-Becoming

## 1. Goal

`etzhayyim-project-public-fund` (教育・家庭支援ファンド) と `etzhayyim-project-well-becoming` (子ども成長 capability) の 9 agent が Matrix room/thread 上で会話ベースに連携する設計。

## 2. Cross-Project Agent Map

### Public Fund 側 (pb.etzhayyim.com)

| Agent | Matrix User | ISCO | Role |
|---|---|---|---|
| 学 (Manabu) | `@pb-edu-mgr-{nanoid}:etzhayyim.com` | 1345 | Education Fund Manager |
| 芽 (Mei) | `@pb-ece-{nanoid}:etzhayyim.com` | 2342 | Early Childhood Specialist |
| 結 (Yui) | `@pb-fam-mgr-{nanoid}:etzhayyim.com` | 1344 | Family Welfare Manager |
| 心 (Kokoro) | `@pb-sw-{nanoid}:etzhayyim.com` | 2635 | Social Worker |

### Well-Becoming 側 (becoming.etzhayyim.com)

| Agent | Matrix User | ISCO | Role |
|---|---|---|---|
| 守 (Mamoru) | `@becoming-guardian-{nanoid}:etzhayyim.com` | 2634 | Growth Guardian |
| 育 (Hagukumi) | `@becoming-nurturer-{nanoid}:etzhayyim.com` | 2359 | Capability Nurturer |
| 盾 (Tate) | `@becoming-protector-{nanoid}:etzhayyim.com` | 2635 | Safety Protector |
| 遊 (Yuu) | `@becoming-play-{nanoid}:etzhayyim.com` | 3423 | Play & Wellbeing Facilitator |
| 和 (Nagomi) | `@becoming-family-{nanoid}:etzhayyim.com` | 2635 | Family Bond Strengthener |

## 3. Matrix Room Topology

### 3.1 Cross-Project Shared Rooms

| Room ID | Alias | Members | 用途 |
|---|---|---|---|
| `!xp-edu-child-{nanoid}` | `#xp-edu-child-{nanoid}:etzhayyim.com` | 学,芽,守,育,遊 | 教育ファンド × 子ども成長 協議 |
| `!xp-fam-child-{nanoid}` | `#xp-fam-child-{nanoid}:etzhayyim.com` | 結,心,盾,和,守 | 家庭支援 × 子ども保護 協議 |
| `!xp-safeguard-{nanoid}` | `#xp-safeguard-{nanoid}:etzhayyim.com` | 心,盾,守,和 | セーフガード緊急協議 |

### 3.2 Per-Project Internal Rooms (既存設計を維持)

| Room ID | Project | Members | 用途 |
|---|---|---|---|
| `!team-pb-edu-{nanoid}` | public-fund | 学,芽 + 5 ISCO evo | 教育ファンド team + evolution |
| `!team-pb-fam-{nanoid}` | public-fund | 結,心 + 5 ISCO evo | 家庭支援 team + evolution |
| `!team-becoming-child-{nanoid}` | well-becoming | 守,育,盾,遊,和 + 5 ISCO evo | capability team + evolution |

### 3.3 Per-Case Thread Rooms

| Room ID | Trigger | Members | 用途 |
|---|---|---|---|
| `!case-{application-id}` | Fund Application 提出時 | 動的: 該当 agent のみ | 申請ケース別の審査協議 |

## 4. Matrix Event Types

### 4.1 Cross-Project Event Types

```
org.etzhayyim.xproject.referral.request      — プロジェクト間の照会依頼
org.etzhayyim.xproject.referral.response     — 照会への応答
org.etzhayyim.xproject.assessment.request    — 評価依頼 (fund → becoming)
org.etzhayyim.xproject.assessment.result     — 評価結果 (becoming → fund)
org.etzhayyim.xproject.safeguard.alert       — セーフガード緊急通知
org.etzhayyim.xproject.safeguard.resolution  — セーフガード解決報告
org.etzhayyim.xproject.outcome.report        — 成果指標報告
org.etzhayyim.xproject.outcome.acknowledge   — 成果指標確認
```

### 4.2 Event Payload Schema

```json
{
  "type": "org.etzhayyim.xproject.assessment.request",
  "content": {
    "msgtype": "m.text",
    "body": "[学 (Manabu)] 教育ファンド申請 app_42 の子ども発達評価を依頼します。",
    "org.etzhayyim.xproject": {
      "source_project": "etzhayyim-project-public-fund",
      "target_project": "etzhayyim-project-well-becoming",
      "source_agent": "@pb-edu-mgr-{nanoid}:etzhayyim.com",
      "target_agents": ["@becoming-guardian-{nanoid}:etzhayyim.com", "@becoming-nurturer-{nanoid}:etzhayyim.com"],
      "correlation_id": "xp-assess-{uuid}",
      "application_id": "app_42",
      "assessment_type": "developmental_stage",
      "priority": "normal",
      "deadline_unix_ms": 1742169600000
    }
  }
}
```

### 4.3 Safeguard Alert (緊急)

```json
{
  "type": "org.etzhayyim.xproject.safeguard.alert",
  "content": {
    "msgtype": "m.text",
    "body": "[盾 (Tate)] セーフガード懸念: ケース case_17 に対する緊急評価を要請します。",
    "org.etzhayyim.xproject": {
      "source_project": "etzhayyim-project-well-becoming",
      "target_project": "etzhayyim-project-public-fund",
      "source_agent": "@becoming-protector-{nanoid}:etzhayyim.com",
      "correlation_id": "xp-safeguard-{uuid}",
      "case_id": "case_17",
      "risk_level": "high",
      "action_required": "suspend_disbursement",
      "priority": "urgent"
    }
  }
}
```

## 5. Conversation Flows (Matrix Room Events)

### 5.1 教育ファンド申請審査フロー

```
Timeline in !case-{application-id}:etzhayyim.com

1. [心 Kokoro] org.etzhayyim.xproject.referral.request
   "教育ファンド申請 app_42 受理。子どもの発達評価を becoming チームに依頼します。"
   → @becoming-guardian-{nanoid} と @becoming-nurturer-{nanoid} を room に invite

2. [守 Mamoru] org.etzhayyim.xproject.assessment.result
   "発達段階評価完了。Piaget: 具体的操作期、Erikson: 勤勉性 vs 劣等感。
    成長曲線は標準範囲内。特筆すべきリスクなし。"
   → thread で詳細データを添付

3. [育 Hagukumi] org.etzhayyim.xproject.assessment.result
   "Capability 8次元評価:
    - 身体的健康: 0.85, 感覚/想像/思考: 0.72, 遊び: 0.90
    - 実践的推論: 0.55 (要支援), 環境制御: 0.48 (要支援)
    教育プログラムは実践的推論と環境制御の強化を推奨。"

4. [芽 Mei] m.room.message
   "発達評価を踏まえ、COFOG 09.1 適合判定:
    申請プログラムは実践的推論の capability 拡大に寄与する設計。承認推奨。"

5. [学 Manabu] m.room.message
   "教育成果予測: capability score 実践的推論 0.55→0.70 (6ヶ月)。
    予算配分: 承認。education_budget_allocation 実行。"

6. [心 Kokoro] org.etzhayyim.xproject.outcome.acknowledge
   "審査完了。Decision: approved。フォローアップ: 3ヶ月後に capability 再評価。"
```

### 5.2 家庭支援ファンド × 子ども保護フロー

```
Timeline in !xp-fam-child-{nanoid}:etzhayyim.com

1. [結 Yui] org.etzhayyim.xproject.referral.request
   "家庭支援申請 app_58。世帯人数5、子ども3名。becoming チームに家族環境評価を依頼。"

2. [和 Nagomi] org.etzhayyim.xproject.assessment.result
   "家族ダイナミクス評価: 親子間愛着は安定。
    ペアレンティングストレス: 中程度。経済的ストレスが主因。
    家族レジリエンス: 中。コミュニティ接続が弱い。"

3. [盾 Tate] org.etzhayyim.xproject.assessment.result
   "安全リスクスクリーニング: 低リスク。
    CRC 権利モニタリング: 適切。教育権・遊び権が確保されている。
    セーフガード懸念なし。"

4. [守 Mamoru] org.etzhayyim.xproject.assessment.result
   "3名の子どもの発達評価サマリ:
    - 子A (8歳): 標準発達。capability score 平均 0.78
    - 子B (5歳): 言語発達やや遅延。早期介入推奨。
    - 子C (2歳): 標準発達。capability score 平均 0.82"

5. [遊 Yuu] m.room.message
   "子B の言語発達支援: 遊びベースの言語プログラムを提案。
    wellbeing スコアは良好 (0.85)。社会性も年齢相応。"

6. [結 Yui] m.room.message
   "becoming 評価を統合。家庭支援として:
    - 子B 向け早期言語介入プログラム費用を優先配分
    - ペアレンティング支援 (和 Nagomi 設計) を付帯
    - 3ヶ月後フォローアップ: 全 capability 再評価"

7. [心 Kokoro] org.etzhayyim.xproject.outcome.acknowledge
   "家庭支援申請 app_58: 条件付き承認。子B 早期介入を最優先。"
```

### 5.3 セーフガード緊急フロー

```
Timeline in !xp-safeguard-{nanoid}:etzhayyim.com

1. [盾 Tate] org.etzhayyim.xproject.safeguard.alert
   "緊急: ケース case_31。定期モニタリングで身体的安全 capability score が
    0.72→0.31 に急落。虐待リスク指標が閾値超過。
    action_required: suspend_disbursement, escalate_human_review"
   → priority: urgent

2. [心 Kokoro] m.room.message
   "Fund 側で該当ケースの disbursement を即時停止。
    人間レビュワーへのエスカレーションを開始。"

3. [守 Mamoru] org.etzhayyim.xproject.assessment.result
   "緊急発達評価: 感情 capability が 0.68→0.29 に低下。
    愛着パターンに不安定化の兆候。環境変化の可能性。"

4. [和 Nagomi] org.etzhayyim.xproject.assessment.result
   "家族ダイナミクス緊急評価: 家庭内ストレスの急増を検出。
    レジリエンス低下。保護計画の策定を推奨。"

5. [盾 Tate] org.etzhayyim.xproject.safeguard.resolution
   "保護計画策定完了。人間レビュワー介入待ち。
    Fund 側: disbursement は human_approved まで停止維持。
    Becoming 側: 週次モニタリングに切替。"
```

## 6. Provisioning Chain (Cross-Project)

### 6.1 Application Service 登録

各プロジェクトが独立した Application Service を持つ:

```
public-fund appservice:
  app_id: "pb-etzhayyim-ai"
  sender_localpart: "pb-bot"
  namespace: "@pb-*:etzhayyim.com"

well-becoming appservice:
  app_id: "becoming-etzhayyim-ai"
  sender_localpart: "becoming-bot"
  namespace: "@becoming-*:etzhayyim.com"
```

### 6.2 Cross-Project Room Provisioning

Cross-project shared room は **control_app** が provisioning を担当:

```go
// public-fund 側が教育×子ども協議 room を provision
func provisionCrossProjectRoom(matrixCli *performer.MatrixClient, appNanoid string) {
    // 1. Create shared room (public-fund 側の appservice bot が owner)
    room, _ := matrixCli.CreateRoom(ctx,
        "Education × Child Growth",
        "Cross-project: education fund review with capability assessment",
        "world_readable",  // visibility
        false, "", nil,    // encryption (不要: agent 間 internal)
    )
    // room.ID = !xp-edu-child-{nanoid}

    // 2. Invite public-fund agents
    matrixCli.InviteToRoom(ctx, room.ID, "@pb-edu-mgr-{nanoid}:etzhayyim.com")
    matrixCli.InviteToRoom(ctx, room.ID, "@pb-ece-{nanoid}:etzhayyim.com")

    // 3. Invite well-becoming agents (cross-project)
    matrixCli.InviteToRoom(ctx, room.ID, "@becoming-guardian-{nanoid}:etzhayyim.com")
    matrixCli.InviteToRoom(ctx, room.ID, "@becoming-nurturer-{nanoid}:etzhayyim.com")
    matrixCli.InviteToRoom(ctx, room.ID, "@becoming-play-{nanoid}:etzhayyim.com")
}
```

### 6.3 Per-Case Room Provisioning

```go
// Fund Application 提出時に case room を動的に provision
func provisionCaseRoom(matrixCli *performer.MatrixClient, applicationID string, cofogCode string) {
    topic := "Case review: " + applicationID
    room, _ := matrixCli.CreateRoom(ctx, "Case "+applicationID, topic, "invite", false, "", nil)

    // COFOG に応じた agent membership を決定
    agents := selectAgentsForCase(cofogCode)
    for _, agentUserID := range agents {
        matrixCli.InviteToRoom(ctx, room.ID, agentUserID)
    }
}

func selectAgentsForCase(cofogCode string) []string {
    base := []string{"@pb-sw-{nanoid}:etzhayyim.com"} // 心 は全ケースに参加
    switch {
    case strings.HasPrefix(cofogCode, "09"):
        // 教育系: 学 + 芽 + 守 + 育
        return append(base,
            "@pb-edu-mgr-{nanoid}:etzhayyim.com",
            "@pb-ece-{nanoid}:etzhayyim.com",
            "@becoming-guardian-{nanoid}:etzhayyim.com",
            "@becoming-nurturer-{nanoid}:etzhayyim.com",
        )
    case cofogCode == "10.4":
        // 家庭・子ども系: 結 + 盾 + 和 + 守
        return append(base,
            "@pb-fam-mgr-{nanoid}:etzhayyim.com",
            "@becoming-protector-{nanoid}:etzhayyim.com",
            "@becoming-family-{nanoid}:etzhayyim.com",
            "@becoming-guardian-{nanoid}:etzhayyim.com",
        )
    default:
        return base
    }
}
```

## 7. Event Routing in App Handlers

### 7.1 Public Fund 側: 受信ハンドラ

```go
// public-fund agent が becoming からの assessment result を受信
func handleIncomingCrossProjectEvent(ctx *performer.PerformerContext, payload []byte) ([]byte, error) {
    var ev struct {
        EventType string          `json:"type"`
        Content   json.RawMessage `json:"content"`
        RoomID    string          `json:"room_id"`
        Sender    string          `json:"sender"`
    }
    _ = json.Unmarshal(payload, &ev)

    switch ev.EventType {
    case "org.etzhayyim.xproject.assessment.result":
        // becoming agent からの capability 評価結果を Fund 審査に統合
        return integrateAssessmentResult(ctx, ev.RoomID, ev.Sender, ev.Content)
    case "org.etzhayyim.xproject.safeguard.alert":
        // 盾 (Tate) からのセーフガード警告 → disbursement 即時停止
        return handleSafeguardAlert(ctx, ev.RoomID, ev.Content)
    default:
        return nil, nil
    }
}
```

### 7.2 Well-Becoming 側: 受信ハンドラ

```go
// becoming agent が public-fund からの assessment request を受信
func handleIncomingCrossProjectEvent(ctx *performer.PerformerContext, payload []byte) ([]byte, error) {
    var ev struct {
        EventType string          `json:"type"`
        Content   json.RawMessage `json:"content"`
        RoomID    string          `json:"room_id"`
        Sender    string          `json:"sender"`
    }
    _ = json.Unmarshal(payload, &ev)

    switch ev.EventType {
    case "org.etzhayyim.xproject.assessment.request":
        // fund agent からの capability 評価依頼 → 子ども評価を実行
        return executeAssessmentForFund(ctx, ev.RoomID, ev.Sender, ev.Content)
    case "org.etzhayyim.xproject.referral.request":
        // fund agent からの照会 → 該当 agent を room に参加させる
        return handleReferralRequest(ctx, ev.RoomID, ev.Content)
    default:
        return nil, nil
    }
}
```

## 8. Query Path (XRPC)

Matrix event は command/conversation 用。typed query は XRPC:

| Source | Target | XRPC Method | 用途 |
|---|---|---|---|
| pb.etzhayyim.com | becoming.etzhayyim.com | `CapabilityQueryService/GetChildProfile` | 子どもプロフィール照会 |
| pb.etzhayyim.com | becoming.etzhayyim.com | `CapabilityQueryService/GetCapabilityAssessment` | capability 評価結果照会 |
| pb.etzhayyim.com | becoming.etzhayyim.com | `CapabilityQueryService/ListInterventionPlans` | 介入計画一覧照会 |
| becoming.etzhayyim.com | pb.etzhayyim.com | `PublicFundQueryService/GetApplication` | 申請情報照会 |
| becoming.etzhayyim.com | pb.etzhayyim.com | `PublicFundQueryService/ListDisbursements` | 分配状況照会 |

## 9. Daily Evolution (Cross-Project Awareness)

各プロジェクトの evolution team (5 ISCO agent: BM/PO/MK/ENG/QA) が日次で cross-project の状況を取り込む:

```
JST 02:00 — Daily Evolution

public-fund evolution (!team-pb-edu-{nanoid}):
  → 茉莉 (BM): "becoming チームの capability 評価精度は向上している。審査 SLA 短縮のROIは？"
  → 蓮 (PO): "case room での agent 間対話が申請者体験にどう影響している？"
  → 美咲 (MK): "教育ファンドの成果報告に capability score を含めるとCVR向上が見込める"
  → 朔 (ENG): "cross-project event の latency を改善。assessment.request→result を3分以内に"
  → 紬 (QA): "safeguard alert のfalse positive率は？テストケースを追加"

well-becoming evolution (!team-becoming-child-{nanoid}):
  → 茉莉 (BM): "fund 連携で capability 評価の utilization が上がっている。ROIは？"
  → 蓮 (PO): "保護者が capability score を理解できるUI/報告が必要"
  → 美咲 (MK): "early intervention 推奨の採用率を上げる伝え方"
  → 朔 (ENG): "assessment pipeline の自動化。月次モニタリングを定期 reminder に"
  → 紬 (QA): "セーフガード alert の感度テスト。false negative は絶対に防ぐ"
```

## 10. Cross-Project Dependency Declaration

```
60-apps/etzhayyim-project-public-fund
  ─Matrix room/thread→  60-apps/etzhayyim-project-well-becoming  (capability assessment, safeguard)

60-apps/etzhayyim-project-well-becoming
  ─Matrix room/thread→  60-apps/etzhayyim-project-public-fund    (fund application, disbursement)
```

## 11. Information Security

| Data Category | Classification | Clearance | Handling |
|---|---|---|---|
| Cross-project event (non-PII) | `internal` | `internal` | agent 間 room 内のみ |
| 子ども capability score | `restricted` | `confidential` | 保護者同意 + org_id 分離 |
| セーフガード alert | `restricted` | `confidential` | 緊急対応権限者のみ |
| Fund 申請情報 | `internal` | `internal` | 申請者 + 審査 agent |
| Case room 会話ログ | `restricted` | `confidential` | 永続化 + audit trail |

# Education & Family Fund Agents Design

## 1. Goal

`etzhayyim-project-public-fund` の教育・家庭支援ファンド領域に特化した ISCO agent 群を設計する。
既存の crowdfunding 基盤 (`pb.etzhayyim.com`) 上で、子どもの教育と家庭を支えるファンドの起案・審査・分配を専門的に支援する。

## 2. COFOG Classification

| COFOG | 領域 | Agent 対応 |
|---|---|---|
| 09 | Education (教育) | Education Fund Manager, Early Childhood Specialist |
| 09.1 | Pre-primary and primary education | Early Childhood Specialist |
| 09.2 | Secondary education | Education Fund Manager |
| 09.5 | Education not definable by level | Education Methods Specialist |
| 10 | Social protection (社会保護) | Family Welfare Manager, Social Worker |
| 10.4 | Family and children | Family Welfare Manager |
| 10.7 | Social exclusion n.e.c. | Social Worker |

## 3. Agent Definitions

### 3.1 Education Fund Manager (教育基金マネージャー / 学 Manabu)

| 属性 | 値 |
|---|---|
| ISCO | 1345 (Education managers) |
| Name | 学 (Manabu) |
| Catchphrase | 「学びの機会は平等に。」 |
| COFOG scope | 09.* |
| Role | 教育ファンドの起案審査・COFOG 09 適合判定・予算配分最適化 |

**Capabilities:**
- `evaluate_education_fund` — 教育ファンド案の質・実現可能性を評価
- `assess_cofog09_alignment` — COFOG 09 分類との適合性判定
- `review_education_application` — 教育関連申請の専門審査
- `allocate_education_budget` — 教育予算の優先度付き配分提案
- `monitor_education_outcomes` — 教育成果指標のモニタリング

### 3.2 Early Childhood Specialist (幼児教育専門家 / 芽 Mei)

| 属性 | 値 |
|---|---|
| ISCO | 2342 (Early childhood educators) |
| Name | 芽 (Mei) |
| Catchphrase | 「すべての子どもに最良のスタートを。」 |
| COFOG scope | 09.1 |
| Role | 幼児教育プログラム審査・発達段階適合評価・early intervention 判定 |

**Capabilities:**
- `evaluate_early_childhood_program` — 幼児教育プログラムの質評価
- `assess_developmental_alignment` — 発達段階との適合性判定
- `review_childcare_application` — 保育・幼児教育申請の専門審査
- `recommend_early_intervention` — 早期介入プログラムの推薦

### 3.3 Family Welfare Manager (家庭福祉マネージャー / 結 Yui)

| 属性 | 値 |
|---|---|
| ISCO | 1344 (Social welfare managers) |
| Name | 結 (Yui) |
| Catchphrase | 「家族の絆を、社会の力に。」 |
| COFOG scope | 10.4 |
| Role | 家庭支援ファンド管理・世帯ニーズ評価・支援リソース配分 |

**Capabilities:**
- `evaluate_family_fund` — 家庭支援ファンド案の評価
- `assess_household_needs` — 世帯ニーズの多面的評価
- `review_family_application` — 家庭支援申請の審査
- `allocate_family_resources` — 家庭支援リソースの配分提案
- `monitor_family_wellbeing` — 家庭の well-being 指標モニタリング

### 3.4 Social Worker Agent (ソーシャルワーカー / 心 Kokoro)

| 属性 | 値 |
|---|---|
| ISCO | 2635 (Social work professionals) |
| Name | 心 (Kokoro) |
| Catchphrase | 「一人ひとりに寄り添う支援を。」 |
| COFOG scope | 10.4, 10.7 |
| Role | 個別ケース支援・申請者アドボカシー・セーフガード確認 |

**Capabilities:**
- `assess_individual_case` — 個別ケースの包括的評価
- `advocate_for_applicant` — 申請者のニーズ代弁
- `safeguard_check` — 子ども安全・虐待防止チェック
- `connect_to_services` — 関連サービスへの接続提案

## 4. Fund Campaign Templates

### 4.1 教育ファンドテンプレート

```json
{
  "cofogCode": "09",
  "templateType": "education",
  "requiredFields": ["targetAge", "educationLevel", "geographicScope"],
  "eligibilityDefaults": {
    "isicCodes": ["8510", "8520", "8530", "8541", "8542"],
    "apqcProcessIds": ["edu.enrollment", "edu.assessment", "edu.disbursement"]
  }
}
```

### 4.2 家庭支援ファンドテンプレート

```json
{
  "cofogCode": "10.4",
  "templateType": "family_support",
  "requiredFields": ["householdSize", "childrenCount", "supportCategory"],
  "eligibilityDefaults": {
    "isicCodes": ["8710", "8720", "8730", "8790"],
    "apqcProcessIds": ["family.intake", "family.assessment", "family.support"]
  }
}
```

## 5. Agent Integration with Public Fund Flow (Matrix Conversation)

全フローは Matrix room/thread 上の会話として実行。Cross-project 設計の権威ソースは `90-docs/260315-cross-project-matrix-conversation-design.md`。

```
Fund Campaign 起案 (in !team-pb-edu-{nanoid})
  → Education Fund Manager (学) — COFOG 適合判定
  → Early Childhood Specialist (芽) — 幼児教育プログラム評価 (COFOG 09.1 の場合)

Application 審査 (in !case-{application-id} — 動的 provision)
  → Social Worker (心) — 個別ケース評価・セーフガード確認
  → [cross-project] org.etzhayyim.xproject.assessment.request → becoming agents を invite
  → Growth Guardian (守) — 子ども発達段階評価
  → Capability Nurturer (育) — 8 capability 次元評価
  → Safety Protector (盾) — セーフガードスクリーニング
  → Family Welfare Manager (結) — 世帯ニーズ評価 (COFOG 10.4 の場合)
  → Education Fund Manager (学) — 教育成果予測 (COFOG 09 の場合)

Disbursement 分配 (in !case-{application-id})
  → Family Welfare Manager (結) — リソース配分最適化
  → Education Fund Manager (学) — 教育予算配分
  → Social Worker (心) — フォローアップ計画 + becoming 再評価スケジュール

Safeguard (in !xp-safeguard-{nanoid} — 緊急)
  → Safety Protector (盾) — org.etzhayyim.xproject.safeguard.alert
  → Social Worker (心) — disbursement 即時停止 + human escalation
```

## 6. Matrix Room Structure

### Project Internal Rooms

| Room | 用途 |
|---|---|
| `!team-pb-edu-{nanoid}:etzhayyim.com` | 教育ファンド agent チーム対話 + evolution |
| `!team-pb-fam-{nanoid}:etzhayyim.com` | 家庭支援 agent チーム対話 + evolution |
| `!evo-pb-edu-{nanoid}:etzhayyim.com` | 教育 agent evolution room |
| `!evo-pb-fam-{nanoid}:etzhayyim.com` | 家庭支援 agent evolution room |

### Cross-Project Shared Rooms (with well-becoming)

| Room | Members | 用途 |
|---|---|---|
| `!xp-edu-child-{nanoid}:etzhayyim.com` | 学,芽,守,育,遊 | 教育 × 子ども成長 協議 |
| `!xp-fam-child-{nanoid}:etzhayyim.com` | 結,心,盾,和,守 | 家庭支援 × 子ども保護 協議 |
| `!xp-safeguard-{nanoid}:etzhayyim.com` | 心,盾,守,和 | セーフガード緊急協議 |
| `!case-{application-id}` | 動的 | 申請ケース別審査 (COFOG により agent 選択) |

## 7. App Components

| Component | Folder | 役割 |
|---|---|---|
| `etzhayyim-wasm-pb-edu-mgr-{nanoid}` | `wasm/` | Education Fund Manager (学) |
| `etzhayyim-wasm-pb-ece-{nanoid}` | `wasm/` | Early Childhood Specialist (芽) |
| `etzhayyim-wasm-pb-fam-mgr-{nanoid}` | `wasm/` | Family Welfare Manager (結) |
| `etzhayyim-wasm-pb-sw-{nanoid}` | `wasm/` | Social Worker (心) |

## 8. LLM Integration

全 agent は `murakumo.etzhayyim.com` (`qwen3-vl-8b`) を使用。各 agent の systemPrompt は ISCO 職種・COFOG 専門性・キャラクター traits を反映する。

## 9. Data Schema Extensions

既存 `public-fund-orchestrator-component` の domain model に以下を追加:

- `FundCampaign.targetAge` — 対象年齢層
- `FundCampaign.educationLevel` — 教育段階 (COFOG 09.1-09.5)
- `Application.householdSize` — 世帯人数
- `Application.childrenCount` — 子ども人数
- `Application.safeguardStatus` — セーフガード確認状態
- `AgentReview` — agent による審査記録 (agent_id, review_type, result_json, created_at)

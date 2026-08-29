# Operator quickstart

この repo で **今日実際にできること**の手順。書いてあるコマンドは
2026-08-29 に clean clone に対して全部実行し、出力を確認したもの。

**先に境界を言う。** ここには **サービス実装が無い**。あるのは設計文書 3 本と、
PDS へ baseline record を書く `seed.ts` 1 本。したがってこの quickstart は
「アプリを起動する」手順ではなく、**この repo が主張していることを検算し、
seed を撃てる状態かどうかを測る**手順である。

| やりたいこと | できるか |
|---|---|
| repo の主張（参照先・seed 契約・record グラフ）を検算する | **できる**（step 2、offline・決定論的） |
| seed が何を書くかを事前に見る | **できる**（step 3、offline） |
| seed の書き込み先が生きているか測る | **できる**（step 4、要ネットワーク） |
| seed を実行する | **2026-08-29 時点では不可**（step 5。宛先が応答しない） |
| public fund のサービスを起動する | **不可**。この repo にコードが無い |

---

## 0. 必要なもの

`git` と `nbb`（Node 上の ClojureScript。この workspace の script host）。

```bash
git --version
nbb --version
```

実測: `git 2.51.0` / `nbb v1.5.212`。`nbb` が無ければ `npm i -g nbb`。

## 1. 取得する

```bash
git clone git@github.com:cloud-itonami/app-public-fund.git
cd app-public-fund
```

## 2. repo の主張を検算する（offline）

```bash
nbb tools/verify.cljs
```

これが実行する検査は 3 つ:

| 検査 | 何を守るか |
|---|---|
| `doc-path-resolves` | 文書が名指しするファイルが実在すること |
| `seed-contract` | README の seed 手順が `seed.ts` の実際の要求と一致すること |
| `seed-graph` | seed の 23 record の id 参照が全部解決し、重複が無いこと |

**期待する出力の終わり**は `CLEAN — every check ran and found nothing.`、
**exit code は 0**。

```
[doc-paths]      scanned: {:files 6, :refs 12}   ok
[seed-contract]  scanned: {:env-required 1, :env-documented 1, :commands 1}   ok
[seed-graph]     scanned: {:arrays 8, :records 23}   ok
CLEAN
```

`scanned:` の行を飛ばさないこと。**0 を数えた検査は、問題が無かったのではなく
測れていない。** この script はそれを pass にせず `REFUSED` と言って **exit 2**
で終わる（0 でも 1 でもない ＝「答えられなかった」）。

exit code は 3 値:

| exit | 意味 |
|---|---|
| `0` | 全検査が走り、何も見つからなかった |
| `1` | 検査が走り、何か見つかった |
| `2` | **REFUSED** — 検査が走れなかった。判定は出していない |

`doc-paths` は毎回 `allowed-unresolved:` を 3 件列挙する。これは
抽出前 monorepo（`etzhayyim/root`）を指す参照で、後継が特定できていない。
**黙って無視しているのではなく、無視していることを毎回言っている** ——
列挙されない allowlist は finding の墓場になる。

## 3. seed が何を書くか見る（offline）

```bash
grep -oE "createRecord\('[^']+'" seed.ts | tr -d "'" | sed 's/createRecord(//' | sort
```

8 collection が出る（`fundProgram` / `fundCampaign` / `pledge` /
`routedAllocation` / `eligibilityPolicy` / `application` / `decision` /
`disbursement`）。件数の内訳は step 2 の `seed-graph` が
`{:arrays 8, :records 23}` として出す。

## 4. 書き込み先が生きているか測る（要ネットワーク）

```bash
nbb tools/verify.cljs --preflight
```

`--preflight` は **宣言された 4 host** を毎回引く（DNS → HTTP）。状態は 5 値で、
**測れなかった場合を「生きている」とも「死んでいる」とも言わない**（`UNMEASURED`）。

宣言リストを持つ理由は実測から来ている。当初この機能は文書から host を
scrape していたが、**URL 形式しか拾えなかったので 4 host 中 1 つしか測らず**、
死んでいる 3 つについて何も言わないきれいな出力を出した —— バッククォートで
`pb.etzhayyim.com` と書かれた host は URL ではないため。**沈黙が健康に見えた。**
いまは宣言リストを必ず引き、そのうえで**リストに無い host らしき token を
`UNDECLARED` として別に列挙する**（リストの取りこぼしを可視化するため。
実際これが `murakumo.etzhayyim.com` を見つけて 4 つ目になった）。

2026-08-29 の実測 —— **4 つのうち 3 つが消えている**:

```
ERROR    atproto.etzhayyim.com   HTTP 530      ← seed の書き込み先
NO-DNS   credits.etzhayyim.com   NXDOMAIN
NO-DNS   murakumo.etzhayyim.com  NXDOMAIN      ← 4 agent の推論先
NO-DNS   pb.etzhayyim.com        NXDOMAIN      ← 公開ドメイン
probed 4 declared host(s)
```

**この表を定数として引用しないこと。** 下がっているのはこの repo が所有して
いない deployment で、状態は動く。測り直すのは上のコマンド 1 本。

## 5. seed を撃つ（前提が揃ったときだけ）

前提は 2 つあり、2026-08-29 時点で**どちらも満たされていない**:

1. `atproto.etzhayyim.com` が応答すること（step 4 が `OK`）。
   いま `HTTP 530`（Cloudflare 1033 = origin 不在）。
2. `etzhayyim_TOKEN` が取得できること。発行元だった `etzhayyim auth token`
   CLI は 2026-05-20 に撤去済み。現在の発行経路は**未解決** ——
   `seed.ts` のエラーメッセージはまだ撤去済み CLI を案内するので、
   それに従っても取れない。

揃ったとき:

```bash
export etzhayyim_TOKEN="<your-pds-jwt>"
npx tsx seed.ts
```

`seed.ts` は失敗しても**途中まで書いた分を巻き戻さない**（`createRecord` が
非 2xx を投げてそこで止まる）。宛先を測らずに撃つと、半端な dataset が
生きたサーバに残る。step 4 を先に踏む理由はこれ。

## 6. 検査が本当に効いているか確かめる

**落ちない検査は劇場。** 信じる前に 1 度落とすこと。

```bash
# (a) seed が要求する env 変数名を README 側でずらす
sed -i '' 's/export etzhayyim_TOKEN=/export WRONG_TOKEN=/' README.md
nbb tools/verify.cljs > /tmp/out.log; echo "exit=$?"   # → exit=1
grep FINDING /tmp/out.log                              # → seed-env-var-documented
git checkout README.md

# (b) 定義側だけを改名して、参照を宙に浮かせる
#     perl の s/// は /g が無いので **最初の 1 件だけ** 置換する。
#     最初の出現は FUND_PROGRAMS の定義なので、参照 3 件が dangling になる。
perl -0pi -e "s/programId: 'pf-health-access'/programId: 'pf-renamed'/" seed.ts
nbb tools/verify.cljs > /tmp/out.log; echo "exit=$?"   # → exit=1
grep FINDING /tmp/out.log                              # → seed-dangling-reference x3
git checkout seed.ts

# (e) 同じ collection に id を重複させる
perl -0pi -e "s/pledgeId: 'plg-002'/pledgeId: 'plg-001'/" seed.ts
nbb tools/verify.cljs > /tmp/out.log; echo "exit=$?"   # → exit=1
grep FINDING /tmp/out.log                              # → seed-duplicate-id
git checkout seed.ts

# (c) 検査が走れない状態にする（pass ではなく REFUSED になること）
mv seed.ts seed.ts.bak
nbb tools/verify.cljs > /tmp/out.log; echo "exit=$?"   # → exit=2
grep REFUSED /tmp/out.log
mv seed.ts.bak seed.ts

# (d) preflight の UNDECLARED が本当に見つけられるか（0 件が真の 0 か）
printf '\nprobe: `nonexistent-host.example.com`\n' >> README.md
nbb tools/verify.cljs --preflight > /tmp/out.log 2>&1
grep -A2 UNDECLARED /tmp/out.log                       # → nonexistent-host.example.com
git checkout README.md
```

(d) が要るのは、`no undeclared host-like tokens found` が
**「探して無かった」なのか「探せていない」なのか出力から区別できない**ため。
一度見つけさせて初めて 0 件に意味が出る。

**壊したものと報告されたものが一致することを確かめる。** 別の理由で赤くなった
実行を「検査が効いた」と数えると、それは検査したことにならない。

⚠ **壊し方を間違えると、効いている検査が壊れているように見える。** (b) を最初
`sed -i '' 's/.../.../'`（＝全置換）で試したところ **exit=0 で緑**になった。
`pf-health-access` は 4 箇所に出るので、全置換すると**定義も参照も一緒に改名され、
グラフは整合したまま**になる。検査は正しく「dangling は無い」と答えていた。
`perl -0pi -e 's///'`（/g 無し＝最初の 1 件のみ）にして初めて、
意図した「参照だけが宙に浮く」状態が作れる。**赤が出ないときは、まず
自分の壊し方を疑うこと。**

⚠ `$?` は pipe の**最後の**コマンドの終了値なので、
`nbb tools/verify.cljs | grep FINDING; echo $?` は `grep` の値を読む。
上のように**先にファイルへ落として exit を採り、それから読む**。

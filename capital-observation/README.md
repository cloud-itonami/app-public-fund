# Capital observation contracts (proposed to Hyakka)

Bounded actor contracts in this directory observe public capital-market
surface with provenance, and propose results to `network-awai/app-hyakka`
as auditable questions — never as scores, rankings or advice.

## fund-close-observation.edn

- A machine-readable contract covering source receipts (sha256 of verbatim
  bytes), typed entity/event inputs with hard entity separation (a venture
  firm, a fund vehicle and a GP are distinct entities even under one brand),
  time-bounded measurement windows (`[from, until)`), a method/version pin
  (`fund-close-observation.v2`), missingness/coverage flags
  (`missing-is-unmeasured`), derived observations that structurally exclude
  rank/score/centrality/ownership/suitability fields, an append-only refresh
  history, a Hyakka proposal shape (questions only, never advice), and a
  deterministic query/readback shape that always carries coverage +
  missingness.

v2 additions (2026-09-01):

- **Event-level provenance**: `:provenance-chain` is required on every
  event, not only on derived observations. An event with no traceable
  receipt chain is unmeasured (`:provenance-chain-incomplete`).
- **Identifier invariants**: one `:identifier-value` never denotes two
  different entity types, and one entity id carries exactly one identifier
  value. A missing identifier is `:identifier-unstated`, never guessed from
  a brand string.
- **Receipt disagreement is recorded, never resolved**: two allow-class
  receipts stating different facts about the same event produce a
  `:receipt-disagreement` flagged observation with ALL conflicting receipt
  ids in its chain and value `:unmeasured` — the contract never picks a
  winner.
- **Fetch-status admission**: a receipt whose fetch was not fully `:ok`
  backs no observation and flags `:fetch-status-non-ok`.

What it is not:

- Not a score or ranking. Not investment advice, ownership, endorsement or
  suitability. Amounts are carried with their stated kind (target vs first
  close vs final close) and are never collapsed.

Verify deterministically (offline, no network):
`fund-close-observation.v1` — observes **public fund** closes and
disbursements. Covers source receipts (sha256 of verbatim bytes), typed
entity/event inputs with hard entity separation (a venture firm, a fund
vehicle and a GP are distinct entities even under one brand), time-bounded
measurement windows (`[from, until)`), missingness/coverage flags
(`missing-is-unmeasured`), derived observations that structurally exclude
rank/score/centrality/ownership/suitability fields, an append-only refresh
history, a Hyakka proposal shape (questions only, never advice), and a
deterministic query/readback shape that always carries coverage +
missingness.

Amounts are carried with their stated kind (target vs first close vs final
close) and are never collapsed.

```bash
nbb tools/capital_observation_fixtures.cljs
```

## LP-commitment observation (`lp-commitment-observation.v1`)

`lp-commitment-observation.edn` is a second bounded contract in this
directory, observing **stated LP commitments** to funds with provenance.

What it adds beyond fund-close:

- Observes commitments **as stated** by allowed sources only
  (institutional-LP first-party, fund first-party, official regulator /
  securities filings). A discovery-only receipt (e.g. a news report
  naming an LP) can never back a derived observation — that is
  `:inferred-lp`, i.e. unmeasured here (fixture `allowed-source-only`).
- Entity separation across the LP plane: limited-partner vehicle,
  LP management organization, fund vehicle and GP are distinct entities
  even under one brand; LP and fund are distinct entity ids inside every
  event.
- `lp-commitment-is-not-current-nav-or-ownership`: a stated commitment
  is a capital pledge, never NAV, valuation or ownership stake; kinds
  (`stated-commitment` / `-amendment` / `stated-redemption`) are carried,
  not collapsed, and `:nav` / `:ownership-stake` / `:personal-wealth`
  are structurally forbidden fields.

Verify deterministically (offline, no network):

```bash
nbb tools/lp_commitment_fixtures.cljs
```

Exit codes: `0` all fixtures ran clean · `1` a violation was found ·
`2` REFUSED (contract could not be read).

## Readback pagination (`observation-query-readback.edn`)

A compositional contract constraining HOW observation records are served
back over the query plane: deterministic `(observation-id asc)` ordering
with opaque cursors, one method/version per page (no implicit version
mixing), every response — including empty pages — carrying its coverage
record and missingness flags, unknown request keys rejected, and
unavailable sources answered `:unmeasured` instead of a cache rebuild.
No new derived fields are introduced; page bodies carry observation ids
only (legal names, identifiers, amounts, receipt URLs resolve through
the underlying contract's own readback).

```bash
nbb tools/observation_readback_fixtures.cljs
## Exit observation contract (`exit-observation.edn`)

`exit-observation.edn` is a bounded actor contract for observing **exit
events** (acquisition, IPO listing, liquidation) around fund and portfolio
entities with provenance, proposed to Hyakka as auditable questions.

- Announced exits and completed exits are carried as distinct event kinds
  and are never collapsed. An estimated valuation is carried with kind
  `:estimated-valuation` only — a verified/current valuation field cannot
  exist in the derived-observation shape by construction.
- Same guarantees as above: hash-backed receipts, entity separation,
  time-bounded windows, missingness flags, append-only refresh history
  (reclassification appends, never overwrites), no rank/score/returns/
  ownership/suitability fields, questions-only Hyakka proposal, and a
  deterministic readback that always carries coverage + missingness.
## Portfolio listing observation contract (`portfolio-listing-observation.edn`)

`portfolio-listing-observation.edn` is a bounded actor contract for
observing **portfolio-page listings** — "source S listed company C on
fund F's portfolio page at time T" — as first-party, hash-backed claims,
proposed to Hyakka as auditable questions.

- A portfolio page listing is the manager's own statement about its own
  page. It is **not** verified ownership, a current holding, an
  endorsement or a performance claim
  (`portfolio-listing-is-not-ownership-verification`,
  `past-listing-is-not-current-holding`). A name disappearing from the
  page is recorded as its own `:removed-from-portfolio-page` event — the
  earlier listing observation is never overwritten or deleted.
- Only first-party sources (fund / manager / registry) may back a derived
  observation; news reports are discovery-only. Same guarantees as above:
  hash-backed receipts, entity separation (fund vehicle, management
  company and portfolio company stay distinct even when they share a
  brand), time-bounded windows, missingness flags, append-only refresh
  history, no rank/score/returns/ownership/suitability fields,
  questions-only Hyakka proposal, and a deterministic readback that
  always carries coverage + missingness.
- **v1.1 — cross-source conflict**: when two allowed first-party sources
  disagree about the same (fund-vehicle, portfolio-company) listing in
  the same window, the disagreement itself is carried as a
  `:conflict-observation` with both receipts
  (`:competing-source-receipt-ids`). The resolution is always
  `:carry-both-never-resolve` — no winner is picked, the pair is flagged
  `:first-party-source-conflict` wherever it appears downstream, and a
  conflict never resolves into an ownership or current-holding claim.

Verify deterministically (offline, no network):

```bash
nbb tools/exit_observation_fixtures.cljs
nbb tools/portfolio_listing_fixtures.cljs
```
## Coverage rollup contract

`coverage-rollup-observation.edn`
(`coverage-rollup-observation.v1`) is a compositional contract that
aggregates the per-window coverage records the observation kinds already
emit, per coverage-unit × observation-kind. It is measurement of
measurement: how much of a window is measured and what is unmeasured.
No new observation semantics, no aggregate amounts, no completeness
score — unmeasured units are emitted as rows, never silence, and
`aggregate-amount` / `completeness-score` / `rank` are structurally
forbidden. Same window shape, append-only refresh history, and readback
discipline (one method/version per page, every response carries
coverage + missingness, `:unmeasured` is not zero).

```bash
nbb tools/coverage_rollup_fixtures.cljs
```


## Fund-manager affiliation observation contract (`fund-manager-affiliation-observation.edn`)

`fund-manager-affiliation-observation.edn` is a bounded actor contract
for observing **manager / general-partner naming** — "source S named
management company M as the manager of fund vehicle F at time T" — as
first-party or registry-backed, hash-backed claims, proposed to Hyakka
as auditable questions.

- A manager naming is a source's own statement. It is **not** verified
  ownership or control, a share percentage, an endorsement, a
  performance claim or investment suitability
  (`manager-naming-is-not-ownership-or-control`).
- The manager role and the general-partner role are carried as
  **separately-stated roles** and are never collapsed into one "owner"
  (`roles-carried-not-collapsed`).
- Only first-party sources (fund / manager / official registry) may
  back a derived observation; news reports are discovery-only. Same
  guarantees as the other contracts: sha256-backed verbatim receipts,
  hard entity separation (a brand string never merges a management
  company across jurisdictions or across the GP/manager boundary),
  half-open time-bounded windows, missingness flags
  (`missing-is-unmeasured`), append-only refresh history, no
  rank/score/returns/ownership/control/suitability fields by
  construction, questions-only Hyakka proposal, and a deterministic
  readback that always carries coverage + missingness.
- **Cross-source conflict**: when two allowed first-party sources
  disagree about the same (fund-vehicle, role) naming in the same
  window, the disagreement is carried as a `:conflict-observation`
  with both receipts. Resolution is always
  `:carry-both-never-resolve` — no winner is picked, and a conflict
  never resolves into an ownership or control claim.

Verify deterministically (offline, no network):

```bash
nbb tools/manager_affiliation_fixtures.cljs
```
## financing-round-observation.edn

`financing-round-observation.v1` — observes **announced startup financing
rounds** (pre-seed through growth). Same receipt/window/missingness/
readback skeleton as the fund-close contract, with round-specific
epistemic boundaries enforced by construction:

- `announced-round-is-not-cash-received` — amount kinds (`:stated-round-size`,
  `:stated-raise`, `:stated-post-money-valuation-claim`) are carried, not
  collapsed; a valuation *claim* is never a verified valuation
  (`:verified-valuation` is a forbidden field).
- `lead-investor-is-not-board-control` — participant roles (`:lead`,
  `:co-investor`, `:existing-investor`) are observed claims bound to a
  receipt, not control or ownership facts.
- `brand-is-not-legal-entity` — a company, a venture firm and a fund
  vehicle sharing a brand string stay distinct entity ids.
- `news-report-is-not-an-issuer-or-regulator-filing` — news reports and
  commercial aggregators are discovery-only pointers and can never back a
  derived observation.
- Source classes: company/fund/manager first-party and official registries
  are allowed; search snippets, scraped directories, social posts and
  bypass classes are forbidden.

```bash
nbb tools/financing_round_fixtures.cljs
```

What these are not:

- Not a score or ranking. Not investment advice, ownership, endorsement or
  suitability. No personal profiling or wealth inference.

Exit codes for both fixture runners: `0` all fixtures ran clean · `1` a
violation was found · `2` REFUSED (contract could not be read).

## Round participant observation contract (`round-participant-observation.edn`)

`round-participant-observation.edn` is a bounded actor contract for
observing **financing-round participant naming** — "source S named
entity P as a participant in financing round R at time T" — as
first-party or registry-backed, hash-backed claims, proposed to Hyakka
as auditable questions.

- A participant naming is a source's own statement. It is **not**
  verified ownership, a share percentage, an endorsement, a
  co-investment network edge, a performance/momentum claim or
  investment suitability
  (`participant-naming-is-not-ownership-or-endorsement`).
- Participant roles (lead-participant / participant / co-investor /
  unstated) are carried as **separately-stated roles** and are never
  collapsed (`roles-carried-not-collapsed`).
- Only first-party sources (company / participant / official registry)
  may back a derived observation; news reports are discovery-only. Same
  guarantees as the other contracts: sha256-backed verbatim receipts,
  hard entity separation (round, company, investor entity and GP stay
  distinct even when they share a brand), half-open time-bounded
  windows, missingness flags (`missing-is-unmeasured`), append-only
  refresh history, no rank/score/centrality/graph-edge/returns/
  ownership/suitability fields by construction, questions-only Hyakka
  proposal, and a deterministic readback that always carries coverage +
  missingness.
- **Cross-source conflict**: when two allowed first-party sources
  disagree about the same (financing-round, participant) naming in the
  same window, the disagreement is carried as a `:conflict-observation`
  with both receipts. Resolution is always `:carry-both-never-resolve`
  — no winner is picked, and a conflict never resolves into an
  ownership or endorsement claim.

Verify deterministically (offline, no network):

```bash
nbb tools/round_participant_fixtures.cljs
```

## Co-investment adjacency observation contract (`co-investment-observation.edn`)

`co-investment-observation.edn` (`co-investment-observation.v1`) is a
bounded actor contract for observing **co-investment listings** —
"source S listed participant entities X and Y together in the same
financing round R at time T" — as registry- or first-party-backed,
hash-backed claims, proposed to Hyakka as auditable questions.

- `edge` is **adjacency only**: it means exactly "the source listed A
  and B together in round R". It is symmetric and unordered, and the
  contract structurally excludes interpreting it as syndication,
  alignment, endorsement, influence, a relationship, or a follow-on.
  A named role (e.g. "lead") is only ever carried as the source's own
  word (`:listing-kind`), never verified.
- This is a **network observation, not a network score**: no
  centrality, degree counts, influence, network strength, ranking or
  syndication patterns — those fields are forbidden by construction,
  as are all rank/score/valuation/returns/ownership/suitability
  fields. A round with a single listed participant produces no edge
  and flags `:single-participant-only`.
- Two allowed sources listing different participant sets for the same
  round yield **two separate edge observations**, each bound to its own
  receipt — never a merged participant list.
- Same guarantees as the other contracts: sha256-backed verbatim
  receipts, `fetch-status :ok` admission, hard entity separation (a
  company, a fund vehicle and a limited partner sharing a brand stay
  distinct), participant ids must resolve to declared entities,
  half-open time-bounded windows (`missing-is-unmeasured`,
  `:out-of-window` outside the window), append-only refresh history,
  questions-only Hyakka proposal, and a deterministic readback that
  always carries coverage + missingness + provenance.

Verify deterministically (offline, no network):

```bash
nbb tools/co_investment_fixtures.cljs
```

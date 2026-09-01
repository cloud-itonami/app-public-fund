# Fund-close observation contract (proposed to Hyakka)

`fund-close-observation.edn` is a bounded actor contract for observing
**public fund** closes and disbursements with provenance, proposed to
`network-awai/app-hyakka` as auditable questions.

What it is:

- A machine-readable contract covering source receipts (sha256 of verbatim
  bytes), typed entity/event inputs with hard entity separation (a venture
  firm, a fund vehicle and a GP are distinct entities even under one brand),
  time-bounded measurement windows (`[from, until)`), a method/version pin
  (`fund-close-observation.v1`), missingness/coverage flags
  (`missing-is-unmeasured`), derived observations that structurally exclude
  rank/score/centrality/ownership/suitability fields, an append-only refresh
  history, a Hyakka proposal shape (questions only, never advice), and a
  deterministic query/readback shape that always carries coverage +
  missingness.

What it is not:

- Not a score or ranking. Not investment advice, ownership, endorsement or
  suitability. Amounts are carried with their stated kind (target vs first
  close vs final close) and are never collapsed.

Verify deterministically (offline, no network):

```bash
nbb tools/capital_observation_fixtures.cljs
```

Exit codes: `0` all fixtures ran clean · `1` a violation was found ·
`2` REFUSED (contract could not be read).

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
nbb tools/portfolio_listing_fixtures.cljs
```

# Fund-close observation contract (proposed to Hyakka)

`fund-close-observation.edn` is a bounded actor contract for observing
**public fund** closes and disbursements with provenance, proposed to
`network-awai/app-hyakka` as auditable questions.

What it is:

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

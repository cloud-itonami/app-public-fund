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
```

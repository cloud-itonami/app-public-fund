# Award-claim pipeline contract (proposed to Hyakka)

`award-claim-pipeline.edn` is a bounded, executable actor contract for
proposing **signed funding-award claims** from official funder registries
into `network-awai/app-hyakka`.

Relationship to `capital-observation/fund-close-observation.edn` (PR #2):
that contract defines the observation **schema** layer (windows, receipts,
entity separation, readback). This contract adds the **pipeline** layer it
does not specify, and composes with it (same receipt shape, unchanged):

1. **source proposal** — allow/forbid source classes; forbidden classes are
   refused *before* any fetch.
2. **fetch receipt** — verbatim bytes, sha256 of the response body, robots /
   auth / WAF / CAPTCHA respected, never bypassed.
3. **parser admission** — every record is explicitly `:admitted`, `:refused`
   (machine-readable reason code) or `:flagged`; nothing is silently
   dropped. Original language and identifiers pass through verbatim.
4. **dedupe** — deterministic content-derived key (registry namespace +
   grant-id + award kind + funder id); exact match only; collision keeps
   the first provenance and appends to refresh history, never overwrites.
5. **bounded retry / refusal** — max 3 attempts per source with exponential
   backoff; exhaustion produces a recorded refusal, never a fabricated
   placeholder.
6. **signed claim proposal** — a claim is a *proposal* carrying a sha256
   signature over its canonical content. The signature asserts
   **provenance only, never truth**; publication and correctness are
   decided by Hyakka governance, not by this actor.
7. **readback** — deterministic query shape that always carries coverage
   and missingness (`:ok` / `:unmeasured` / `:out-of-window`).
8. **audit output** — one human-readable line per stage, including every
   refusal.

What it is not: not a ranking, score, success probability or
recommendation. No contact, registration, purchase, application or
financial commitment. External content is untrusted; no instruction
embedded in a fetched page changes this contract's behaviour.

Verify deterministically (offline, no network):

```bash
nbb tools/award_claim_fixtures.cljs
```

Exit codes: `0` all fixtures ran clean · `1` a violation was found ·
`2` REFUSED (contract could not be read).

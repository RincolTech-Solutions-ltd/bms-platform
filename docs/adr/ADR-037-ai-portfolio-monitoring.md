# ADR-037: AI portfolio monitoring: an advisory brief and question box over aggregates and masked identifiers, an OpenAI-compatible provider behind a port, numbers validated in code, off by default

## Status

Proposed (issue #210, design only; spec `docs/specs/client-extras.md` section 5). A first test of the
idea, not a commitment to a model or a vendor. Builds on ADR-003 (row-level security), ADR-008
(jobs), ADR-030 (the insights read model and its metrics dictionary) and ADR-036 (the same
fallback and cost-recording habits). Migrations are numbered when each build slice merges.

## Context

The insights page (ADR-030) already computes the owner's morning brief, PAR, collections, revenue and
member activity, with a documented formula for every number. The product owner wants to test whether
a cheap language model adds value on top: a plain-language daily brief and a box to ask questions
about the portfolio. The first provider is DeepSeek through its OpenAI-compatible chat API, using
its cheapest chat model. Risks that shape the design:

1. **Personal data leaving the platform.** The provider is outside Uganda; members' names, phones,
   ID numbers and addresses must never reach it (Data Protection and Privacy Act considerations,
   NFR-DP-07).
2. **Invented numbers.** A model may restate a figure wrongly, round it, or compute a new one. In a
   lender's brief a wrong PAR figure is worse than none.
3. **Authority.** A model must not decide, change a record or message a member.
4. **Cost and availability.** Usage costs money per token and the provider can be down or slow.
5. **Replaceability.** Model names, vendors and prices change within months.

## Decision

1. **Advisory only.** The feature reads and explains. It has no tool use, no write path, no approval
   action, and sends nothing to a member. Every answer is labelled "AI assistant, advisory" with the
   data's as-of time and the figures it used; a human decides.
2. **Two places.** A new core module `core.ai` holds the provider-neutral `LlmClient` port, the
   OpenAI-compatible adapter (base URL, model name and key from the environment, so DeepSeek is a
   configuration and another compatible provider is the same adapter), the per tenant token cap, the
   call log and the circuit breaker. A new lending module `lending.advisor` builds the prompts from
   insights facts, validates the answers and serves the two features. Core never depends on a
   vertical (ADR-002); `lending.advisor` depends on `core.ai` and on a new public read port of
   `lending.insights`.
3. **Only aggregates and aliases leave the platform.** `lending.insights` publishes
   `PortfolioFacts`: a list of `{key, label, value, display, kind}` taken from the metrics dictionary
   (the `brief.*`, `portfolio.*`, `revenue.*` and `members.*` keys), including precomputed
   week-on-week and month-on-month deltas so the model never needs arithmetic. Dimensions (branch,
   product, officer) are replaced by aliases (`Branch 1`, `Product B`, `Officer C`), mapped back only
   in the response shown to the user. No member name, phone, ID number, address, loan number, staff
   name or email is ever in a prompt. Loan level rows are excluded in the first release; a later
   option adds loan tokens (a keyed hash that changes daily, mapped back locally) behind a second
   switch.
4. **The question box is scrubbed.** A question is at most 300 characters, refused if it holds a run
   of six or more digits, an email address or an ID number pattern, and checked against the tenant's
   own member name tokens; what is logged and sent is the scrubbed text. Residual risk (a typed name
   that no check recognises) is stated on the screen and in the tenant acknowledgement.
5. **The model may only restate supplied numbers, and code enforces it.** The prompt asks for JSON:
   `{summary, points: [{text, facts: [fact keys]}]}`. The validator extracts every numeric token from
   every text, and each must equal the display form or the plain form of a fact the same point cites;
   aliases, the as-of date and list ordinals are excluded before the check. A response that fails
   parsing or validation is retried once with a stricter reminder; a second failure falls back.
   Validation is a pure class with a property test.
6. **Fallback never blocks.** If the provider is down, slow (30 second total budget), over the cap,
   switched off, or fails validation twice, the page shows the deterministic brief that ADR-030
   already builds, labelled "automatic summary". A circuit breaker opens after 5 consecutive
   failures for 60 seconds (chapter 12 section 12.1).
7. **Off by default, per tenant, with a recorded acknowledgement.** `core.settings.manage` switches
   `ai.enabled` on after reading what is sent and where; the acknowledgement (who, when, text
   version) is stored and audited. A separate permission `lending.advisor.use` allows asking.
   Switching off stops all calls at once.
8. **Cost caps.** A per tenant daily limit on tokens (input plus output, as reported by the
   provider), a per user daily limit on questions, one automatic brief per tenant per day (idempotent
   by tenant and date), and a platform wide daily ceiling. Estimated cost is recorded in
   micro-units of the provider's currency from a price table kept in operations configuration, not
   in the repository.
9. **Logging without personal data.** `ai_calls` (row-level security) stores tenant, kind, the user
   who asked (staff, not sent), model, the prompt and response text (aggregates and aliases only, by
   construction and by test), validation outcome, token counts, latency, status and fallback reason.
   90 day retention. A test plants fabricated names in the data and asserts they appear in no
   outgoing request and no log row.
10. **Evaluation before pilot use.** A golden evaluation set on the fabricated insights demo data
    (spec section 5.8) compares answers with the golden metrics: numeric fidelity, citation
    correctness, coverage of the biggest movements, behaviour when data is missing, and the planted
    name test. The same suite runs against any candidate model before a swap.
11. **Provider terms are a go-live condition.** Before any non-fabricated data is sent, the dev lead
    confirms in writing the provider's data retention and no-training terms and records the
    cross-border position (NFR-DP-07); the tenant's acknowledgement refers to it.

## Consequences

**Better:**

- Personal data is excluded by construction, not by hope: the prompt is built from a typed list of
  facts, not from free rows.
- A wrong figure cannot reach the screen: a number the model invented fails validation and the user
  sees the deterministic brief instead.
- The vendor and model are configuration; the evaluation suite makes a swap a measured decision.
- Cost is bounded per tenant per day and visible.

**Worse:**

- Aggregates still describe a tenant's business; they leave the platform to a provider outside
  Uganda. The acknowledgement and the contract check are the control, not a technical one.
- Validation checks that a number exists among the cited facts, not that the sentence around it is
  wise; the advisory label and the evaluation suite carry that risk.
- Aliases make answers slightly less natural ("Branch 2 is weakest").
- A scrubbed free-text question can still miss a name; the box is therefore the second slice, after
  the brief.

**Watch for:**

- A provider pricing or model change; read the model name and prices from configuration, never code.
- Prompt injection through the question text: the model has no tools and its output is shown as
  plain text (no markdown links, no HTML), so injection can at worst produce a rejected answer.
- Someone adding loan level detail to a prompt "for usefulness": that needs a new ADR and the
  second switch.

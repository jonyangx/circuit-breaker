# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project status

This is a **design-phase** project. The repository currently contains only a design
document — there is no source code, build system, test suite, or git history. All
authoritative detail lives in **`docs/brd/design.md`** (written in Chinese). When
implementing or discussing behavior, treat that document as the source of truth.

The intended runtime is the **JVM (Java)** — the design references `AtomicLong`/
`AtomicInteger`/`LongAdder`, `@Contended` + `-XX:-RestrictContended`, and Java 21+
virtual threads. No build tooling (Maven/Gradle) exists yet; do not assume one.

## What this project is

A traffic-governance component (circuit breaking + rate limiting + concurrency
control) positioned as an ultra-low-overhead alternative to Alibaba Sentinel and
Hystrix. The hard performance targets — and these are **invariants any code must
preserve**, not aspirations — are:

- **Nanosecond-scale** per-call governance overhead (vs μs for Sentinel).
- **Zero heap allocation** on the request hot path (no `Entry`/`Context`/`Bucket` objects).
- **Lock-free** — all state updates via single `AtomicLong` CAS.
- **No background timer threads on the governance side** (lazy evaluation instead;
  only low-frequency observation/system probes run off the hot path).

## Core architecture (the big picture)

Split into a **control plane** (manage rules) and a **data plane** (execute traffic).
The data plane deliberately avoids OOP chaining (no Sentinel-style slot chain, no
polymorphism) in favor of primitive types, arrays, and bitmask dispatch.

### Config-State separation (the central v2 design decision)

The single most important architectural rule. For each resource there are **two
independent-lifetime containers**, both indexed by an integer `resourceId`:

- **`CONFIGS` = `volatile ResourceConfig[]`** — immutable, pure parameters
  (`mask`, `ratePerMs`, `capacity`, `errThreshold`, `minCalls`, `openMillis`,
  `ewmaTauMs`, `concurrencyLimit`, `version`). Replaced wholesale on hot-update (RCU).
- **`STATES` = `final ResourceState[]`** — long-lived mutable runtime state
  (`bucketState`, `breakerState`, `ewmaState` `AtomicLong`s, segmented
  `concurrency[]`, `passCount`/`blockCount` `LongAdder`s). **Never rebuilt on rule
  change.**

Hot-update swaps only `CONFIGS[resourceId]`; `STATES[resourceId]` stays put. This is
what keeps in-flight `release()` calls hitting the correct counters. Do **not** couple
mutable runtime state into `ResourceConfig` — that was v1's root defect.

### The 64-bit token

`FlatExecutionEngine.tryAcquire(resourceId)` returns a **`long` token** (not an
object) that carries everything needed by the later `release()`:

```
[sign:1][time:41][version:6][bucketIdx:4][mask:12]
 bit63                                              bit0
```

- **Sign bit always 0** on success, so `token < 0` cleanly means "blocked".
- **`bucketIdx` and `version` are embedded in the token**, so `release()` does **not**
  depend on the executing thread. This is what makes it reactive-safe (Reactor/Netty
  thread switches cannot cause counter drift). It is also the fix for v1's acquire/
  release-cross-thread bugs.
- Block codes are negative constants: `BLOCK_SYSTEM_OVERLOAD=-1`,
  `BLOCK_CIRCUIT_BREAKER=-2`, `BLOCK_RATE_LIMITER=-3`, `BLOCK_CONCURRENCY=-4`.

RT is computed with modular subtraction on the time field; because a single call's RT
is bounded by request timeouts, the time field can be narrowed and borrowing bits from
it to grow `mask` is safe (see design §3.2.2 for the bit-width table).

### Flat execution + three gated modules

`config.mask` enables governance capabilities via bitmask AND:

- `0x01` Circuit breaker (time-decay EWMA, not sliding window)
- `0x02` Rate limit (lazy token bucket)
- `0x04` Concurrency limit (segmented approximation)

Any module failure returns its negative block code; if all pass, the token is packed
and returned. A system-overload graded shed check (`SHED_PERMILLE`) short-circuits
first (design §10).

### Correctness mechanisms to preserve

- **Don't stripe the token bucket or the breaker state machine** — they hold global
  invariants (QPS ceiling, breaker state). Keep them as single `AtomicLong` + spin,
  padded with `@Contended`. **Do** stripe only exchangeable-summable quantities
  (observation counters, approximate concurrency), routing by `ThreadLocalRandom`
  probe (not `threadId` — virtual-thread ids defeat striping).
- **Generation tags** align the two independent `AtomicLong`s (`breakerState` and
  `ewmaState`). State transitions bump `generation`; an EWMA update whose generation
  doesn't match is re-seeded. This replaces explicit clear-CAS and prevents ABA and
  stale-error-rate re-tripping.
- **All timing uses `System.nanoTime()/1_000_000` relative to a startup `START`** —
  monotonic, immune to clock jumps, sized to fit the token's time field.
- **EWMA uses time-decay** `α = 1 - exp(-Δt/τ)` via a piecewise approximation (cheap
  `α≈u` on the hot path, LUT+interpolation otherwise) — never call `Math.exp` per
  request. Error rate stored as **ppm fixed-point integer** (not float) to avoid CAS
  jitter.

## Expected entry points / types (not yet implemented)

`ResourceManager` (`register(name, policy) -> resourceId`), `PolicyBuilder`,
`ResourceConfig`, `ResourceState`, `FlatExecutionEngine` (`tryAcquire`,
`release`), the lazy token-bucket and EWMA-circuit-breaker modules. Business call
pattern: `tryAcquire` → check `token < 0` → run work in `try/finally` → `release`.
Reactive variant attaches `release` via `doOnSuccess`/`doOnError` (design §5, §9).

## Working in this repo

- Before changing any behavior, **read `docs/brd/design.md` fully** — especially §3
  (architecture), §4 (module algorithms + exact bit layouts), §6 (pitfalls), and §8
  (hot-update). The bit-packing offsets, block codes, and state-machine edges are
  normative.
- The doc distinguishes **v1 flaws from v2 fixes** throughout. When something looks
  contradictory, the v2 text (usually introduced with 【v2】or 【细化】markers, or
  "v1 ... v2 ...") is current — v1 descriptions are included only to explain why a
  design choice exists.
- Keep documentation and code terminology consistent with the Chinese terms used in
  the design doc when referencing concepts (e.g. 惰性计算 / lazy evaluation,
  状态压缩 / state compression, 代际 / generation).

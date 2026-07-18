# StarsectorPrepatcher

[English](README.md) | [Русский](README_RU.md)

Current version: **0.9.1**. Supported game build: **Starsector 0.98a-RC8**.

[![Unplayable without Prepatcher versus smooth with Prepatcher](media/smoothness_comparison.gif)](https://github.com/kirpoly/StarsectorPrepatcher/releases/download/v0.8.0/StarsectorPrepatcher-0.8.0-comparison.webm)

Click the preview for the full 60 FPS WebM comparison.

StarsectorPrepatcher is a compatibility-first pre-load patching layer for Starsector. Its startup
javaagent runs before the game and mod classloaders begin normal loading, so guarded structural
patches can be applied at the point where the affected classes first enter the JVM.

The project has a broader direction than map optimization alone:

- maintain carefully verified performance and correctness patches for game internals;
- provide a stable, documented API for useful capabilities that Starsector's public API does not
  expose;
- keep version-specific bytecode knowledge inside the prepatcher instead of duplicating it across
  gameplay mods.

The public API is a roadmap item, not a published compatibility surface in `0.9.1`. Its intended
namespace is `com.starsector.prepatcher.api`; API types will only become supported once they are
documented and covered by compatibility tests.

## How it works

The distribution contains a sandbox-safe mod bootstrap and one startup agent:

```text
agent/StarsectorPrepatcherAgent.jar
```

The agent matches and verifies every patch independently, including the hyperspace patches. It does
not use whole-class hashes. It checks whichever game files are installed, original or localized,
and accepts them when the local bytecode contract still matches. Unknown, ambiguous, or partially
changed sites remain vanilla and the reason is written to the diagnostic log.

The agent control code and the typed runtime are deliberately separated. At startup the agent reads
the `com.fs.starfarer.api.StarsectorPrepatcher*` runtime classfiles from its own JAR and defines them
in the classloader that owns Starsector's API. This keeps hook arguments loader-identical under both
the vanilla launcher and Faster Rendering's custom system classloader. The transformer is registered
only after that runtime is installed successfully, and it skips a target whose loader differs from
the runtime loader.

The bootstrap plugin does not perform bytecode work. It exposes agent status through the normal game
log and warns when the mod is enabled without the startup agent.

## Installation

1. Fully close Starsector.
2. Extract the directory as `<Starsector>\mods\StarsectorPrepatcher`.
3. Install the agent for the launcher you use (commands below).
4. Enable **StarsectorPrepatcher** in the launcher and start the game.

For the vanilla launcher, run:

```bat
install-agent.bat
```

For Faster Rendering (`starsector-core\fr.bat`), run:

```bat
install-agent.bat -Target FasterRendering
```

To configure both launch paths, use `install-agent.bat -Target Both`. The installer understands
both vanilla's `vmparams` command line and Faster Rendering's `starsector-core\fr.vmparams` Java
argument file. For every changed file it creates a timestamped backup, replaces any existing entry
for this installation, and places Prepatcher after every other `-javaagent` option:

```text
-javaagent:../mods/StarsectorPrepatcher/agent/StarsectorPrepatcherAgent.jar
```

The folder name must not contain whitespace and should remain `StarsectorPrepatcher` after
installation. No additional `--add-exports` options are required; the agent exports the required JDK
ASM packages through `Instrumentation.redefineModule()`.

Install telemetry and other agents first, or rerun this installer afterward. Prepatcher must remain
the final `-javaagent` so its transformer sees the bytes returned by earlier agents.

The prepatcher does not modify save data, and its runtime caches are never serialized.

## Current patch areas

- sector, system, and Intel maps: reconciliation, spatial candidates, callbacks, hover checks,
  entity indexes, nebula metadata, scratch collections, and grid LOD;
- campaign and economy: lifecycle-bound caches, listener refresh, reusable snapshots, an aggressive
  staggered scheduler for remote markets, observation of direct mod `Market.advance()` calls,
  repeated absent commodity event-mod removal suppression, empty-script and empty-memory fast paths,
  and comm-relay candidates;
- routing: ordered jump-point and system indexes with vanilla selection and fallback semantics;
- combat and particles: internal scratch collections and stable deferred cleanup;
- loading and save paths: literal parsing, progress redraw, and output-path fixes;
- hyperspace: terrain culling, layer selection, seeded random reuse, owner-local automaton buffers,
  and moving-starfield cleanup.

The complete switch and invariant reference is in [`docs/PATCHES.md`](docs/PATCHES.md).

## Configuration and rollback

All settings are in `prepatcher.properties`. Every user-facing patch group has a dedicated `patch.*`
switch and requires a full game restart. The following disables the entire prepatcher:

```properties
enabled=false
```

`patch.loadingTextReader` and `patch.startupLogAggregation` remain disabled in all supplied profiles
because they participated in confirmed mission-startup failures. They will not be re-enabled until
their fixes pass an isolated startup and mission suite.

`patch.remoteMarketScheduler` is enabled in the default/aggressive profile and intentionally changes
`MarketAPI.advance()` cadence for remote markets. The current location, interaction market, and
player-owned markets remain full-rate; an individual market can opt out through the memory key
`$starsectorPrepatcher_fullRateMarket=true`. Use `profiles/safe.properties` or disable the switch for
fully conservative callback cadence.

`patch.directMarketObservation` is also enabled in the default/aggressive profile during the 0.9.2
development cycle. It does not throttle direct mod calls: each call remains synchronous and
immediate. It writes per-run CSV/stacks under `logs/direct-market-observe/session-*/`. Disable it
after collecting data to remove the sampling overhead.

Run `uninstall-agent.bat` for vanilla, `uninstall-agent.bat -Target FasterRendering` for FR, or
`uninstall-agent.bat -Target Both` to remove both managed entries. Each changed file is backed up.

## Diagnostics and verification

Runtime logs:

```text
mods\StarsectorPrepatcher\logs\prepatcher.log
mods\StarsectorPrepatcher\logs\direct-market-observe\session-*\
```

The agent records `APPLIED`, `ALREADY_APPLIED`, `SKIPPED_STRUCTURAL`, `SKIPPED_LOADER`, or
`SKIPPED_ERROR` for every patch. Hyperspace targets use the same structural, per-patch status model
as all other targets. `SKIPPED_LOADER` is a fail-open classloader guard and must be investigated
before calling that launch path compatible.

Run `verify-structural.bat` on Windows or `./verify-structural.sh` on Linux/macOS for the complete
documentation, structural, negative/idempotency, lifecycle/GC, runtime, hyperspace, and agent
startup suite. When `fr.jar` is present it also runs the real Faster Rendering classloader smoke.
Build details are in [`BUILDING.md`](BUILDING.md).

## Documentation

- [`README_RU.md`](README_RU.md) — Russian version of this overview;
- [`CHANGELOG.md`](CHANGELOG.md) — public `X.Y.Z` release history;
- [`BUILDING.md`](BUILDING.md) — build and verification workflow;
- [`docs/PATCHES.md`](docs/PATCHES.md) — patch switches and behavioral invariants;
- [`docs/COMPATIBILITY.md`](docs/COMPATIBILITY.md) — structural matching and fail-open rules;
- [`docs/VALIDATION.md`](docs/VALIDATION.md) — regression and performance validation playbook;
- [`docs/ROADMAP.md`](docs/ROADMAP.md) — structural discovery, architecture, tooling, and platform plan;
- [`docs/releases/0.9.1.md`](docs/releases/0.9.1.md) — current detailed release report.

StarsectorPrepatcher is distributed under the terms in [`LICENSE`](LICENSE).

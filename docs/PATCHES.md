# Карта патчей

## Модель применения

Основной agent выполняет независимое structural matching фактически загружаемого bytecode. Каждый
блок имеет ownership marker, postcondition, ASM verification и fail-open fallback.

Hyperspace-патчи проходят тот же независимый structural pipeline единого agent: каждый патч
проверяет target-класс, метод и descriptor, локальный bytecode-контракт, точное число точек
внедрения, ownership marker, postcondition и результат ASM verification. Allowlist полных SHA-256
не используется, поэтому оригинальный и переводной JAR принимаются автоматически, пока структура
конкретного изменяемого участка совместима; изменения строк и constant pool этому не мешают. При
неизвестном или неоднозначном участке только соответствующий патч остаётся vanilla со статусом
`SKIPPED_STRUCTURAL`, а остальные патчи рассматриваются независимо.

## Main agent

| Config | Target/область | Изменение | Основной compatibility-инвариант |
|---|---|---|---|
| `patch.retainAll` | map `A.H.renderStuff` | linear reconciliation вместо `keySet().retainAll(ArrayList)` | порядок `LinkedHashMap`, equality-aware fallback |
| `patch.scratchCollections` | map renderer/input | reusable internal lists/sets/vectors | no escape, reentrant scratch |
| `patch.labelSpatialCandidates` | map labels | spatial candidate buckets | original exact overlap checks остаются |
| `patch.intelCallbackCache` | Intel map | short TTL для `getMapLocation/getArrowData` | miss/error вызывает callback |
| `patch.intelEntityIndex` | Intel synthetic entities | identity index | generation/TTL/fallback |
| `patch.intelExistingIconLookup` | EventsPanel | direct icon lookup | full scan fallback |
| `patch.intelFastContains` | EventsPanel | hash membership | vanilla equality |
| `patch.hoverHitTestCache` | map hover | cell/TTL cache exact vanilla result | bounded staleness, miss runs original |
| `patch.systemNebulaCache` | map construction | immutable system metadata cache | synthetic entities создаются заново |
| `patch.sampleCacheClearThrottle` | map construction | suppress rapid repeated clear | configurable interval |
| `patch.gridLineCap` | huge sector map | dynamic grid spacing | визуальный LOD only |
| `patch.arrowVectorPool` | Intel arrows | internal vectors | reference не выходит наружу |
| internal lifecycle | `CampaignEngine.set/resetInstance` | clear all campaign-bound caches | two-phase generation boundary |
| `patch.campaignListenerThrottle` | campaign advance | skip unchanged internal repository listener sweep | public method untouched, periodic audit |
| `patch.campaignSnapshotReuse` | `BaseLocation` | reusable eager snapshots | point-in-time order/mutation isolation |
| `patch.entityScriptSnapshotReuse` | entity scripts | inline empty fast return; non-empty fresh vanilla snapshot | no scratch scope for empty lists; mutation isolation unchanged |
| `patch.emptyMemoryAdvanceFastPath` | `Memory.advance` | inline return when expire+require are empty | restoration/pause and non-empty loops remain vanilla |
| `patch.routeJumpPointIndex` | route widget | ordered jump/system candidates | original filter/distance/tie loop |
| `patch.economyLocationCache` | `Economy.advance` | omit only redundant automatic dirty write | explicit mod dirty state authoritative |
| `patch.economySnapshotReuse` | Economy/Market | reusable market/condition/industry snapshots | callback cadence/order unchanged |
| `patch.remoteMarketScheduler` | `Economy.advance` + pre-save boundary | stable-phase full-market scheduling with exact accumulated amount | direct mod calls immediate; hot markets full-rate; callback cadence of remote markets intentionally changes |
| `patch.directMarketObservation` | mod call sites + concrete `Market.advance` entry | observation-only synchronous wrappers, sampled inclusive timing and stack attribution | no delay/merge/suppression; original amount, multiplicity, thread and exceptions preserved |
| `patch.commodityEventModDirtyCache` | `CommodityOnMarket.reapplyEventMod` | skip repeated removal after zero quantity proved private `eMod` absent | first zero call/load and the complete nonzero remove/calculate/add path stay vanilla; direct external mutation of the private key is unsupported |
| `patch.commRelaySystemIndex` | `IntelManager` | conservative spatial system candidates + TTL position audit | original order/live relay checks; bounded coordinate staleness |
| `patch.shipAdvanceScratch` | `Ship.advance` | reuse 3 lists + command snapshot + 2 sets | fresh listener snapshot, no API objects pooled |
| `patch.particleCleanup` | `DynamicParticleGroup` | reuse expiry list + stable linear removal | all particles advance before removal |
| `patch.loadingTextReader` | `LoadingUtils` | streaming UTF-8 normalization | **known-disabled in 0.7.1 profiles:** mission startup regression requires separate fix |
| `patch.startupLogAggregation` | loaders/specs/rules/sound | remove/aggregate high-volume INFO | **known-disabled in 0.7.1 profiles:** mission startup regression requires separate fix |
| `patch.rulesLiteralParser` | rules loader | literal fixed-delimiter operations | randomized differential semantics |
| `patch.saveLoadProgressThrottle` | progress streams | redraw ceiling | final forced updates retained |
| `patch.saveOutputBufferDedup` | save output chain | remove one duplicate outer buffer | save bytes/format/close chain unchanged |

Оба startup-патча остаются в коде и structural/runtime suites, но все поставляемые профили держат
их выключенными. Они не возвращаются в default/safe/aggressive до изолированного исправления и
успешного startup/mission прогона каждого переключателя по отдельности.

### Агрессивный remote-market scheduler

`patch.remoteMarketScheduler` меняет только один engine call site — интерфейсный вызов
`MarketAPI.advance(amount)` внутри ordered snapshot-loop `Economy.advance()`. Прямые вызовы
`MarketAPI.advance()` из модов и других engine paths остаются vanilla-immediate.

Настройки default/aggressive profile:

```properties
patch.remoteMarketScheduler=true
market.remote.frames=4
market.remote.hiddenFrames=8
market.remote.maxDeferredFrames=8
market.remote.maxDeferredGameDays=0.02
market.hot.currentLocation=true
market.hot.playerOwned=true
market.hot.interaction=true
market.remote.policyAuditFrames=60
market.remote.fullRateMemoryKey=$starsectorPrepatcher_fullRateMarket
```

Для skipped frame исходный `amount` суммируется. При stable phase, safety cap, переходе в hot state
или pre-save flush рынок получает полный накопленный interval одним вызовом. Новый рынок всегда
получает первый вызов немедленно. Due markets по-прежнему вызываются в исходном порядке market
snapshot; scheduler не запускает background threads и не сериализует state.

Performance-first компромиссы:

- frame-count callback cadence удалённых conditions/industries/submarkets уменьшается;
- RNG sequence plugin'ов, вызывающих random один раз на callback, может измениться;
- точное состояние, наблюдаемое одним рынком у другого между staggered phases, может отставать на
  ограниченное окно;
- direct mutation memory opt-out может стать видимой не позднее `policyAuditFrames`;
- накопленный elapsed time сохраняется, но plugin, игнорирующий `amount` и считающий callbacks,
  поведёт себя иначе.

Full-rate остаются текущая location, interaction market, player-owned markets и рынки с:

```java
market.getMemoryWithoutUpdate().set(
        "$starsectorPrepatcher_fullRateMarket", true);
```

`profiles/safe.properties` отключает scheduler. Nested/reentrant `Economy.advance()` определяется
через reentrant scope и полностью уходит в immediate fallback, не меняя frame context внешнего
прохода.

### Level 1: observation прямых mod-вызовов Market.advance

`patch.directMarketObservation` не является scheduler-патчем. Он предназначен для диагностического
прогона перед проектированием robust direct-call policy и **не меняет cadence или результат**
прямых вызовов.

Отдельный transformer рассматривает mod bytecode и заменяет только прямые вызовы:

```text
invokeinterface MarketAPI.advance(F)V
invokevirtual   Market.advance(F)V
```

на typed wrapper, который:

1. записывает site ID и дешёвые counters;
2. при выбранном sampling измеряет inclusive время и снимает stack;
3. синхронно вызывает исходный `market.advance(amount)` в том же потоке;
4. сохраняет multiplicity, float `amount`, порядок и exception propagation.

Concrete vanilla `Market.advance(float)` дополнительно имеет дешёвый entry probe. Calls от
central scheduler, его fail-open пути, pre-save flush и instrumented mod sites помечаются через
`ThreadLocal` origin. Непомеченный вход классифицируется как `UNKNOWN_DIRECT` и получает только
ограниченное число stack samples; это позволяет обнаруживать reflection/MethodHandle и
нестандартные loaders.

Настройки default/aggressive profile:

```properties
patch.directMarketObservation=true
directMarket.timingSampleEvery=128
directMarket.stackSampleEvery=2048
directMarket.maxStacksPerSite=8
directMarket.reportIntervalSeconds=15
directMarket.maxSites=4096
directMarket.unknownStackSamples=32
```

Каждый запуск создаёт отдельную session-директорию:

```text
logs/direct-market-observe/session-<UTC>-pid<PID>/
```

Для анализа нужны все файлы session вместе с `logs/prepatcher.log`. Observer не удерживает
`MarketAPI` references, не пишет данные в save, работает только на daemon reporter thread и
fail-open при любой telemetry/file ошибке. Ненулевой диагностический overhead принят по дизайну;
`profiles/safe.properties` держит этот переключатель выключенным.

## Hyperspace

| Config | Target | Изменение | Принятое поведение/риск |
|---|---|---|---|
| `patch.hyperspaceCulling` | `BaseTiledTerrain.render/isTileVisible` | third width load in yEnd changed to height | affects every subclass by design |
| `patch.hyperspaceYClamp` | same | second end clamp uses inner dimension | affects every subclass by design |
| `patch.skipNoOpTerrainLayer` | `HyperspaceTerrainPlugin.getActiveLayers` | removes `TERRAIN_9` from backing set | also skips that layer's `preRender` sequence by design |
| `patch.terrainRandomReuse` | tiled/hyperspace terrain | seeded `Random` ring + batched diagnostics | same seed/draw sequence; cumulative approximate counter; no per-tile `LongAdder`/clock call |
| `patch.automatonBufferReuse` | automaton + terrain internal reads | owner-local spare `int[][]`; confirmed exact-owner internal reads bypass public escape mark | public/mod/subclass/unconfirmed paths use virtual getter; escaped aliases are never reused; transient state, zero-init |
| `patch.starfieldCleanupBuffers` | parallax starfield implementation | reusable stale list | two-phase cleanup retained |
| `patch.starfieldLinearRemoval` | same | thresholded stable iterator removal | order retained; equality-aware fallback |

## Намеренно не реализовано

- storm/automaton update throttling;
- dropped simulation debt;
- неявные callback-frequency changes вне явно документированного `patch.remoteMarketScheduler`;
- public combat-grid reuse;
- generic particle object pooling;
- fleet-pair broadphase без runtime parity harness;
- GL batching/FBO/VBO;
- inter-frame terrain geometry cache;
- save-format или serialized-object changes.

# Карта патчей

## Модель применения

Основной agent выполняет независимое structural matching фактически загружаемого bytecode. Каждый
блок имеет ownership marker, postcondition, ASM verification и fail-open fallback.

Hyperspace-agent применяет узкий exact-target подход: проверяет SHA-256 фактически изменяемого
класса и точное число каждого bytecode-паттерна. Allowlist содержит clean и подтверждённый
локализованный варианты `HyperspaceTerrainPlugin`; изменения строк в остальном JAR не отключают
независимые патчи. Неизвестный target-класс остаётся vanilla и переводит runtime status в заметный
`target-guard-failed`.

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

## Hyperspace agent

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
- callback-frequency changes;
- public combat-grid reuse;
- generic particle object pooling;
- fleet-pair broadphase без runtime parity harness;
- GL batching/FBO/VBO;
- inter-frame terrain geometry cache;
- save-format или serialized-object changes.

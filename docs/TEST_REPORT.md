# Build and validation report — 0.4.0-exp6

Дата exp6 build/structural/runtime regression: 2026-07-17. Structural harness прошёл на двух core
JAR, lifecycle GC и отдельный exp6 runtime regression — успешно. Графический campaign smoke и
контролируемый allocation/frame-time A/B для exp6 ещё не перезапускались; последний полный
campaign smoke относится к exp4 (2026-07-17).

## Проверенные входы

- чистый Starsector 0.98a-RC8: `C:\Games\Starsector\starsector-core\starfarer_obf.jar`;
- локализованная сборка 0.98a-RC8: `C:\Games\Starsector_test\starsector-core\starfarer_obf.jar`.

У соответствующих exp5 target methods обеих сборок атрибуты `Code` совпадают. Отличия изменённых
классов состоят в строковых константах перевода. Никакие JAR/class digests не используются
transformer-ом или installer-ом. Exp6 harness отдельно подтвердил `BaseLocation`,
`BaseCampaignEntity` и `Memory` на обоих фактически прочитанных JAR.

## Результат exp6 structural/GC/runtime harness

Команда:

```powershell
.\verify-structural.ps1 -CoreJars `
  'C:\Games\Starsector\starsector-core\starfarer_obf.jar', `
  'C:\Games\Starsector_test\starsector-core\starfarer_obf.jar'
```

Результат:

```text
OK structural jar=C:\Games\Starsector\starsector-core\starfarer_obf.jar classes=9 verifiedMethods=672
OK structural jar=C:\Games\Starsector_test\starsector-core\starfarer_obf.jar classes=9 verifiedMethods=672
OK negative-tests retainAll missing ambiguous unrelated-call unrelated-change marker-ownership scratch-scope-tamper wrapper-tamper wrapper-metadata idempotency lifecycle-missing-write lifecycle-wrong-source exp6-marker-ownership exp6-scratch-scope-tamper
SUMMARY jars=2 transformedClasses=18 verifiedMethods=1344
OK lifecycle-gc exact-caches=8 scratch-remove identity-idempotence two-phase-boundary engine-change reset-null generation-active-state generation-epoch lock-free-detach nebula-slot-detach out-of-order-fail-closed weak-cache-reachability weak-engine-arguments
OK exp6-runtime snapshot-isolation snapshot-reuse snapshot-reentrancy snapshot-fallback snapshot-no-retention empty-snapshot-singleton exceptional-snapshot-no-retention memory-iterator-empty-singleton memory-iterator-order memory-iterator-remove memory-iterator-no-retention
```

Для каждого класса проверено:

- точное число ожидаемых hooks;
- существование каждого emitted hook с точным descriptor и `public static` access в runtime-классе;
- `Analyzer<BasicValue>` + `BasicVerifier` для всех concrete methods;
- повторный parse после сериализации;
- неизменность public/protected field и method surface;
- полное покрытие normal/exceptional exits scratch scope и явный `F_FULL` handler frame;
- ownership marker, точная wrapper wiring и перенос method metadata;
- второй transform возвращает `null`, а каждый блок определяется как `ALREADY_APPLIED`;
- отсутствие новых `SKIPPED_STRUCTURAL` при idempotency pass.

## Observed transformation counts

| Target | Hooks/sites |
|---|---:|
| `H` | scratch 2, retainAll 1, labels 1, Intel index 1, nebula 1, sample throttle 1, grid 5 |
| `A` | scratch 2, map-location 1, hover wrapper 1 |
| `Z` | arrow callback 1, vectors 2 |
| `EventsPanel` | map-location 7, fast contains 1, existing-icon lookup 1 |
| `CampaignEngine` | lifecycle begin 2, completion 2, listener refresh 1 |
| `O0Oo` | jump candidates 3, system candidates 1 |
| `BaseLocation` | defensive snapshots 5, scratch scopes 2 |
| `BaseCampaignEntity` | script snapshot 1, scratch scope 1 |
| `Memory` | empty iterator fast paths 2 |

Размеры шести прежних clean target classes (exp5 baseline):

```text
H:              35,299 -> 36,376 bytes
A:              21,586 -> 21,868 bytes
Z:              12,290 -> 12,496 bytes
EventsPanel:    37,230 -> 37,401 bytes
CampaignEngine: 47,854 -> 47,839 bytes
O0Oo:           19,321 -> 19,400 bytes
```

Размеры шести прежних localized target classes (exp5 baseline):

```text
H:              35,299 -> 36,376 bytes
A:              21,869 -> 22,151 bytes
Z:              12,290 -> 12,496 bytes
EventsPanel:    37,372 -> 37,543 bytes
CampaignEngine: 47,992 -> 47,909 bytes
O0Oo:           19,530 -> 19,609 bytes
```

## Отрицательные проверки

На копиях `H` в памяти проверено:

- unrelated instruction change не блокирует совместимый `retainAll` patch;
- дополнительный несвязанный `Set.retainAll` не принимается за map reconciliation;
- отсутствующий semantic site даёт `SKIPPED_STRUCTURAL` только для `retainAll`;
- два одинаковых semantic sites считаются неоднозначными и не патчатся;
- остальные шесть блоков `H` продолжают применяться после локального skip;
- hook-shaped bytecode без ownership marker не принимается за уже установленный патч;
- owned scratch hooks с удалённым scope не получают ложный `ALREADY_APPLIED`;
- посторонняя инструкция в wrapper даёт локальный `SKIPPED_STRUCTURAL`;
- method annotation переносится на публичный wrapper и не остаётся на private original;
- lifecycle patch отвергается, если `resetInstance` не пишет singleton или `setInstance` пишет
  значение не из argument 0; независимый listener patch при этом продолжает устанавливаться;
- hook-shaped exp6 bytecode без exp6 ownership marker не принимается за `ALREADY_APPLIED`;
- `BaseLocation` snapshot hooks без полного scratch scope дают локальный `SKIPPED_STRUCTURAL`;
- каждый полученный класс проходит `BasicVerifier`.

## Подтверждённые compatibility assumptions

- Номера local slots в `O0Oo` и `EventsPanel` выводятся через `SourceValue`, а не фиксируются.
- Три nebula lists определяются по producer/add chains.
- Grid matcher выбирает четыре bounds и один step; radar `2000f` не затрагивается.
- Campaign systems/hyperspace fields выводятся из `readdChangeListeners` и передаются hook напрямую.
- Lifecycle matcher доказывает единственное static self-typed singleton field и data-flow
  `setInstance(argument 0)`/`resetInstance(null)`, begin placement перед `PUTSTATIC` и token-matched
  completion placement перед каждым normal `RETURN`.
- Route backing systems field во время выполнения определяется уникальным identity-сопоставлением
  с public `getStarSystems()` result, а не по имени поля.
- Route indexes сверяют полный identity/relationship snapshot раз в TTL; обычный hit остаётся
  `O(1)`, а safe profile этот TTL-патч не включает.
- `BaseLocation`/`BaseCampaignEntity` snapshot locals принимаются только при доказанном
  iterator-only использовании без merge, mutation или escape и полном scratch scope.
- `Memory.advance` hooks принимаются только для expire list и `LinkedHashMap.values()` require
  view в прежнем порядке после clock conversion.
- Wrapper originals становятся private synthetic; публичная сигнатура остаётся исходной.

## Exp6 telemetry-driven target selection (A/B pending)

Источник: `StarsectorMapTelemetry\telemetry\session-20260717-023929-040`. Записано примерно
`38.2 s` / `3 084` frames в hyperspace огромного modded-сектора: `2 137` systems,
`432 809` campaign entities, `17 775` hyperspace entities и `66` mods.

Pre-change allocation telemetry показала `14.209 GB` за `37.725 s`, то есть около
`376.6 MB/s` и `4.607 MB/frame`. Это общая аллокация наблюдавшегося процесса до exp6, а не
оценка сэкономленного объёма. В JFR sampled allocation profile после исключения смещённого первого
sample крупными семействами были:

- MagicPaintjob `HashSet -> List` copies: приблизительно `4.886 GB` / `37%` sampled bytes;
- `campaign.rules.Memory` iterator churn: приблизительно `4.196 GB` / `32%`;
- по типам: object arrays около `54%`, `ArrayList` iterators около `18%`,
  `LinkedHashMap` iterators около `16%`.

Raw CPU samples были в основном внутри campaign advance: `CampaignEngine.advance` — `86.6%`
inclusive, `Hyperspace.advance` — `35.9%`, `StarSystem.advance` — `19.8%`,
economy/market — `16.2%`, `Memory` — `10.5%` inclusive. `BaseLocation.advance` дал `13.9%`
leaf samples. Проценты inclusive пересекаются и не должны складываться. Snapshot copies
`BaseLocation` были крупным allocation stack; `BaseCampaignEntity.runScripts` snapshots также
присутствовали в sampled stacks для jump points, asteroids и gravity wells. Сам Map Optimizer не
был верхним CPU hotspot этого профиля.

Эти наблюдения мотивировали три включённых по умолчанию exp6-блока:

- `patch.campaignSnapshotReuse` — пять `BaseLocation` snapshot-sites;
- `patch.entityScriptSnapshotReuse` — один `BaseCampaignEntity.runScripts` snapshot-site;
- `patch.emptyMemoryAdvanceFastPath` — два empty iterator-sites `Memory.advance`.

Structural/negative harness подтвердил все восемь sites на обоих core JAR. Отдельный JVM runtime
regression подтвердил snapshot isolation/reuse/reentrancy/fallback, normal и exceptional
no-retention, empty snapshot singleton, а также empty/non-empty `Memory` iterator order/remove и
no-retention. Это ещё не заменяет графический campaign `APPLIED`/behavior run и off/on A/B.
Для измерения эффекта нужен одинаковый campaign scenario с новыми toggles off/on и сопоставлением
allocation rate/types, frame time и call activity. Исходная telemetry подтверждает приоритет
targets, но не измеренный эффект exp6.

## Подтверждённые runtime evidence предыдущих версий

Предыдущая пользовательская телеметрия показала примерно 10 -> 60 FPS после map fix. В проблемной
локализованной сессии 93.33% steady global-map samples находились в vanilla
`H.renderStuff -> retainAll -> ArrayList.contains`; exp5 успешно устанавливает этот hook на
том же локализованном JAR.

17 июля для exp5 выполнен отдельный GC harness против собранного agent JAR. Он заполнил все восемь
static map-кэшей, `nebulaCacheSlot.value` и scratch одним sentinel, затем проверил engine replace и
`reset(null)`. После lifecycle reset sentinel и прежние engine arguments были помещены в
`ReferenceQueue`; все кэши оказались пусты, generation epoch менялся только при смене identity,
generation-active flag оставался выключен между begin/completion и после reset. Harness также
подтвердил volatile detachment всех восьми map-roots и nebula-slot, а также fail-closed очистку при
out-of-order completion token.
Harness намеренно отказывается работать с `-XX:+DisableExplicitGC`.

До этого для exp4 был выполнен полный графический campaign smoke test на большой modded-сборке.
Bootstrap записал в `starsector.log` статус `structural-transformer-installed`; ранняя строка
показывает один уже загруженный target и `skippedPatchesSoFar=0`. После открытия campaign UI/map/Intel
`logs/map-optimizer.log` содержит 17 `APPLIED` для всех 14 patch id и не содержит `WARN`, `ERROR`,
`SKIPPED_STRUCTURAL` или `SKIPPED_ERROR`.

Суммарные activity counters за записанные интервалы:

```text
retainCalls=13383, avoidedContainsUpperBound=473079916544, retainKeysScanned=27456733
labelCandidates=2798/1019091, hoverHits/Misses=2122/3929
mapLocationHits/Misses=506/102, arrowHits/Misses=8355/985
intelIndexHits/Builds=47/12, nebulaHits/Misses=6/1
campaignListenerRuns/Skips=203/17762
routeJumpIndexHits/Builds/Fallbacks=6636/1/0
```

Также сработал grid LOD: для сектора `492000 x 312000` spacing вырос с `2000` до `4000`. Route
system index и sample-clear skip в этой сессии не были задействованы. В `starsector.log` остаются
ошибки отсутствующих weapon/hull/resource specs сторонних модов, но stack trace или ошибки
Map Optimizer отсутствуют.

Отдельная frame-time/allocation телеметрия в exp4 smoke-запуске не записывалась, поэтому новых численных
утверждений о производительности он не добавляет. Матрица ещё не закрытых behavior/A-B проверок
ведётся в [`PATCH_VALIDATION_CHECKLIST.md`](PATCH_VALIDATION_CHECKLIST.md).

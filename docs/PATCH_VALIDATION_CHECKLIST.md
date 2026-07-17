# Чеклист доказательности патчей

Этот документ отделяет конфигурацию запуска от доказательств корректности. Профиль с одним
включённым патчем сам по себе ничего не доказывает: для каждого `patch.*` нужны воспроизводимые
структурные, runtime, функциональные и производительные проверки.

## Уровни доказательств

- **S — structural:** semantic site однозначно найден; postconditions, API surface и весь класс
  проходят `BasicVerifier`.
- **N — negative:** отсутствие, дублирование или конфликт site приводит к локальному
  `SKIPPED_STRUCTURAL`; несвязанные изменения не мешают патчу.
- **R — runtime:** на реальной modded-сборке есть `APPLIED`/`ALREADY_APPLIED`, нет
  `SKIPPED_ERROR`, optimizer exception или падения игры.
- **A — activity:** счётчик или отдельный лог подтверждает, что оптимизированный путь реально
  выполнялся, а не только загрузился.
- **B — behavior:** целевой сценарий пройден без визуальной, игровой или callback-регрессии;
  fallback и граничные случаи проверены отдельно.
- **P — performance:** получена A/B-телеметрия на одном сохранении, сцене, масштабе и настройках;
  сравниваются frame time/allocations/call counts, а не только субъективный FPS.

Заявление «патч совместим» требует как минимум S+R. Заявление «патч корректен» требует S+N+R+B.
Заявление о приросте производительности требует также A+P. Общий smoke test не заменяет
изолированный B/P-прогон патча.

## Общий чеклист для каждого `patch.*`

- [ ] Описаны target, исходная сложность/аллокация и сохраняемая vanilla-семантика.
- [ ] Positive structural test проходит на clean и localized/modded core JAR.
- [ ] Есть mutation tests для missing, ambiguous и unrelated-change случаев.
- [ ] Проверены точные hook descriptors/counts, ownership marker, idempotency и public API.
- [ ] Все concrete methods преобразованного класса проходят ASM verifier после сериализации.
- [ ] В реальном запуске target дал `APPLIED` или точный `ALREADY_APPLIED` без skip/error.
- [ ] Целевой экран/сценарий вызвал отдельный activity signal.
- [ ] Проверены функциональная эквивалентность, cache miss/fallback и modded edge case.
- [ ] Для заявленного ускорения записан одинаковый A/B-сценарий с патчем off/on.
- [ ] Дата, версия JAR, конфигурация и результаты занесены в `TEST_REPORT.md`.

## Матрица целевых сценариев

| Патч | Сценарий для A/B | Activity signal | Обязательная проверка поведения |
|---|---|---|---|
| `patch.retainAll` | global/system map с тысячами entities/icons | `retainCalls`, `avoidedContainsUpperBound`, `retainKeysScanned/Removed`, `retainEqualityFallbacks` | тот же набор и порядок иконок; отдельно custom keys с `equals/hashCode` |
| `patch.scratchCollections` | длительный map render + движение мыши | allocation profile/GC; косвенно вызовы render/hover | reentrant и exceptional exits не оставляют элементы scratch; нет cross-frame данных |
| `patch.labelSpatialCandidates` | global map на нескольких zoom и плотных кластерах | `labelCandidates=candidates/total` | те же решения о размещении/скрытии подписей на границах bucket и после TTL rebuild |
| `patch.hoverHitTestCache` | движение/остановка мыши над объектами и пустым hyperspace | `hoverHits`, `hoverMisses` | hover меняется не позднее TTL/cell boundary; click target остаётся правильным |
| `patch.intelCallbackCache` | Intel map со стрелками и plugins разных модов | `mapLocationHits/Misses`, `arrowHits/Misses` | callback return/`null`/exception fallback; допустима только заявленная TTL-видимость изменений |
| `patch.intelEntityIndex` | многократное обновление Intel icons | `intelIndexHits`, `intelIndexBuilds` | identity, а не `equals`; удаление/замена plugin становится видимой после rebuild |
| `patch.intelExistingIconLookup` | добавление, обновление и удаление Intel rows/icons | dedicated counter или instrumentation EventsPanel | direct lookup совпадает с vanilla scan; duplicate/missing key уходит в fallback |
| `patch.intelFastContains` | большой missing-list при перестроении Intel | dedicated counter или CPU profile EventsPanel | custom equality и duplicate entries дают тот же набор missing icons |
| `patch.arrowVectorPool` | Intel map с большим числом стрелок | allocation profile `Vector2f` | координаты/углы стрелок совпадают; pooled vectors не утекают наружу |
| `patch.systemNebulaCache` | повторно открыть system/global map, затем сменить систему | `nebulaHits`, `nebulaMisses` | synthetic entities каждый раз новые; star/nebula/constellation metadata актуальны после invalidation |
| `patch.sampleCacheClearThrottle` | быстро закрывать/открывать hyperspace map | `sampleClearSkips` | после фактического clear и после истечения interval terrain samples корректны |
| `patch.gridLineCap` | огромный сектор на min/max zoom | `Map grid LOD: ... spacing=...` | координаты и hit tests неизменны; меняется только плотность grid, без gaps/мерцания |
| internal `campaignCacheLifecycle` | load → map/Intel/route → reload/reset, несколько поколений | `campaignCacheResets`; GC harness/heap roots | после reset все campaign caches пусты, старые engine собираются; до lifecycle-ready используется vanilla fallback |
| `patch.campaignListenerThrottle` | закрытая карта, создание/удаление системы и custom list mutation | `campaignListenerRuns`, `campaignListenerSkips` | repositories получают правильный listener сразу при обычном изменении и не позже audit при прямой мутации |
| `patch.campaignSnapshotReuse` | одинаковый campaign advance в hyperspace и системах, включая paused/reentrant/throwing callbacks | JFR allocation stacks `BaseLocation.advance`; counts пяти snapshot hooks | тот же point-in-time набор и порядок entities/plugins; no escape; normal/exceptional exits обнуляют campaign refs |
| `patch.entityScriptSnapshotReuse` | entities с пустым и непустым script list, script добавляет/удаляет scripts и бросает exception | JFR allocation stacks `BaseCampaignEntity.runScripts`; count одного snapshot hook | тот же snapshot/order и число вызовов scripts; reentrant/exceptional cleanup не сохраняет entity/script refs |
| `patch.emptyMemoryAdvanceFastPath` | тысячи пустых `Memory` плюс отдельные expire/require entries | JFR `ArrayList$Itr`/`LinkedHashMap$LinkedValueIterator` allocations; counts двух hooks | пустые scans остаются пустыми; expiration, require cleanup/order и iterator remove на непустом пути совпадают с vanilla |
| `patch.routeJumpPointIndex` | построение маршрутов внутри/между системами, wormholes и одинаковые anchors | `routeJump/SystemIndex Hits/Builds/Fallbacks` | destination, distance и tie-break совпадают с vanilla; miss/malformed/custom getter использует полный список |

## Состояние exp6 allocation follow-up, 2026-07-17

Новые targets выбраны по pre-change profiler/JFR-сессии большой modded-кампании: в ней видны
defensive snapshot allocations `BaseLocation`/`BaseCampaignEntity` и значительный iterator churn
`Memory`. Exp6 structural harness прошёл на двух core JAR: `18` transformed classes,
`1 344` verified methods; positive/idempotency и negative ownership/scratch-scope cases
подтверждены. Отдельный JVM runtime regression прошёл snapshot isolation/reuse/reentrancy/fallback,
normal/exceptional no-retention и `Memory` empty/non-empty iterator order/remove/no-retention.

Эти результаты закрывают S+N и отдельную JVM-проверку semantics/reachability. Они не являются
реальным modded-campaign R/B и performance-доказательством exp6. Для R/B нужен графический запуск
с `APPLIED` и целевыми сценариями; для P — одинаковые save, duration, game state и profiler
settings с тремя новыми toggles off/on. Pre-change телеметрия подтверждает только активность и
приоритет targets, а не измеренный эффект.

## Состояние exp5 lifecycle validation, 2026-07-17

Structural harness подтвердил четыре two-phase lifecycle hook (два begin + два completion) на clean
и localized JAR, их token placement/idempotency и локальный skip при missing/wrong singleton write.
Отдельный JVM harness против собранного agent JAR проверил очистку восьми maps, `nebulaCacheSlot.value`,
scratch, inactive state между фазами, volatile map/nebula-slot detachment, out-of-order completion fail-closed,
generation-active state/epoch и сборку прежних
cache sentinel/engine arguments через `WeakReference` + `ReferenceQueue`. Полный графический
многократный save-reload прогон exp5 остаётся отдельной интеграционной проверкой.

## Состояние runtime smoke test exp4, 2026-07-17

На большой modded-сборке применены все 17 sites для 14 patch id; в логе оптимизатора нет
`WARN`, `ERROR`, `SKIPPED_STRUCTURAL` или `SKIPPED_ERROR`. Подтверждена активность:

- `retainAll`: 13 383 вызова, верхняя оценка 473 079 916 544 избегнутых `contains`;
- labels: 2 798 candidates из 1 019 091 исходных элементов;
- hover: 2 122 hits / 3 929 misses;
- Intel callbacks: map location 506/102, arrows 8 355/985 hits/misses;
- Intel entity index: 47 hits / 12 builds;
- system nebula: 6 hits / 1 miss;
- campaign listeners: 203 runs / 17 762 skips;
- route jump index: 6 636 hits / 1 build / 0 fallbacks;
- grid LOD: spacing увеличен с 2 000 до 4 000 для сектора 492 000 × 312 000.

В этом запуске не были положительно задействованы `sampleClearSkips` и route system index; у
scratch, direct Intel lookup, fast contains и vector pool пока нет отдельных activity counters.
Телеметрия frame time/allocations не записывалась, поэтому smoke test даёт R и часть A, но не
закрывает B/P. Подробности запуска находятся в `TEST_REPORT.md`.

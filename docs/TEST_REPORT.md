# Build and validation report — 0.4.0-exp4

Дата: 2026-07-16; runtime smoke test: 2026-07-17.

## Проверенные входы

- чистый Starsector 0.98a-RC8: `C:\Games\Starsector\starsector-core\starfarer_obf.jar`;
- локализованная сборка 0.98a-RC8: `C:\Games\Starsector_test\starsector-core\starfarer_obf.jar`.

У соответствующих target methods обеих сборок атрибуты `Code` совпадают. Отличия изменённых
классов состоят в строковых константах перевода. Никакие JAR/class digests не используются
transformer-ом или installer-ом.

## Результат structural harness

Команда:

```powershell
.\verify-structural.ps1 -CoreJars `
  'C:\Games\Starsector\starsector-core\starfarer_obf.jar', `
  'C:\Games\Starsector_test\starsector-core\starfarer_obf.jar'
```

Результат:

```text
OK structural jar=C:\Games\Starsector\starsector-core\starfarer_obf.jar classes=6 verifiedMethods=358
OK structural jar=C:\Games\Starsector_test\starsector-core\starfarer_obf.jar classes=6 verifiedMethods=358
OK negative-tests retainAll missing ambiguous unrelated-call unrelated-change marker-ownership scratch-scope-tamper wrapper-tamper wrapper-metadata idempotency
SUMMARY jars=2 transformedClasses=12 verifiedMethods=716
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
| `CampaignEngine` | listener refresh 1 |
| `O0Oo` | jump candidates 3, system candidates 1 |

Размеры clean classes:

```text
H:              35,299 -> 36,376 bytes
A:              21,586 -> 21,868 bytes
Z:              12,290 -> 12,496 bytes
EventsPanel:    37,230 -> 37,401 bytes
CampaignEngine: 47,854 -> 47,559 bytes
O0Oo:           19,321 -> 19,400 bytes
```

Размеры localized classes:

```text
H:              35,299 -> 36,376 bytes
A:              21,869 -> 22,151 bytes
Z:              12,290 -> 12,496 bytes
EventsPanel:    37,372 -> 37,543 bytes
CampaignEngine: 47,992 -> 47,629 bytes
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
- каждый полученный класс проходит `BasicVerifier`.

## Изменённые compatibility assumptions

- Номера local slots в `O0Oo` и `EventsPanel` выводятся через `SourceValue`, а не фиксируются.
- Три nebula lists определяются по producer/add chains.
- Grid matcher выбирает четыре bounds и один step; radar `2000f` не затрагивается.
- Campaign systems/hyperspace fields выводятся из `readdChangeListeners` и передаются hook напрямую.
- Route backing systems field во время выполнения определяется уникальным identity-сопоставлением
  с public `getStarSystems()` result, а не по имени поля.
- Route indexes сверяют полный identity/relationship snapshot раз в TTL; обычный hit остаётся
  `O(1)`, а safe profile этот TTL-патч не включает.
- Wrapper originals становятся private synthetic; публичная сигнатура остаётся исходной.

## Runtime evidence

Предыдущая пользовательская телеметрия показала примерно 10 -> 60 FPS после map fix. В проблемной
локализованной сессии 93.33% steady global-map samples находились в vanilla
`H.renderStuff -> retainAll -> ArrayList.contains`; exp4 теперь успешно устанавливает этот hook на
том же локализованном JAR.

17 июля выполнен полный графический campaign smoke test на большой modded-сборке. Bootstrap записал
в `starsector.log` статус `structural-transformer-installed`; ранняя строка показывает один уже
загруженный target и `skippedPatchesSoFar=0`. После открытия campaign UI/map/Intel
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

Отдельная frame-time/allocation телеметрия в этом запуске не записывалась, поэтому новых численных
утверждений о производительности он не добавляет. Матрица ещё не закрытых behavior/A-B проверок
ведётся в [`PATCH_VALIDATION_CHECKLIST.md`](PATCH_VALIDATION_CHECKLIST.md).

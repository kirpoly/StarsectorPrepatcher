# Совместимость и применение патчей

Основной agent не сравнивает SHA-256 игры, JAR или target-классов. Каждый его `patch.*`
независимо проверяет локальный контракт фактически загружаемого JVM bytecode. Hyperspace-agent
изменяет targets из `starfarer.api.jar` и `starfarer_obf.jar` и использует отдельный fail-open
allowlist SHA-256 для каждого фактически изменяемого класса вместе с проверкой точного числа
bytecode-sites.

## Жизненный цикл патча

1. Найти ровно один целевой метод по имени и descriptor.
2. Найти ожидаемые symbolic call/field/allocation sites.
3. Через ASM `Analyzer<SourceValue>` проверить происхождение receiver, аргументов и local values.
4. Отличить три состояния: исходный код, уже установленный этим агентом патч и
   конфликт/неоднозначность. Владение подтверждается отдельным private synthetic marker вместе с
   полной postcondition, а не только похожим вызовом hook или inline guard. Для inline fast paths
   дополнительно сверяются вся входная последовательность, data-flow полей и исходный remainder
   метода. Marker использует стабильную версию patch schema и не меняется при обычном SemVer bump
   релиза.
5. Изменить отдельную копию текущего класса.
6. Проверить postconditions: точные имена/descriptors и число hooks, отсутствие заменённых sites,
   точную wiring-схему wrapper и неизменность public/protected API.
7. Сериализовать, повторно прочитать класс и прогнать все concrete methods через
   `Analyzer<BasicValue>` + `BasicVerifier`.
8. Только после успеха передать новые bytes следующему transformer. При ошибке пропускается
   один патч; ранее подтверждённые изменения того же класса сохраняются.

Строковые литералы, constant-pool ordering, line numbers и debug metadata не являются частью
решения о совместимости. Поэтому переводы и изменения несвязанных методов не блокируют патчи.

## Статусы

В `logs/prepatcher.log` для каждого загруженного target выводится один из статусов:

- `APPLIED` — контракт совпал, результат верифицирован;
- `ALREADY_APPLIED` — точный postcondition уже присутствует;
- `SKIPPED_STRUCTURAL` — site отсутствует, неоднозначен или конфликтует;
- `SKIPPED_ERROR` — анализ/сериализация завершились непредвиденной ошибкой.

Количество применённых и пропущенных патчей также экспортируется в system properties и
показывается bootstrap-плагином в `starsector.log`.

## Основные data-flow контракты

- `H.renderStuff`: `LinkedHashMap.keySet() -> Set.retainAll(entityList)`, результат отбрасывается.
- `H.getTextAlignmentFor`: `getIcons` backing field -> `LinkedHashMap.values -> iterator`.
- `H.getIntelIconEntity`: одна iterated Intel list, `customData[INTEL_ICON_DATA_KEY]`, identity
  comparison с argument plugin и `null` на miss.
- `H.updateSystemNebulas`: три output lists сопоставляются по `createNebula`,
  `createConstellationLabel` и `StarSystemAPI.getStar`, а не по порядку полей.
- `H.null(F)V`: четыре `FLOAD / 2000f / FDIV / F2I` bounds и один
  `2000f * mapScale` step после starscape guard. Radar constant не меняется.
- `EventsPanel.addMissingIconsAndRows`: entity local берётся из результата
  `H.getIntelIconEntity`; missing-list должен быть свежим локальным `ArrayList`.
- `CampaignEngine.advance`: receiver — `this`; backing systems/hyperspace fields выводятся из
  реализации `readdChangeListeners` и передаются hook напрямую.
- `BaseLocation.advance` и `advanceEvenIfPaused`: matcher требует соответственно ровно три и два
  `new ArrayList(Collection)`, каждый результат должен сразу записываться в отдельный local.
  Data-flow разрешает только ожидаемые вызовы `List.iterator()` от этого local (один на snapshot,
  кроме одного paused snapshot с двумя итерациями), без merge, mutation, escape или иных uses.
- `BaseCampaignEntity.runScripts`: исходный метод должен начинаться с единственного
  `new ArrayList(this.scripts)`, сразу записанного в local и использованного ровно одним
  `List.iterator()`. Inline guard читает то же поле `this.scripts`, возвращает только на
  `isEmpty()` и на non-empty branch попадает в исходную первую инструкцию. Сторонний исполняемый
  пролог, другой receiver, partial guard или иные uses дают `SKIPPED_STRUCTURAL`.
- `Memory.advance`: expire iterator должен происходить именно от `this.expire`, а require
  iterator — от `this.require.values()`. Единственный `CampaignClock.convertToDays(F)F` остаётся
  после restore/pause path и до обоих scans; expire iterator предшествует require iterator.
  Inline guard читает те же два поля, стоит после исходного pause-return и до clock conversion.
  Partial/raw-hook mixture, другой map receiver, изменённый порядок и лишние sites отвергаются.
- `CampaignEngine.setInstance/resetInstance`: существует единственное static поле типа
  `CampaignEngine`; `setInstance` записывает в него argument 0, а `resetInstance` — `null`.
  Begin-hooks с сохранением transition token должны непосредственно предшествовать подтверждённым
  singleton `PUTSTATIC`; completion-hooks с тем же token должны непосредственно предшествовать
  каждому normal `RETURN` обоих методов.
- `O0Oo`: system/anchor locals выводятся из identity comparisons с destination location и
  hyperspace anchor; номера local slots не фиксируются.
- Scratch locals принимаются только при точном наборе receiver/argument uses. Для pooled
  `H/A/Z`, label-candidate, Intel-contains и `BaseLocation` путей ставится depth-indexed reentrant
  scope с
  очисткой на каждом normal/exceptional exit; catch-all handler получает явный `F_FULL` frame
  с исходными locals.
- Два internal-read sites `HyperspaceTerrainPlugin` могут читать `HyperspaceAutomaton.cells`
  напрямую только для точного vanilla runtime owner и только когда buffer-reuse transformation
  подтверждена. Для subclass, неподтверждённого patch state или недоступного поля используется
  исходный virtual `getCells()`; такое чтение считается public escape и безопасно запрещает reuse.
- Wrapper сохраняет исходные declaration/type/parameter annotations и parameter metadata,
  переносит реализацию в private synthetic method и при повторной трансформации заново
  проверяет semantic contract оригинала и точную последовательность wrapper-инструкций.

## Ограничения

Статический анализ не может доказать намерение произвольного стороннего патча. Если target
сохраняет похожие инструкции, но меняет их смысл так, что контракт перестаёт быть однозначным,
соответствующий блок должен дать `SKIPPED_STRUCTURAL`. Hook-и дополнительно сохраняют vanilla
fallback на отключённой настройке, cache miss и runtime error.

Route indexes намеренно имеют TTL: size/first/last проверяются на каждом hit, полный identity/
relationship snapshot — раз в `route.indexTtlMs`. Это сохраняет быстрый hit path и ограничивает
видимость прямой мутации сторонним модом. Значение TTL `0` отключает runtime index; в
`profiles/safe.properties` route patch отключён полностью.

Comm-relay index намеренно использует такой же bounded-staleness контракт. Size/radius и identity
первой/последней системы проверяются на каждом запросе, а полный ordered identity/coordinate audit
выполняется раз в `commRelay.indexTtlMs`. Владелец релиза явно принимает задержку до TTL для прямого
перемещения или замены средней системы сторонним модом; vanilla distance/tag/memory/tie loop над
возвращёнными candidates не меняется. Значение `0` отключает runtime index.

Hyperspace diagnostics не участвует в tile-level clock/counter hot path. Значение
`pooledRandomApprox` является накопительным и монотонным с запуска JVM: опубликованные пачки
дополняются live pending tails из weak registry активных `RandomPool`. Приблизительность остаётся
из-за concurrent snapshot и weak lifecycle pool, но незаполненный хвост учитывается, пока pool жив.
Automaton counters также накопительные; поэтому редкие rollover/reuse остаются видимыми после
последующих интервалов логирования.

Telemetry schema `0.7.1`: старый `pooledRandom` называется `pooledRandomApprox`, добавлен
`automatonInternalReads`, а статические `cullHeight`/`yClamp` counters удалены из runtime stats.
Анализатор старых и новых сессий должен различать schema: отсутствие удалённого поля не означает
нулевой activity или structural skip.

Structural proof показывает однозначность site, linkage, no-escape и verifier postconditions, но
не доказывает величину ускорения. Runtime и performance evidence создаётся в `.build/reports/`, а
проверенные выводы сохраняются в отчёте выпуска, например
[`releases/0.8.0.md`](releases/0.8.0.md).

Если несколько javaagent меняют одни и те же классы, располагайте Prepatcher после них в
`vmparams`: transformer увидит bytes, возвращённые ранее зарегистрированными агентами.

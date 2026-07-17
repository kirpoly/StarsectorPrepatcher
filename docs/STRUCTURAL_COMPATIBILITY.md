# Структурная совместимость

Начиная с `0.4.0-exp4`, оптимизатор не сравнивает SHA-256 игры, JAR или target-классов.
Каждый `patch.*` независимо проверяет локальный контракт фактически загружаемого JVM bytecode.

## Жизненный цикл патча

1. Найти ровно один целевой метод по имени и descriptor.
2. Найти ожидаемые symbolic call/field/allocation sites.
3. Через ASM `Analyzer<SourceValue>` проверить происхождение receiver, аргументов и local values.
4. Отличить три состояния: исходный код, уже установленный этим агентом патч и
   конфликт/неоднозначность. Владение подтверждается отдельным private synthetic marker,
   а не только похожим вызовом hook.
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

В `logs/map-optimizer.log` для каждого загруженного target выводится один из статусов:

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
- `BaseCampaignEntity.runScripts`: требуется ровно один `new ArrayList(Collection)`, сразу
  записанный в local и использованный ровно одним `List.iterator()`; любые другие uses дают
  `SKIPPED_STRUCTURAL`.
- `Memory.advance`: один `List.iterator()` должен происходить от instance list field, а один
  `Collection.iterator()` — от `LinkedHashMap.values()` другого instance field. Единственный
  `CampaignClock.convertToDays(F)F` сохраняется до обоих scans; expire iterator должен
  предшествовать require iterator. Partial/raw-hook mixture и лишние sites отвергаются.
- `CampaignEngine.setInstance/resetInstance`: существует единственное static поле типа
  `CampaignEngine`; `setInstance` записывает в него argument 0, а `resetInstance` — `null`.
  Begin-hooks с сохранением transition token должны непосредственно предшествовать подтверждённым
  singleton `PUTSTATIC`; completion-hooks с тем же token должны непосредственно предшествовать
  каждому normal `RETURN` обоих методов.
- `O0Oo`: system/anchor locals выводятся из identity comparisons с destination location и
  hyperspace anchor; номера local slots не фиксируются.
- Scratch locals принимаются только при точном наборе receiver/argument uses. Для pooled
  `H/A/Z`, label-candidate, Intel-contains, `BaseLocation` и `BaseCampaignEntity` путей ставится
  depth-indexed reentrant scope с
  очисткой на каждом normal/exceptional exit; catch-all handler получает явный `F_FULL` frame
  с исходными locals.
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

Три exp6-блока выбраны по profiler/JFR-телеметрии, но structural proof показывает только
однозначность site, linkage, no-escape и verifier postconditions. Он не доказывает величину
снижения allocations или frame time; для этого нужен повторный A/B-прогон одинакового campaign
сценария с новыми toggles off/on.

Если несколько javaagent меняют одни и те же классы, располагайте Map Optimizer после них в
`vmparams`: transformer увидит bytes, возвращённые ранее зарегистрированными агентами.

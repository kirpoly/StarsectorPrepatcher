# Starsector Map Optimizer 0.4.0-exp6

Runtime-оптимизации sector/hyperspace/Intel map, campaign bookkeeping и route candidate scans
для очень больших секторов Starsector. Основное исправление карты на реальном сохранении подняло
сцену примерно с **10 FPS** до установленного предела **60 FPS**.

Мод состоит из sandbox-safe bootstrap-плагина и startup `javaagent`. Javaagent обязателен: обычный
script classloader Starsector не может безопасно менять engine bytecode до загрузки классов.

## Совместимость без SHA allowlist

Начиная с exp4 мод не содержит проверок SHA игры или target-классов. Вместо решения «подходит ли весь JAR»
каждый патч отдельно:

1. находит symbolic bytecode site;
2. проверяет receiver, arguments и local values через ASM data-flow;
3. требует однозначное ожидаемое число sites;
4. применяет изменение транзакционно;
5. ставит private synthetic ownership marker для безопасной идемпотентности;
6. проверяет точные hook descriptors, postconditions, public API и весь полученный класс через
   `BasicVerifier`.

Переводы строк, изменения несвязанных методов и другие безопасные модификации core JAR не блокируют
подходящие патчи. Неизвестный или неоднозначный site получает `SKIPPED_STRUCTURAL`, при этом остальные
патчи того же класса продолжают устанавливаться. Подробные контракты описаны в
[`docs/STRUCTURAL_COMPATIBILITY.md`](docs/STRUCTURAL_COMPATIBILITY.md).

## Установка

1. Полностью закройте игру.
2. Поместите каталог в `<Starsector>\mods\StarsectorMapOptimizer`.
3. Запустите `install-agent.bat` из каталога мода.
4. Включите мод в launcher и запустите Starsector.

Installer создаёт резервную копию `vmparams` и добавляет:

```text
-javaagent:../mods/StarsectorMapOptimizer/agent/StarsectorMapOptimizerAgent.jar
```

Telemetry javaagent можно оставить. Installer автоматически добавляет Map Optimizer после всех
существующих `-javaagent`, чтобы структурный matcher видел уже изменённые ими bytes. Повторный
запуск installer исправляет порядок старой установки, если Map Optimizer стоит раньше другого агента.

## Что исправляется

### Sector/hyperspace/Intel map

Главный per-frame дефект:

```java
icons.keySet().retainAll(entityArrayList);
```

заменяется линейной reconciliation. Vanilla entities используют reusable identity membership;
для modded entity с собственными `equals/hashCode` автоматически используется reusable `HashSet`,
сохраняющий Java collection semantics. Сложность меняется с `O(K × E)` на `O(K + E)`.

Дополнительные блоки уменьшают:

- временные `ArrayList`, `HashSet` и `Vector2f` allocations;
- полный scan иконок при label layout и Intel reconciliation;
- повторные `IntelInfoPlugin.getMapLocation/getArrowData` callbacks;
- hover hit-tests и all-system hyperspace scans;
- подготовку synthetic system-nebula entities;
- повторные sample-cache clears;
- количество grid lines в огромных секторах.

### Жизненный цикл campaign-кэшей

Начиная с exp5 все долгоживущие кэши привязаны к identity текущего `CampaignEngine`. Входы
`CampaignEngine.setInstance()` и `resetInstance()` служат точной границей поколения: при загрузке,
замене или выгрузке кампании очищаются Intel/label/hover/nebula/listener/route-кэши и текущий
`ThreadLocal` scratch. Следующая кампания лениво перестраивает их при обычных обращениях, поэтому
набор и поведение оптимизаций не меняются.

Сам active engine хранится только через `WeakReference`. Между `resetInstance()` и следующим
успешным завершением `setInstance()` stateful-кэши не заполняются: begin-hook закрывает поколение
непосредственно перед singleton-записью, а completion-hook активирует новое только после обновления
`Global`/factory и остальной штатной логики метода. Если lifecycle-site на другой сборке игры нельзя
структурно подтвердить, эти кэши используют точный vanilla fallback вместо сохранения объектов
неизвестного жизненного цикла. Каждая публикация в process-lifetime кэш дополнительно сверяет
номер поколения под блокировкой: начатый до смены кампании вызов не может вернуть старый объект
в уже очищенную static-структуру. При смене поколения volatile-ссылки на все восемь map-кэшей и
отдельный nebula-slot заменяются новыми пустыми roots без ожидания их monitors; старые структуры
больше не являются static-roots и освобождаются после завершения уже выполнявшихся вызовов.

### Telemetry-driven campaign allocations (exp6)

Отдельная profiler/JFR-сессия в огромном modded-секторе показала оставшийся allocation churn в
vanilla campaign advance: defensive snapshots `BaseLocation`, snapshots script list у
`BaseCampaignEntity` и iterator-объекты `Memory.advance()`. Exp6 добавляет три независимых,
включённых по умолчанию блока:

- `patch.campaignSnapshotReuse` повторно использует backing storage пяти point-in-time snapshots
  в `BaseLocation.advance()/advanceEvenIfPaused()`;
- `patch.entityScriptSnapshotReuse` делает то же для одного snapshot в
  `BaseCampaignEntity.runScripts()`, а пустой список scripts обрабатывает без новой allocation;
- `patch.emptyMemoryAdvanceFastPath` возвращает общий empty iterator только для пустых
  expire/require containers; непустой путь вызывает исходный iterator.

Snapshot создаётся заново по содержимому при каждом вызове. Transformer принимает только sites,
где результат используется как `List` исключительно для iteration, без mutation или escape.
Reusable storage живёт в reentrant scratch scope; ссылки на элементы обнуляются на каждом normal и
exceptional exit. Поэтому эти три блока не являются cross-campaign кэшами и не должны удерживать
entity, location или `CampaignEngine` после завершения вызова.

Эта телеметрия обосновывает выбор targets, но ещё не измеряет эффект exp6. Численное утверждение об
уменьшении allocations или frame time требует повторного контролируемого A/B-прогона на том же
сохранении и сценарии.

### Campaign при закрытой карте

Vanilla `CampaignEngine.advance()` каждый кадр вызывает `readdChangeListeners()` и обходит hyperspace
и все star systems. Патч меняет только внутренний call site:

- первый вызов выполняется полностью;
- изменение backing systems/hyperspace вызывает немедленный refresh;
- периодический audit покрывает необычные прямые мутации модами;
- публичный `readdChangeListeners()` остаётся оригинальным.

### Route/pathfinding

`coreui.A.O0Oo` заменяет три полных hyperspace jump-point scans и один all-system anchor scan
короткоживущими identity indexes. Оригинальный bytecode продолжает выполнять wormhole/star filters,
distance, tolerance и tie-break. Cache miss, malformed/custom data или runtime error возвращают
полный vanilla candidate list. Полный identity/relationship snapshot перепроверяется раз в TTL;
консервативный профиль отключает этот патч.

## Совместимость и безопасность

- Публичные Starsector API и формат сохранений не меняются.
- Wrapper-патчи сохраняют оригинальные методы как private synthetic fallback.
- Каждый блок отключается независимо в `optimizer.properties`.
- Повторная трансформация распознаётся как `ALREADY_APPLIED` и ничего не дублирует.
- Любая структурная неоднозначность пропускает только затронутый блок.
- Кэши transient, очищаются на каждой смене `CampaignEngine` и не удерживают старую кампанию.
- Exp6 snapshot scratch очищает campaign-object ссылки на каждом normal/exceptional exit.

## Проверка запуска

Основной лог:

```text
mods\StarsectorMapOptimizer\logs\map-optimizer.log
```

Для каждого загруженного target ожидаются строки `APPLIED`, `ALREADY_APPLIED`,
`SKIPPED_STRUCTURAL` или `SKIPPED_ERROR`, затем сводная строка класса. UI-классы загружаются лениво,
поэтому их строки появляются после первого открытия соответствующего экрана.

Постоянный in-memory regression harness:

```powershell
.\verify-structural.ps1 -CoreJars `
  'C:\Games\Starsector\starsector-core\starfarer_obf.jar', `
  'C:\Games\Starsector_test\starsector-core\starfarer_obf.jar'
```

Harness не записывает изменённые game classes: он читает JAR, трансформирует bytes в памяти,
проверяет наличие каждого hook descriptor в runtime JAR, все concrete methods, scratch exception
frames, идемпотентность, ownership/metadata wrapper и отрицательные missing/ambiguous cases.

Критерии доказательности и воспроизводимые сценарии для каждого блока собраны в
[`docs/PATCH_VALIDATION_CHECKLIST.md`](docs/PATCH_VALIDATION_CHECKLIST.md). Успешный `APPLIED`
подтверждает структурную совместимость, но утверждение о корректности или ускорении дополнительно
требует behavior-проверки и одинаковой A/B-телеметрии.

## Откат

Запустите `uninstall-agent.bat`, отключите мод и перезапустите игру. Удаление безопасно для сохранений.

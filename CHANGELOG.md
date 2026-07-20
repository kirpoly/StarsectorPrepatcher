# Журнал изменений

Здесь фиксируются заметные пользовательские изменения StarsectorPrepatcher. Формат основан на
[Keep a Changelog](https://keepachangelog.com/ru/1.1.0/), версии следуют
[Semantic Versioning](https://semver.org/lang/ru/).

Публичная версия всегда имеет вид `X.Y.Z` без названий веток и суффиксов `exp*`/`integrated`:

- `X` меняется при несовместимом изменении;
- `Y` — при добавлении совместимой функциональности;
- `Z` — при совместимом исправлении ошибки или документации.

До `1.0.0` проект остаётся экспериментальным, но придерживается этой схемы настолько строго,
насколько позволяет модификация внутренних классов Starsector. История экспериментальных веток
остаётся в истории Git и не дублируется в поставке.

## [Unreleased]

### Объединено

- FastForward Presentation Patch `1.1.0` интегрирован как exact-build transformer внутри единого
  `StarsectorPrepatcherAgent.jar`. Его hooks входят в общий target/game-loader runtime payload;
  отдельный FFP-agent, вторая `-javaagent` запись и дублирующая startup-инфраструктура не нужны.
- Presentation transformer регистрируется перед structural transformer Prepatcher, чтобы проверять
  исходные bytes каждого target, сохраняя общий classloader guard для vanilla и Faster Rendering.

### Добавлено

- Master/frame marker и отдельные переключатели fast-forward coalescing для action/location/floating
  text, fleet, sensor, celestial/aurora, continuous sound и gate presentation. Simulation продолжает
  выполняться на каждом substep; подтверждённые presentation calls выполняются один раз на финальном
  substep outer frame.
- Опции `fastForward.visualTime`, `fastForward.guardJar`, `fastForward.verbose` и
  `fastForward.metrics`. Режим `realtime` оставляет один обычный visual-time шаг, а opt-in
  `simulation` накапливает время substeps и может давать заметные нелинейные скачки.
- Global animations, sensor faders, slipstream particles и particle emitters выделены в aggressive
  opt-in groups из-за возможных изменений callback/lifetime, RNG и emission cadence. Default/safe
  profiles оставляют эти четыре группы выключенными.

### Совместимость

- Presentation targets поддерживаются только для текущих exact SHA-256 containers:
  `starfarer_obf.jar` — `5dd222b9e266d2ac2d63b3dad4983eb05caaf5a247d7dfb82aaeba47ea774cc8`,
  `starfarer.api.jar` — `a7ba18f3476ffe704729bd0a7a47443f035fea98a32ac2930eae8b391d013c2a`,
  и зафиксированных whole-class hashes. Переводные, перепакованные и другие game builds этим
  exact-hash блоком не объявляются совместимыми.
- Несовпадение target/container hash оставляет original bytes со статусом
  `SKIPPED_CLASS_HASH`/`SKIPPED_CONTAINER_HASH`. Отключение `fastForward.guardJar` снимает только
  container guard; обязательный exact class hash сохраняется.
- Уже загруженный presentation-only target получает локальный `SKIPPED_ALREADY_LOADED` и больше не
  отменяет установку прежних structural patches. Если до premain уже загружен обязательный
  `CampaignState` frame marker, выключается только presentation transformer.
- Faster Rendering с пустым `CodeSource` проверяется через exact defining-loader JAR resource.
  Классы, которые сам FR изменил до Instrumentation (в текущем `fr.jar` это, в частности,
  `HyperspaceTerrainPlugin`), по-прежнему безопасно получают `SKIPPED_CLASS_HASH`; их независимые
  structural patches продолжают применяться.

## [0.9.5] - 2026-07-18

### Объединено

- Параллельная ветка no-op market callbacks объединена с `0.9.4` в один unified transformer и один
  startup-javaagent. Полная замена `Market.advance()` и отдельный helper-agent не используются.
- Полезная industry-идея переработана в прямой dormant fast path внутри exact
  `BaseIndustry.advance(float)`: исходное тело сохранено как private synthetic raw method, а
  per-instance state хранится в двух private transient synthetic полях.

### Изменено

- После полного vanilla-вызова inherited `BaseIndustry.advance()` может пропускаться, пока
  `building=false`, `wasDisrupted=false` и не наступил `market.noOpIndustryAuditFrames`.
- `BaseIndustry.setDisrupted(float, boolean)` немедленно сбрасывает dormant state до выполнения
  исходного метода. Custom `advance`, `isDisrupted` и `getDisruptedKey` остаются на полном vanilla
  cadence.
- Пустые inherited `BaseMarketConditionPlugin.advance()` намеренно не перехватываются: отдельный
  classifying helper оказался дороже самого пустого virtual callback.

### Совместимость

- Публичный API и save graph не изменены; synthetic state transient. На fast path отсутствуют
  reflection, global `IdentityHashMap`, helper-call, allocation и per-call telemetry counter.
- Мод, который меняет private disruption memory в обход `setDisrupted()`, может быть замечен только
  на bounded audit. Это осознанная aggressive-семантика; safe profile выключает патч.
- Structural mismatch, partial prior patch или неизвестный override contract оставляют
  `BaseIndustry` vanilla без отключения других блоков.

### Проверено

- Structural retained-body/postcondition и `setDisrupted` wake-prologue.
- Actual-javaagent smoke с dormant skip, periodic audit, immediate disruption wake и custom override
  fallbacks; building guard проверяется structural postcondition.
- Runtime config/classification suite, API snapshot, ASM `BasicVerifier`, lifecycle/save/hyperspace и
  startup regressions.

Подробности: [отчёт о выпуске 0.9.5](docs/releases/0.9.5.md).

## [0.9.4] - 2026-07-18

### Объединено

- В unified transformer интегрирована параллельная ветка commodity temporal active set. Она
  работает поверх одновременно интегрированного direct `MutableStatWithTempMods` hybrid и не устанавливает второй
  javaagent и не заменяет игровой класс целиком.
- `Market.advance()` теперь хранит private transient ordered state и не выполняет четыре
  `MutableStatWithTempMods.advance()` плюс `CommodityOnMarket.reapplyEventMod()` для commodities,
  у которых нет временных modifiers и не было изменений relevant stats.

### Добавлено

- `patch.commodityTemporalFastPath` с bounded audit `commodity.temporalAuditFrames`.
- Private transient owner/role binding в точном vanilla `MutableStat`: обычные `modify*`,
  `unmodify*`, `setBaseValue` и temporary-mod mutators немедленно будят owning commodity.
- Private audit bridge к direct expiry scheduler: periodic market audit материализует или
  перестраивает nearest-deadline state и восстанавливает работу после прямых map-изменений,
  обнаруживаемых без штатного mutator.
- Первый audit deadline разнесён между markets; high-volume telemetry обновляется выборочно раз в
  64 market calls, чтобы статистика не стала новым economy hot path.
- Active set автоматически устанавливает direct temp-mod scheduler как зависимость даже при
  случайно выключенном отдельном `patch.tempModExpiryScheduler`.
- Actual-javaagent smoke для совместной загрузки `Market`, `CommodityOnMarket`, `MutableStat` и
  `MutableStatWithTempMods`, включая stable skip, dirty wake-up, exact expiry и retained-map audit.

### Исправлено

- Переписанный `CommodityOnMarket.reapplyEventMod()` теперь содержит корректные stack-map frames.
  Ранее offline `BasicVerifier` принимал method, но фактическая JVM могла выдать `VerifyError` при
  первом определении класса; новый actual-agent smoke закрывает этот пробел.
- XStream test дополнительно проверяет, что commodity owner/role bindings не попадают в save и не
  восстанавливаются после load.

### Совместимость

- Порядок live commodities, точный `ArrayList`/`LinkedHashMap` behavior и исходные методы
  `reapplyEventMod()`/`MutableStatWithTempMods.advance()` сохранены; subclasses, shared stats,
  неизвестные backing maps и helper failures используют vanilla fallback.
- Обычные API-mutations видны следующему `Market.advance()` немедленно. Мод, сохраняющий live map
  из `getMods()` и меняющий её позже в обход API, может быть обнаружен только на bounded audit;
  это намеренный aggressive-profile компромисс.
- Safe profile оставляет active set выключенным. Формат сохранений и публичный API не изменены.

### Проверено

- Structural/negative/idempotency suite: 30 transformed classes, 1886 verified concrete methods.
- Actual-javaagent commodity smoke, direct temp-mod differential/float fixtures, XStream save/load,
  persistent economy, lifecycle/GC, loading/save, hyperspace и startup suites.

Подробности: [отчёт о выпуске 0.9.4](docs/releases/0.9.4.md).

## [0.9.3] - 2026-07-18

### Добавлено

- `patch.planetConditionMarketScheduler` покрывает отдельный vanilla-путь
  `BaseCampaignEntity.advance() -> MarketAPI.advance()` для `planetConditionMarketOnly` markets.
- Добавлены stable phases, exact accumulated `amount`, first-tick/current-location full-rate,
  memory opt-out, frame/day caps, lifecycle reset, reentrant fallback и pre-save flush для нового
  scheduler-а.
- `summary.csv` observer-а получил отдельные scheduled/fallback/save-flush/immediate counters
  planet-condition пути.
- `session.json` schema 2 содержит `sessionOrigin`; startup/FR smoke создают явно помеченные каталоги.

### Исправлено

- Массовый известный `planetConditionMarketOnly` engine path больше не учитывается как
  `unknown Market.advance()`.
- `call-sites.csv` теперь получает manifest трансформированных mod call sites до первого
  выполнения, поэтому пустой runtime не выглядит как отсутствие найденных sites.
- Лимит unknown stack samples обновляется каждый report interval; ранний частый caller больше не
  исчерпывает его на весь процесс.
- Если central scheduler выключен, planet scheduler всё равно получает frame-clock boundary, не
  изменяя central market-loop.

### Проверено

- Добавлены structural negative/idempotency tests точного BaseCampaignEntity bridge.
- Runtime suite покрывает stable cadence, current-location policy, opt-out, separate identity state,
  save flush, reentrancy, disabled fallback, eager observer manifest и interval stack budget.
- Lifecycle/GC suite проверяет точный inventory из 12 campaign-bound maps.

Подробности: [отчёт о выпуске 0.9.3](docs/releases/0.9.3.md).

## [0.9.2] - 2026-07-18

### Добавлено

- `patch.remoteMarketScheduler` перенаправляет только центральный market-loop
  `Economy.advance()` в staggered scheduler. Удалённые рынки получают накопленный исходный
  `amount` на стабильных фазах; скрытые рынки имеют отдельный более редкий cadence.
- Перед сохранением pending market time принудительно применяется, а transient scheduler state
  остаётся вне save graph.
- Добавлены full-rate политики для текущей location, interaction market, player-owned markets и
  per-market memory opt-out `$starsectorPrepatcher_fullRateMarket`.
- Добавлены runtime counters и differential regression suite для cadence, amount conservation,
  hidden markets, hot policies, save flush, identity-keyed state и reentrancy fallback.
- `patch.directMarketObservation` инструментирует прямые вызовы
  `MarketAPI.advance(float)` в загружаемом bytecode модов, не откладывая, не объединяя и не
  подавляя исходный синхронный вызов.
- Для каждого call site записываются JAR/source, class, method, descriptor, line, ordinal и
  best-effort классификация источника `amount`.
- Добавлены выборочное измерение inclusive времени, sampled stack signatures, приблизительное
  число затронутых рынков, recursive/error/amount counters и отдельный учёт неизвестных
  reflective/неинструментированных входов в vanilla `Market.advance()`.
- Каждая игровая сессия получает отдельный каталог
  `logs/direct-market-observe/session-*` с `call-sites.csv`, `observations.csv`, `stacks.csv`,
  `unknown-stacks.csv`, `summary.csv` и `session.json`.
- Добавлены structural и runtime regression tests, подтверждающие исходную multiplicity,
  delivered amount, exception propagation, mod-loader transformation и CSV output.

### Изменено

- Default/aggressive profile теперь сознательно выбирает performance-first cadence для удалённых
  рынков: обычные рынки обновляются примерно раз в 4 кадра, скрытые — раз в 8 кадров, с
  ограничением накопленного времени и числа отложенных вызовов.
- Scheduler state переведён на campaign-lifetime identity map: vanilla `Market` переопределяет
  `equals/hashCode`, поэтому equality-keyed map не подходит для независимого состояния рынков.
- `Economy.advance()` всегда получает reentrant scratch scope; nested вызов экономики полностью
  уходит в immediate vanilla path и не перезаписывает контекст внешнего прохода.

### Поведение

- Частота `MarketAPI.advance()` для удалённых рынков намеренно уменьшается. Суммарное игровое время
  сохраняется, но RNG sequence, точный межрыночный порядок наблюдений и frame-count callbacks
  модовых conditions/industries/submarkets могут измениться.
- Observation mode не является scheduler: инструментированные прямые вызовы модов выполняются
  немедленно в текущем потоке, а код после `advance()` видит уже обновлённый рынок как прежде.
- Измерение времени выполняется выборочно (`1/128` по умолчанию), stack capture — на первом и затем
  редких вызовах (`1/2048`), чтобы наблюдатель не стал новым market bottleneck.
- `profiles/safe.properties` оставляет scheduler и observation выключенными; default/aggressive
  включает их для performance-first cadence и временного сбора данных.

### Исправлено

- Pending time не теряется на обычной границе save: save manager вызывает flush до сериализации.
- Custom/vanilla market equality больше не объединяет scheduler state разных экземпляров.

### Документация

- Описаны настройки агрессивного scheduler, opt-out memory key, ожидаемые изменения поведения
  модов и обязательные validation-сценарии.

Подробности: [отчёт о выпуске 0.9.2](docs/releases/0.9.2.md).

## [0.9.1] - 2026-07-18

### Добавлено

- `patch.commodityEventModDirtyCache` устраняет повторный `unmodifyFlat("eMod")`, когда
  `CommodityOnMarket` уже подтвердил нулевое recent-trade quantity и отсутствие модификатора.
  Первый zero-вызов после создания/загрузки и весь nonzero-путь остаются vanilla; transient-флаг
  не меняет cadence market/condition/industry callbacks.

Подробности: [отчёт о выпуске 0.9.1](docs/releases/0.9.1.md).

## [0.9.0] - 2026-07-18

### Добавлено

- Installer получил явный `-Target Vanilla|FasterRendering|Both`: он поддерживает как root
  `vmparams`, так и `starsector-core/fr.vmparams`, сохраняет отдельные timestamped backups и
  идемпотентно размещает Prepatcher последним `-javaagent`.
- Добавлены loader regression gates для vanilla и FR-like topology, wrong-loader fail-open и
  parent-loaded `sound.Sound`.

### Изменено

- Typed hooks перенесены в payload exact-пакета `com.fs.starfarer.api`. Startup control plane читает
  их classfile'ы из agent JAR и определяет через lookup loader'а Starsector до регистрации
  transformer.
- `sound.Sound`, который Faster Rendering оставляет в parent loader, подавляет сопоставленный
  pure-INFO блок inline без cross-loader helper call.

### Исправлено

- Устранён `loader constraint violation` при вызове typed hooks с Faster Rendering: transformer
  теперь требует identity-equal target/runtime loader и fail-open публикует `SKIPPED_LOADER` при
  несовпадении.

### Документация

- Добавлен технический roadmap: обязательный name-independent structural discovery до загрузки
  классов, декомпозиция patch/runtime-кода, formatter, Linux Tier 1 и macOS best effort.
- Описаны classloader topology Faster Rendering, остаточная зависимость payload от FR fallback в
  `JavaAgentLoader`, installer targets и обязательная real-launch матрица до выпуска.

Подробности: [отчёт о выпуске 0.9.0](docs/releases/0.9.0.md).

## [0.8.0] - 2026-07-17

### Добавлено

- Основной английский `README.md` и дополнительный `README_RU.md` с переключателем языка в шапке.
- Зафиксировано направление будущего API для возможностей, отсутствующих в публичном API игры;
  планируемый namespace — `com.starsector.prepatcher.api`.

### Изменено

- Проект переименован из **Starsector Map Optimizer** в **StarsectorPrepatcher**, чтобы название
  отражало раннее применение патчей до обычной загрузки игровых и модовых классов.
- Mod id изменён с `starsector_map_optimizer` на `starsector_prepatcher`; каталог поставки теперь
  называется `StarsectorPrepatcher`.
- Java namespace переведён с `com.starsector.mapoptimizer` на `com.starsector.prepatcher` до
  публикации первого поддерживаемого API.
- Переименованы agent/bootstrap JAR, конфигурация, runtime-лог, system properties и
  ownership marker структурных патчей.
- Все патчи объединены в одном `StarsectorPrepatcherAgent.jar`, одном transformer и одном
  `prepatcher.properties`; второй javaagent и дублирующая startup-инфраструктура удалены.
- Hyperspace-патчи переведены с allowlist полных hash классов на независимые локальные structural
  contracts. Совместимые переводные classfile теперь поддерживаются без отдельных hash-снимков.

### Поведение

- Формат сохранений не меняется, runtime-кэши не сериализуются.
- Состав и поведение оптимизационных патчей `0.7.1` не урезаны.

Подробности: [отчёт о выпуске 0.8.0](docs/releases/0.8.0.md).

## [0.7.1] - 2026-07-17

### Исправлено

- `BaseCampaignEntity.runScripts()` больше не открывает и не очищает общий scratch scope для
  сущностей с пустым списком scripts; empty path возвращает до любой optimizer-аллокации, а
  непустой путь сохраняет свежий vanilla snapshot.
- Helper-based `Memory.advance()` iterator patch заменён inline fast return для полностью пустых
  expire/require queues; устранён измеренный CPU-overhead `memoryExpireIterator()`.
- Hyperspace automaton теперь отличает два engine-internal чтения `cells` от публичной выдачи
  массива через `getCells()`; direct read разрешён только exact vanilla owner с подтверждённым
  reuse patch, а subclass/unconfirmed path сохраняет virtual getter semantics.
- Comm-relay index больше не перепроверяет identity и координаты всех систем на каждом frame;
  полный audit выполняется по TTL, а live relay/tag/memory checks остаются vanilla.
- Terrain `Random` telemetry переведена с `LongAdder` и clock-check на каждом tile на локальное
  пакетирование; накопительная приблизительная статистика больше не находится в многомиллионном
  hot path и сохраняет редкие rollover/reuse events между интервалами логирования.
- Hyperspace telemetry schema теперь cumulative: `pooledRandom` переименован в
  `pooledRandomApprox`, добавлен `automatonInternalReads`, а неинформативные runtime counters
  `cullHeight`/`yClamp` удалены.
- `ScratchFrame.clear()` обходит только реально заимствованные snapshots/lists/sets и не очищает
  исторически созданные, но не использованные в текущем scope контейнеры.
- `patch.loadingTextReader` и `patch.startupLogAggregation` отключены во всех поставляемых профилях
  как known-disabled после подтверждённых ошибок запуска миссий; реализация сохранена для
  отдельного исправления и изолированной повторной проверки.

### Проверено

- Structural/negative/idempotency suite обновлён для inline guards без runtime hooks.
- Runtime comm-relay test теперь проверяет bounded coordinate audit вместо ошибочного требования
  полного O(N)-сканирования внутри TTL.
- Hyperspace verifier проверяет internal-read reuse, public-alias isolation и zeroing reusable buffer.

Подробности и performance-контекст: [отчёт о выпуске 0.7.1](docs/releases/0.7.1.md).

## [0.7.0] - 2026-07-17

### Добавлено

- Оптимизации economy snapshots/location state, hyperspace comm-relay index, combat scratch и
  particle cleanup.
- Streaming loading/save paths, агрегация startup-логов и ограничение частоты progress redraw.
- Отдельный hyperspace javaagent для terrain culling, layer selection, automaton buffers и
  движущегося starfield.
- Единые structural, lifecycle/GC, runtime и offline hyperspace regression suites.
- Обязательный playbook повторяющихся lifecycle, weak-reference, reentrancy, aliasing, TTL,
  verifier и toolchain-регрессий.

### Изменено

- В одну поставку объединены map/campaign/route, economy/combat, loading/save и hyperspace ветки.
- Main agent применяет независимый structural/data-flow matching; hyperspace agent использует
  точные per-class guards вместо привязки к несвязанному содержимому всего JAR.
- Verifier требует полного покрытия target-классов и повторно проверяет сериализованный bytecode.
- Ownership marker теперь использует стабильную версию patch schema, не связанную с SemVer релиза.
- Документация сведена к актуальным справочникам; сырые verifier-логи находятся только в
  `.build/reports`.

### Исправлено

- Campaign caches, Economy state и reentrant `ThreadLocal` scratch больше не удерживают старые
  экземпляры `CampaignEngine`.
- Comm-relay candidates немедленно проверяют актуальные позиции систем; повтор неудачной сборки
  индекса ограничен TTL и не удерживает campaign objects.
- Сохранена vanilla alias/allocation-семантика hyperspace automaton: owner-local transient state,
  zeroed buffers и vanilla fallback для subclass owners.
- Частично применённый или повреждённый transformer patch больше не принимается за полный.
- Исправлены PowerShell native-exit handling и явная UTF-8 сборка/генерация отчётов.

Подробности и исходный baseline: [отчёт о выпуске 0.7.0](docs/releases/0.7.0.md).

## [0.4.0] - 2026-07-17

### Добавлено

- Symbolic/data-flow matching, ownership markers, postconditions, `BasicVerifier` и
  negative/idempotency tests для независимых патчей main agent.
- Campaign-generation lifecycle reset, reusable location/entity snapshots, empty `Memory` iterator
  fast path и lifecycle/GC runtime harness.

### Изменено

- Main-agent SHA gates заменены fail-open структурной проверкой каждого патча.
- Усилены ordered route indexes и conservative fallbacks.

### Исправлено

- Долгоживущие campaign caches отсоединяются при замене или reset `CampaignEngine`.
- Убрана очистка уже пустой `IdentityHashMap` на каждом entity-script scope exit, вызвавшая
  CPU-регрессию экспериментальной итерации.

## [0.3.0] - 2026-07-16

### Добавлено

- Первая поставка startup javaagent и bootstrap для Starsector `0.98a-RC8`.
- Оптимизации sector/Intel map reconciliation, scratch allocations, spatial lookup, hover,
  callbacks, nebula sampling и grid rendering.
- Campaign listener throttling, ordered route indexes, installer/uninstaller, профили, build scripts
  и первичные отчёты проверки.

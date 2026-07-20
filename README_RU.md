# StarsectorPrepatcher

[English](README.md) | [Русский](README_RU.md)

Текущая версия: **0.9.5**. Поддерживаемая версия игры: **Starsector 0.98a-RC8**.

[![Без препатчера и с ним](media/smoothness_comparison.gif)](https://github.com/kirpoly/StarsectorPrepatcher/releases/download/v0.8.0/StarsectorPrepatcher-0.8.0-comparison.webm)

Нажмите на превью, чтобы открыть полное сравнение в WebM при 60 FPS.

StarsectorPrepatcher — compatibility-first слой ранних патчей Starsector. Startup-javaagent'ы
запускаются до обычной загрузки игровых и модовых classloader'ов, поэтому защищённые структурные
патчи применяются в момент первого появления целевых классов в JVM.

Задача проекта шире одной только оптимизации карты:

- поддерживать тщательно проверенные исправления производительности и корректности внутренних
  классов игры;
- предоставить стабильный документированный API к полезным возможностям, которых нет в публичном
  API Starsector;
- хранить зависимое от версии игры знание о bytecode внутри prepatcher, а не размножать его по
  игровым модам.

Публичный API в `0.9.5` ещё не выпущен и остаётся пунктом roadmap. Планируемый namespace —
`com.starsector.prepatcher.api`; типы станут поддерживаемым контрактом только после появления
документации и compatibility-тестов.

## Как это работает

Поставка содержит sandbox-safe bootstrap и один startup-javaagent:

```text
agent/StarsectorPrepatcherAgent.jar
```

Единый agent независимо сопоставляет и проверяет каждый патч, включая hyperspace. Большинство
патчей использует локальные structural-контракты и принимает совместимые оригинальные или
переводные game files. Интегрированный FastForward Presentation Patch `1.1.0` — намеренное
исключение: его call-site replacements требуют exact whole-class hashes и поддерживают только
текущие hash `starfarer_obf.jar` и `starfarer.api.jar`, перечисленные в
[`docs/PATCHES.md`](docs/PATCHES.md). Неизвестный, неоднозначный или изменённый target остаётся
vanilla, а причина записывается в лог.

Control-код agent и typed runtime намеренно разделены. При запуске agent читает runtime-classfile'ы
`com.fs.starfarer.api.StarsectorPrepatcher*` из собственного JAR и определяет их в classloader'е,
которому принадлежит API Starsector. Поэтому типы аргументов hooks остаются loader-identical как в
vanilla launcher, так и с custom system classloader Faster Rendering. Transformer регистрируется
только после успешной установки runtime и пропускает target, загруженный другим loader'ом.

Fast-forward presentation coalescing регистрируется внутри того же startup-agent, а его hooks входят
в общий game-loader runtime payload. Второй javaagent не устанавливается и не нужен; исходный
standalone-agent FastForward Presentation Patch не следует устанавливать одновременно с Prepatcher.

Bootstrap plugin не меняет bytecode. Он выводит состояние агентов в обычный лог игры и предупреждает,
если мод включён без startup-agent.

## Установка

1. Полностью закройте Starsector.
2. Распакуйте каталог как `<Starsector>\mods\StarsectorPrepatcher`.
3. Установите agent для используемого способа запуска (команды ниже).
4. Включите **StarsectorPrepatcher** в launcher и запустите игру.

Для обычного launcher запустите:

```bat
install-agent.bat
```

Для Faster Rendering (`starsector-core\fr.bat`) запустите:

```bat
install-agent.bat -Target FasterRendering
```

Чтобы настроить оба способа запуска, используйте `install-agent.bat -Target Both`. Installer
понимает как vanilla command line в `vmparams`, так и Java argfile Faster Rendering
`starsector-core\fr.vmparams`. Для каждого изменяемого файла он создаёт timestamped backup,
заменяет существующую запись этой установки и размещает Prepatcher после остальных `-javaagent`:

```text
-javaagent:../mods/StarsectorPrepatcher/agent/StarsectorPrepatcherAgent.jar
```

Имя каталога не должно содержать пробелы; после установки его следует оставить
`StarsectorPrepatcher`. Дополнительные `--add-exports` не нужны: agent экспортирует необходимые JDK
ASM packages через `Instrumentation.redefineModule()`.

Сначала установите telemetry и остальные agents либо повторно запустите этот installer после них.
Prepatcher должен оставаться последним `-javaagent`, чтобы его transformer видел bytes, возвращённые
ранее зарегистрированными agents.

Prepatcher не изменяет формат сохранений, а его runtime-кэши не сериализуются.

## Текущие области патчей

- sector, system и Intel map: reconciliation, spatial candidates, callbacks, hover, entity indexes,
  nebula metadata, scratch collections и grid LOD;
- campaign и economy: lifecycle-bound кэши, listener refresh, reusable snapshots, агрессивные
  staggered scheduler'ы центральных удалённых рынков и `planetConditionMarketOnly`, исправленное
  observation прямых mod-вызовов `Market.advance()`, owner-local persistent copy-on-write snapshots
  markets/conditions/industries со structure epochs и bounded audit, owner-local ReachEconomy
  fingerprint, ordered fast path неактивных commodities вместе с direct expiry-aware scheduler
  `MutableStatWithTempMods`, guarded fast path для dormant-наследников `BaseIndustry`, подавление
  повторного удаления уже отсутствующего commodity event mod, fast paths для пустых scripts/Memory
  и comm-relay candidates;
- routing: упорядоченные jump-point/system indexes с vanilla selection/fallback;
- combat и particles: внутренние scratch collections и стабильная deferred cleanup;
- fast-forward presentation: final-substep coalescing защищённых campaign visuals и continuous
  audio; широкие animation/fader/particle группы доступны только в aggressive profile;
- loading/save: literal parsing, progress redraw и исправления output path;
- hyperspace: terrain culling, layer selection, seeded random reuse, owner-local automaton buffers и
  moving-starfield cleanup.

Полный перечень переключателей и инвариантов находится в
[`docs/PATCHES.md`](docs/PATCHES.md).

## Конфигурация и откат

Все настройки находятся в `prepatcher.properties`. Пользовательские группы имеют отдельные
`patch.*` switches и требуют полного перезапуска игры. Весь prepatcher отключается так:

```properties
enabled=false
```

`patch.loadingTextReader` и `patch.startupLogAggregation` остаются отключёнными во всех поставляемых
профилях после подтверждённых ошибок запуска миссий. Они не будут включены снова до отдельного
исправления и изолированного startup/mission-прогона.

Default и safe profiles включают exact-hash master/frame marker fast-forward presentation и более
узкие visual/audio группы. Global animations, sensor faders, slipstream particles и particle
emitters остаются opt-in, поскольку могут менять callback, lifetime, RNG или emission cadence;
aggressive profile включает их. `fastForward.visualTime=realtime` оставляет presentation на одном
обычном update за outer frame, а `simulation` накапливает substep time и может давать заметные
скачки. Сама simulation продолжает выполняться на каждом substep.

`patch.remoteMarketScheduler` включён в default/aggressive profile и намеренно меняет cadence
`MarketAPI.advance()` для рынков, достигаемых через центральный цикл экономики. Текущая location,
interaction market и player-owned рынки остаются full-rate.

`patch.planetConditionMarketScheduler` независимо покрывает vanilla-путь
`BaseCampaignEntity.advance()` для `planetConditionMarketOnly` markets. Он сохраняет точный
накопленный `amount`, выполняет первый tick немедленно, оставляет location игрока full-rate и
сбрасывает pending time перед сохранением. Оба scheduler'а поддерживают memory key
`$starsectorPrepatcher_fullRateMarket=true`. Для консервативного cadence используйте
`profiles/safe.properties` или выключите нужные переключатели.

`patch.directMarketObservation` также включён в default/aggressive profile в 0.9.3. Он не
throttling-ует прямые вызовы модов: каждый вызов остаётся синхронным и немедленным. Известный
planet-condition engine path учитывается отдельно от unknown, manifest преобразованных call sites
пишется до первого выполнения, а лимит unknown stacks обновляется каждый отчётный интервал.
Каталоги validation-smoke имеют заметную метку, а `session.json` содержит `sessionOrigin`.
Результаты находятся в `logs/direct-market-observe/session-*/`; после сбора данных observer стоит
выключить, чтобы убрать sampling overhead.

Для удаления записи vanilla запустите `uninstall-agent.bat`, для FR —
`uninstall-agent.bat -Target FasterRendering`, для обоих файлов —
`uninstall-agent.bat -Target Both`. Каждый изменяемый файл предварительно сохраняется в backup.

## Диагностика и проверка

Runtime-логи:

```text
mods\StarsectorPrepatcher\logs\prepatcher.log
mods\StarsectorPrepatcher\logs\direct-market-observe\session-*\
```

Agent пишет `APPLIED`, `ALREADY_APPLIED`, `SKIPPED_STRUCTURAL`, `SKIPPED_LOADER` или
`SKIPPED_ERROR` для structural patches. Exact-build presentation targets дополнительно публикуют
`SKIPPED_CLASS_HASH` или `SKIPPED_CONTAINER_HASH`. Hyperspace targets используют ту же структурную
модель статусов, что и остальные structural targets. Каждый skip работает fail-open;
`SKIPPED_LOADER` нужно разобрать до заявления совместимости соответствующего способа запуска.

Полная проверка запускается через `verify-structural.bat` на Windows или `./verify-structural.sh` на
Linux/macOS. Suite включает документацию, structural/negative/idempotency, lifecycle/GC, runtime,
hyperspace и startup единого agent. При наличии `fr.jar` дополнительно запускается smoke с настоящим
classloader Faster Rendering. Сборка описана в [`BUILDING.md`](BUILDING.md).

## Документация

- [`README.md`](README.md) — основная английская версия;
- [`CHANGELOG.md`](CHANGELOG.md) — история публичных версий `X.Y.Z`;
- [`BUILDING.md`](BUILDING.md) — сборка и полная проверка;
- [`docs/PATCHES.md`](docs/PATCHES.md) — переключатели патчей и поведенческие инварианты;
- [`docs/COMPATIBILITY.md`](docs/COMPATIBILITY.md) — structural matching и fail-open правила;
- [`docs/VALIDATION.md`](docs/VALIDATION.md) — playbook регрессионных и performance-проверок;
- [`docs/ROADMAP.md`](docs/ROADMAP.md) — план structural discovery, архитектуры, tooling и платформ;
- [`docs/releases/0.9.5.md`](docs/releases/0.9.5.md) — подробный отчёт текущего выпуска.

Условия распространения находятся в [`LICENSE`](LICENSE).

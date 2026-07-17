# StarsectorPrepatcher

[English](README.md) | [Русский](README_RU.md)

Текущая версия: **0.8.0**. Поддерживаемая версия игры: **Starsector 0.98a-RC8**.

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

Публичный API в `0.8.0` ещё не выпущен и остаётся пунктом roadmap. Планируемый namespace —
`com.starsector.prepatcher.api`; типы станут поддерживаемым контрактом только после появления
документации и compatibility-тестов.

## Как это работает

Поставка содержит sandbox-safe bootstrap и один startup-javaagent:

```text
agent/StarsectorPrepatcherAgent.jar
```

Единый agent независимо сопоставляет и проверяет каждый патч, включая hyperspace. Цельные hash
классов не используются: проверяются файлы той установки, где находится мод — оригинальной либо
переводной. Патч применяется, пока совпадает локальный контракт bytecode. Неизвестный,
неоднозначный или частично изменённый участок остаётся vanilla, а причина записывается в лог.

Bootstrap plugin не меняет bytecode. Он выводит состояние агентов в обычный лог игры и предупреждает,
если мод включён без startup-agent.

## Установка

1. Полностью закройте Starsector.
2. Распакуйте каталог как `<Starsector>\mods\StarsectorPrepatcher`.
3. Запустите `<Starsector>\mods\StarsectorPrepatcher\install-agent.bat`.
4. Включите **StarsectorPrepatcher** в launcher и запустите игру.

Installer создаёт timestamped backup `vmparams`, заменяет существующие записи этой установки и
размещает его после остальных `-javaagent`:

```text
-javaagent:../mods/StarsectorPrepatcher/agent/StarsectorPrepatcherAgent.jar
```

Имя каталога не должно содержать пробелы; после установки его следует оставить
`StarsectorPrepatcher`. Дополнительные `--add-exports` не нужны: agent экспортирует необходимые JDK
ASM packages через `Instrumentation.redefineModule()`.

Prepatcher не изменяет формат сохранений, а его runtime-кэши не сериализуются.

## Текущие области патчей

- sector, system и Intel map: reconciliation, spatial candidates, callbacks, hover, entity indexes,
  nebula metadata, scratch collections и grid LOD;
- campaign и economy: lifecycle-bound кэши, listener refresh, reusable snapshots, fast paths для
  пустых scripts/Memory и comm-relay candidates;
- routing: упорядоченные jump-point/system indexes с vanilla selection/fallback;
- combat и particles: внутренние scratch collections и стабильная deferred cleanup;
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

Для удаления управляемой записи из `vmparams` запустите `uninstall-agent.bat`.

## Диагностика и проверка

Runtime-логи:

```text
mods\StarsectorPrepatcher\logs\prepatcher.log
```

Agent пишет `APPLIED`, `ALREADY_APPLIED`, `SKIPPED_STRUCTURAL` или `SKIPPED_ERROR` для каждого патча.
Hyperspace targets используют ту же структурную модель статусов, что и остальные targets.

Полная проверка запускается через `verify-structural.bat` на Windows или `./verify-structural.sh` на
Linux/macOS. Suite включает документацию, structural/negative/idempotency, lifecycle/GC, runtime,
hyperspace и startup единого agent. Сборка описана в [`BUILDING.md`](BUILDING.md).

## Документация

- [`README.md`](README.md) — основная английская версия;
- [`CHANGELOG.md`](CHANGELOG.md) — история публичных версий `X.Y.Z`;
- [`BUILDING.md`](BUILDING.md) — сборка и полная проверка;
- [`docs/PATCHES.md`](docs/PATCHES.md) — переключатели патчей и поведенческие инварианты;
- [`docs/COMPATIBILITY.md`](docs/COMPATIBILITY.md) — structural matching и fail-open правила;
- [`docs/VALIDATION.md`](docs/VALIDATION.md) — playbook регрессионных и performance-проверок;
- [`docs/ROADMAP.md`](docs/ROADMAP.md) — план structural discovery, архитектуры, tooling и платформ;
- [`docs/releases/0.8.0.md`](docs/releases/0.8.0.md) — подробный отчёт текущего выпуска.

Условия распространения находятся в [`LICENSE`](LICENSE).

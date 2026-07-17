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

Пока нет пользовательских изменений после `0.8.0`.

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

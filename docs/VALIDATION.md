# Проверка optimization-патчей

Это единый pre-merge playbook для патчей, которые кэшируют данные игры, переиспользуют
mutable-буферы или меняют bytecode. Его цель — ловить повторяющиеся ошибки до графического прогона
и не принимать «патч загрузился» за доказательство корректности.

## Уровни доказательств

- **S — structural:** semantic site однозначно найден; postconditions, API surface и весь класс
  проходят `BasicVerifier`.
- **N — negative:** отсутствие, дублирование или конфликт site приводит к локальному
  `SKIPPED_STRUCTURAL`; несвязанные изменения не мешают патчу.
- **R — runtime:** на реальной modded-сборке есть `APPLIED`/`ALREADY_APPLIED`, нет
  `SKIPPED_ERROR`, optimizer exception или падения игры.
- **A — activity:** счётчик или отдельный лог подтверждает выполнение оптимизированного пути.
- **B — behavior:** целевой сценарий пройден без визуальной, игровой или callback-регрессии;
  fallback и граничные случаи проверены отдельно.
- **P — performance:** записана A/B-телеметрия на одном сохранении, сцене, масштабе и настройках;
  сравниваются frame time, allocations и call counts, а не только субъективный FPS.

Заявление «патч совместим» требует как минимум S+R. Заявление «патч корректен» требует S+N+R+B.
Заявление о приросте производительности требует также A+P. Общий smoke test не заменяет
изолированный B/P-прогон патча.

## Сначала зафиксировать время жизни и ownership

Для каждой новой структуры нужно письменно указать:

- кто владеет ключом и значением;
- допустимое время жизни: вызов, frame, location, campaign или весь JVM process;
- какие campaign/entity/plugin/location объекты достижимы из ключа **и из значения**;
- событие invalidation и поведение до/во время/после reset;
- может ли ссылка уйти наружу через return, callback, public getter или сохранённый аргумент.

Javaagent-классы и их `static`/`ThreadLocal` живут до завершения JVM. Поэтому любая достижимая из
них campaign-ссылка считается process root, пока обратное не доказано GC-тестом.

## Каталог повторяющихся регрессий

| Регрессия | Типичная причина | Обязательное доказательство |
|---|---|---|
| Lifecycle root | `static`, singleton, executor task или `ThreadLocal` хранит entity/plugin/location/engine | lifecycle reset очищает все значения; старое поколение собирается через `WeakReference` + `ReferenceQueue` |
| Weak-key/value cycle | `WeakHashMap<K,V>`, но `V → ... → K` или значение держит тот же campaign graph | тест хранит только map, отпускает key и требует его GC без дополнительного cache access |
| Reentrant reset | reset удаляет `ThreadLocal`, пока старый patched frame ещё исполняется и может создать новый frame | reset вызывается внутри вложенного scope; normal и exceptional unwind оставляют scratch пустым и detached |
| Public alias semantics | pool/double buffer повторно мутирует массив, коллекцию или vector, ранее возвращённые vanilla-кодом | consumer удерживает старый return/argument; последующие frames не меняют его, если vanilla не меняла |
| Неполная idempotency | первый найденный hook даёт `ALREADY_APPLIED`, поздние sites уже не проверяются | mutation test повреждает **последний** site при сохранённом marker и требует `SKIPPED_STRUCTURAL` |
| Неверный inline fast path | guard найден по похожим `isEmpty()`/field calls, но стоит до стороннего пролога, читает другой field или обходит часть vanilla checks | data-flow связывает guard и исходную работу с теми же `this` fields; branch входит в исходную первую инструкцию или в точно заданную post-check boundary; negative tests меняют receiver, placement и порядок |
| Ложная несовместимость локализованного JAR | whole-class hash реагирует на нерелевантные строки/constant pool | structural patches используют локальные contracts на JAR текущей установки; отдельный presentation-блок намеренно exact-build-only и обязан fail-open пропустить переводной/изменённый class или container hash |
| False-green verifier | успех определяется как `patched > 0`, а `UNCHANGED`/missing target допустим | exit `0` только при точном числе targets и sites; missing/unchanged/duplicate/failed дают non-zero |
| TTL correctness | до истечения TTL не проверяется дешёвый mutable input: position, order, identity, tag | мутация сразу после build сравнивается с vanilla; явно доказано допустимое окно stale-данных либо есть live validation |
| Failed-cache storm | failed build не memoize-ится и повторяет allocation/full scan каждый вызов | malformed/custom объект многократно вызывает path; build выполняется ограниченно, fallback остаётся полным |
| Encoding/platform drift | `javac` использует системную code page; PowerShell содержит bash syntax; проверка шла на другой JDK | explicit UTF-8, parser/syntax check для каждого script, полный прогон на целевом JDK 17 и ОС |
| Loader constraint violation | typed hook и target определены sibling/child classloader'ами и имеют разные копии `com.fs.*` | payload определяется через game-loader lookup; hooks, API и targets имеют identity-equal loader; wrong-loader target получает `SKIPPED_LOADER` без изменения bytes |
| FR resolver drift | payload больше не может разрешить `PrepatcherConfig`/`PrepatcherLog` через fallback FR `AppClassLoader` → `JavaAgentLoader` | FR smoke выполняет bridge configuration и runtime logging; install failure происходит до регистрации transformer; остаточная связь явно учитывается до перехода на полностью JDK-only boundary |

## Обязательные review gates

Review не считается завершённым без следующих артефактов:

1. **Root graph:** короткая схема всех долгоживущих ссылок от agent `static`/`ThreadLocal` до игровых
   объектов. Weak key не засчитывается без анализа value graph.
2. **Generation contract:** указаны begin/reset/complete boundaries, поведение старого frame после
   смены поколения и fail-closed путь до готовности новой campaign.
3. **Alias contract:** для каждого pooled mutable объекта доказано отсутствие escape. Если объект
   возвращается public API или передаётся неизвестному callback, reuse запрещён без копии/ownership API.
4. **Whole-patch postcondition:** проверяются все sites, descriptors, counts, scope hooks и marker.
   `ALREADY_APPLIED` допустим только после полной проверки сериализованного класса.
5. **Guard contract:** перечислены все runtime inputs и причина каждого structural pattern check. Disabled
   status должен быть заметен пользователю, а не только присутствовать в отдельном debug log.
6. **Fallback contract:** malformed/custom/throwing inputs сохраняют vanilla result, order, identity,
   exception timing и не создают rebuild storm.
7. **Freshness contract:** для TTL указаны mutable inputs, максимальная допустимая задержка и дешёвые
   same-frame invalidators. Производительность не оправдывает незаявленное изменение семантики.
8. **Toolchain contract:** команды воспроизводимы из clean checkout, используют Java 17 и explicit
   UTF-8; Windows и POSIX entrypoints передают одинаковые arguments и exit codes.
9. **Loader contract:** перечислены loader control plane, payload, API и каждого target. До
   регистрации transformer весь payload определён loader'ом target; несовпадение identity даёт
   локальный `SKIPPED_LOADER`, а не потенциальный поздний `LinkageError`.
10. **Parent-loader exceptions:** target, который не видит game-loader runtime, не получает typed
    helper call. Для `sound.Sound` разрешена только structurally-verified inline-трансформация без
    runtime descriptor; новые исключения должны быть перечислены отдельно.

## Обязательные test gates

До merge должны пройти:

- positive structural transform и `BasicVerifier` для каждого target class;
- для exact-build presentation targets: positive transform только на зафиксированных class hashes,
  negative class/container hash cases с `SKIPPED_CLASS_HASH`/`SKIPPED_CONTAINER_HASH`, а также
  доказательство, что `guardJar=false` не отключает обязательный class hash;
- для structural patches: повторный transform с точным `ALREADY_APPLIED` без изменений bytes и без
  structural skip; для exact-hash presentation patches: повторный transform не добавляет hooks и
  возвращает `null`, поскольку преобразованные bytes больше не совпадают с vanilla class hash;
- mutation tests: missing, duplicate, foreign marker, broken first site и broken last site; для
  inline guards отдельно меняются receiver, entry prologue, branch target и порядок исходных
  checks при сохранённом marker;
- exact-count verifier: один missing/unchanged/duplicate target обязан завершать процесс ненулевым кодом;
- lifecycle GC: несколько load/reset generations, reentrant reset, normal/exceptional unwind и GC без
  «лечащего» обращения к cache;
- behavior parity для identity/order/duplicates/custom equality/callback exception/public aliases;
- TTL tests с mutation непосредственно после build и на boundary истечения;
- repeated malformed/failure test, подтверждающий bounded rebuild/allocation rate;
- documentation consistency: строгий `X.Y.Z` совпадает в `mod_info.json`, agent sources и
  manifests; changelog упорядочен, все актуальные документы достижимы от README, ссылки целы,
  а `SHA256SUMS.txt` полностью покрывает поставляемое дерево и совпадает с его содержимым;
- agent JAR содержит все top-level/nested entries `com/fs/starfarer/api/StarsectorPrepatcher*`, а
  control plane не имеет typed-ссылок, которые заставят agent loader определить payload;
- loader harness подтверждает vanilla и FR-like topology: payload/API/targets принадлежат одному
  system/game loader, premain control code — отдельному agent loader; wrong-loader negative case
  оставляет bytes без изменений и публикует `SKIPPED_LOADER`;
- packaged startup использует один `StarsectorPrepatcherAgent.jar`: presentation transformer
  зарегистрирован перед structural transformer, presentation hooks входят в тот же game-loader
  payload, отдельной FFP `-javaagent` записи или второго runtime нет;
- structural test `sound.Sound` подтверждает exact inline suppression, отсутствие helper
  `INVOKESTATIC` и полную postcondition/idempotency marker;
- полный `verify-structural` на целевой Java 17; PowerShell parser check и POSIX shell syntax check;
- реальный startup/activity smoke на той же Starsector-установке, для которой публикуется guard.

Численное performance-заявление делается только после этих gates и одинакового A/B-прогона. Если
какой-либо gate временно неприменим, патч остаётся experimental/disabled by default. Исключение
допустимо только по явному решению владельца релиза, при наличии отдельного kill switch и записи
причины/остаточного риска в отчёте соответствующего выпуска из [`releases/`](releases/0.9.5.md).

## Матрица запуска vanilla и Faster Rendering

Loader harness необходим, но не заменяет запуск настоящего `fr.jar`. Перед merge/release нужно
проверить одну и ту же mod-сборку и порядок agents как минимум в двух режимах:

1. Vanilla: root `vmparams`, запуск обычного launcher.
2. Faster Rendering: `starsector-core/fr.vmparams`, запуск `starsector-core/fr.bat`.

В обоих режимах выполняются cold startup, создание новой кампании до первого campaign frame,
загрузка существующего save, открытие sector/system/Intel map, переход через hyperspace и один бой.
При включённой telemetry порядок должен быть `Telemetry` → `Prepatcher`; его можно восстановить
идемпотентным вызовом `install-agent.bat -Target Vanilla` или
`install-agent.bat -Target FasterRendering`.

Обязательные loader assertions для FR:

```text
StarsectorPrepatcherHooks.class.getClassLoader()
    == StarsectorPrepatcherPresentationHooks.class.getClassLoader()
    == CampaignEngine.class.getClassLoader()
    == ClassLoader.getSystemClassLoader()
```

В `prepatcher.log` должны быть успешная установка target-loader runtime и ожидаемые patch statuses.
Smoke фиксирует `APPLIED` для exact targets из обоих containers (`CampaignEngine`, `BaseTerrain`) и
ожидаемый `SKIPPED_CLASS_HASH` для изменённого самим FR `HyperspaceTerrainPlugin`; его structural
statuses при этом остаются `APPLIED`. Любой `LinkageError`, `NoClassDefFoundError` payload/agent
types, `SKIPPED_LOADER`, неожиданный container/hash status, определение payload через agent loader
или иное незаявленное расхождение результатов vanilla/FR блокирует merge. Отдельно
сохраняются startup/mission logs: успешный главный экран не покрывает deferred hook linkage,
которое впервые происходит при генерации кампании или первом frame.

## Временно отключённые startup-патчи

`patch.loadingTextReader` и `patch.startupLogAggregation` имеют подтверждённую регрессию запуска
миссий в текущей modded-сборке. В `0.7.1` оба переключателя установлены в `false` во всех
поставляемых профилях: default, safe и aggressive. Их реализация и тестовые сценарии сохранены для
отдельной диагностики, но повторное включение в поставляемом профиле запрещено до изоляции причины,
исправления и нового startup/mission B-прогона каждого патча по отдельности.

## Exact-build gate для fast-forward presentation

Presentation coalescing — намеренное исключение из общего local-structural compatibility contract.
Заявленный positive-набор ограничен exact container SHA-256:

```text
starfarer_obf.jar  5dd222b9e266d2ac2d63b3dad4983eb05caaf5a247d7dfb82aaeba47ea774cc8
starfarer.api.jar  a7ba18f3476ffe704729bd0a7a47443f035fea98a32ac2930eae8b391d013c2a
```

Для каждого target отдельно фиксируется whole-class SHA-256 и exact call-site count. Проверка должна
подтвердить: vanilla bytes преобразуются и проходят `BasicVerifier`; один изменённый byte оставляет
класс неизменным; неверный container path/name/hash при `fastForward.guardJar=true` fail-open; при
`false` container guard снимается, но неверный class hash всё равно блокирует transform. Переводной,
перепакованный или иной game build нельзя объявлять поддержанным по одному успешному startup.
Отдельный loaded-target policy test подтверждает, что поздний presentation-only target получает
локальный `SKIPPED_ALREADY_LOADED`, тогда как поздний frame marker выключает только presentation,
не весь structural startup.

Runtime harness выполняет outer frames с одним и несколькими substeps, mismatch фактического
fast-forward flag, изменившееся число steps, disabled master/marker и exception unwind. Для
`fastForward.visualTime=realtime` единственный финальный call получает исходный `amount`; для
`simulation` — `amount * substeps`. Out-of-scope и mismatch, известный до первого wrapper,
выполняют original calls с vanilla cadence. Поздний mismatch не может восстановить уже пропущенные
calls ранних substeps, поэтому harness отдельно подтверждает latch: после обнаружения дальнейшее
coalescing прекращается, а следующий outer frame начинает с чистого состояния. Simulation call
count и state при этом всегда остаются vanilla.

Safe/default matrix проверяется отдельно от aggressive: первые группы coalesce только presentation,
но всё равно намеренно меняют число visual/audio callbacks. Четыре aggressive opt-in группы требуют
дополнительных long-run visual captures и проверки callback/lifetime/RNG/emission cadence. Один
game-loader payload должен содержать `StarsectorPrepatcherPresentationHooks` и его nest members;
запуск с отдельным FastForward Presentation Patch agent не является поддерживаемой конфигурацией.

## Матрица целевых сценариев

| Патч | Сценарий для A/B | Activity signal | Обязательная проверка поведения |
|---|---|---|---|
| `patch.retainAll` | global/system map с тысячами entities/icons | `retainCalls`, `avoidedContainsUpperBound`, `retainKeysScanned/Removed`, `retainEqualityFallbacks` | тот же набор и порядок иконок; отдельно custom keys с `equals/hashCode` |
| `patch.scratchCollections` | длительный map render + движение мыши | allocation profile/GC | reentrant и exceptional exits не оставляют элементы scratch; нет cross-frame данных |
| `patch.labelSpatialCandidates` | global map на нескольких zoom и плотных кластерах | `labelCandidates=candidates/total` | те же решения о размещении/скрытии подписей на границах bucket и после TTL rebuild |
| `patch.hoverHitTestCache` | движение/остановка мыши над объектами и пустым hyperspace | `hoverHits`, `hoverMisses` | hover меняется не позднее TTL/cell boundary; click target остаётся правильным |
| `patch.intelCallbackCache` | Intel map со стрелками и plugins разных модов | `mapLocationHits/Misses`, `arrowHits/Misses` | callback return/`null`/exception fallback; допустима только заявленная TTL-видимость изменений |
| `patch.intelEntityIndex` | многократное обновление Intel icons | `intelIndexHits`, `intelIndexBuilds` | identity, а не `equals`; удаление/замена plugin становится видимой после rebuild |
| `patch.intelExistingIconLookup` | добавление, обновление и удаление Intel rows/icons | dedicated counter или instrumentation EventsPanel | direct lookup совпадает с vanilla scan; duplicate/missing key уходит в fallback |
| `patch.intelFastContains` | большой missing-list при перестроении Intel | dedicated counter или CPU profile EventsPanel | custom equality и duplicate entries дают тот же набор missing icons |
| `patch.arrowVectorPool` | Intel map с большим числом стрелок | allocation profile `Vector2f` | координаты/углы стрелок совпадают; pooled vectors не утекают наружу |
| `patch.systemNebulaCache` | повторно открыть system/global map, затем сменить систему | `nebulaHits`, `nebulaMisses` | synthetic entities каждый раз новые; metadata актуальны после invalidation |
| `patch.sampleCacheClearThrottle` | быстро закрывать/открывать hyperspace map | `sampleClearSkips` | после фактического clear и истечения interval terrain samples корректны |
| `patch.gridLineCap` | огромный сектор на min/max zoom | `Map grid LOD: ... spacing=...` | координаты и hit tests неизменны; меняется только плотность grid, без gaps/мерцания |
| internal `campaignCacheLifecycle` | load → map/Intel/route → reload/reset, несколько поколений | `campaignCacheResets`; GC harness/heap roots | после reset caches пусты, старые engine собираются; до lifecycle-ready используется vanilla fallback |
| `patch.campaignListenerThrottle` | закрытая карта, создание/удаление системы и custom list mutation | `campaignListenerRuns`, `campaignListenerSkips` | repositories получают listener сразу при обычном изменении и не позже audit при прямой мутации |
| `patch.campaignSnapshotReuse` | campaign advance в hyperspace/системах, paused/reentrant/throwing callbacks | JFR allocation stacks; counts пяти snapshot hooks | тот же point-in-time набор и порядок; no escape; все exits обнуляют campaign refs |
| `patch.entityScriptSnapshotReuse` | scripts добавляют/удаляют scripts и бросают exception | JFR allocation stacks; отсутствие scratch hooks на empty path | guard читает ровно `this.scripts` и входит перед исходным snapshot без обхода стороннего пролога; non-empty snapshot/order/call count остаются vanilla |
| `patch.emptyMemoryAdvanceFastPath` | тысячи пустых `Memory` плюс expire/require entries | CPU/allocation stacks; отсутствие двух retired iterator hooks | guard после restoration/pause читает `this.expire` + `this.require`; conversion, expiration/require cleanup/order на непустом пути совпадают с vanilla |
| `patch.routeJumpPointIndex` | маршруты внутри/между системами, wormholes и одинаковые anchors | route index hits/builds/fallbacks | destination, distance и tie-break совпадают; malformed/custom getter использует полный список |
| `patch.economyLocationCache` | ускорение времени в секторе с большим числом markets | `economyLocationChecks/Dirty/Skips` + JFR | explicit dirty rebuild same-frame; add/remove/reorder/move/id сразу меняет fingerprint; reset очищает state |
| `patch.economySnapshotReuse` | Economy/Market callbacks меняют markets/conditions/industries | `economySnapshotElements` + allocation stacks | тот же eager point-in-time набор/порядок; nested/throwing callbacks не видят alias |
| `patch.remoteMarketScheduler` | 500–3000 markets: remote/current/player/interaction/hidden; time acceleration; callback reentrancy; save with pending debt | `remoteMarketFrames`, `remoteMarketFullAdvances`, `remoteMarketDeferredCalls`, `remoteMarketHotAdvances`, `remoteMarketHiddenAdvances`, `remoteMarketSaveFlushes`, `remoteMarketFallbacks` + JFR Market.advance share | сумма delivered `amount` совпадает с vanilla; due markets сохраняют market-list order; first tick/hot markets immediate; nested/direct calls fail-open; pending debt zero after save flush; отдельно зафиксировать допустимые изменения callback count/RNG sequence |
| `patch.planetConditionMarketScheduler` | тысячи `planetConditionMarketOnly`: remote/current-location, stable phases, same-market duplicate path, reentrancy, scheduler-on/off independently, save with pending debt | `planetConditionScheduledEntries/Fallback/SaveFlush/Immediate`, scheduler counters + JFR Market.advance share | first tick/current location immediate; exact accumulated amount; stable phase; separate identity state; direct memory opt-out; lifecycle reset and pre-save debt zero; disabled path vanilla-immediate |
| `patch.directMarketObservation` | mod classes в system/child loaders: executed and never-executed call sites, ARG/CONST/derived amount, exception, reflection/MethodHandle, known planet engine path, two report intervals и validation smoke | `call-sites.csv`, `observations.csv`, `stacks.csv`, `unknown-stacks.csv`, `summary.csv`, `session.json`, `prepatcher.log`; JFR overhead A/B | direct call multiplicity/amount/order/thread/exception сохраняются; manifest существует до первого исполнения; planet path не считается unknown; unknown budget обновляется по интервалам; `sessionOrigin` различает game/startup-smoke/fr-smoke; observer failure не подавляет original call |
| `patch.economyPersistentSnapshots` | 1000+ markets, неизменные структуры, nested callbacks, backing-list replacement и прямые live-list edits | aggregate `economyPersistent*`; split `economyMarketPersistent*`, `marketConditionPersistent*`, `marketIndustryPersistent*`; `economyLocationEpochHits/Audits/SequenceMismatches` + JFR | API mutator и source replacement инвалидируют немедленно; unchanged owner-local audit не перепубликует fingerprint и не использует global map; direct edit виден не позже audit; RandomAccess/iterator identity order совпадает; старый snapshot не мутируется; load/clone/reset не сохраняют state |
| `patch.commodityEventModDirtyCache` | 1000+ markets в обычной кампании, затем торговое событие и истечение trade mod | JFR `reapplyEventMod`/`HashMap.remove`; structural field/control-flow checks | первый zero-вызов после load удаляет `eMod`, следующие zero-вызовы пропускают remove; nonzero сохраняет порядок remove -> calculate -> add, transient flag очищается до него; моды не должны напрямую использовать private key `eMod` |
| `patch.commodityTemporalFastPath` | 1000+ markets: stable commodities, API mutations, temporary expiry, retained `getMods()` map, reorder/add/remove commodity и shared/subclass stats | sampled `commodityTemporalMarkets/Entries/Active/InactiveSkips`; exact dirty/exposure/audit/rebuild/fallback counters; JFR `Market.advance`; actual-javaagent smoke | stable entry покидает active list; API mutation будит к следующему market tick; порядок live list сохраняется; expiry/reapply выполняются; shared/subclass/foreign state идёт vanilla; direct mutation обнаруживается не позже audit; owner/role/state не входят в save |
| `patch.tempModExpiryScheduler` | 1000+ markets, tied/nearby/ULP durations, add/refresh/remove/has/getMods/save, live-map mutation и subclass owner | `tempModInitialSweeps/ExpirySweeps/SyncSweeps/ScheduleRebuilds/ModsScanned/ExternalExposures/SubclassFallbacks/FailureFallbacks` + JFR + child-JVM actual-agent/XStream smoke | current minimum использует repeated float countdown; нет aggregate deadline comparison; one-pass materialize/removal/rebuild; add/remove/getMods/save синхронизируют; `hasMod` не sweep; exposure/subclass/anomaly идут retained vanilla; transient fields не входят в save |
| `patch.marketNoOpCallbacks` | тысячи inherited vanilla/BaseIndustry subclasses: dormant, building, disrupted, direct disruption-memory edit и custom overrides | JFR `BaseIndustry.advance`; structural retained-body/wake checks; isolated actual-agent audit=2 smoke; callback counters в fixture | dormant inherited call skips between audits; building/setDisrupted wake same call; custom `advance/isDisrupted/getDisruptedKey` full cadence; condition callbacks untouched; state transient |
| `patch.commRelaySystemIndex` | hyperspace с functional/nonfunctional relays и 1000+ systems | index hits/builds/fallbacks, candidates/total, validation scans/systems | relay и Random/tie behavior совпадают; size/endpoints проверяются каждый query; exact identity/location audit выполняется по TTL; владелец релиза принимает bounded staleness до TTL для direct middle-system mutation |
| `patch.shipAdvanceScratch` | бой 100–300 ships/fighters с modded listeners/commands | scratch counts + allocation profile | listener snapshot fresh; order, multiplicity, callback count и nested reentrancy совпадают |
| `patch.particleCleanup` | particle-heavy эффект с массовым expiry | group/expiry/linear-removal counts | все particles advance до cleanup; survivor order/duplicates/equality/exception behavior совпадают |
| `patch.loadingTextReader` | **known-disabled:** изолированный startup/mission прогон больших JSON/CSV с CRLF, UTF-8 boundary и ошибками чтения | allocation profile + loading/save runtime suite + mission smoke | текст, normalization, close и exception timing совпадают с vanilla; до доказательства переключатель остаётся `false` во всех профилях |
| `patch.startupLogAggregation` | **known-disabled:** изолированный cold startup/mission прогон большой mod-сборки | число INFO/WARN/ERROR до и после + mission smoke | уменьшаются только целевые repetitive INFO; работа loaders и WARN/ERROR сохраняются; до доказательства переключатель остаётся `false` во всех профилях |
| `patch.rulesLiteralParser` | загрузка большого rules.csv и randomized differential corpus | parser call/CPU profile + differential suite | split/replace semantics совпадают для delimiters, пустых fields и Unicode |
| `patch.saveLoadProgressThrottle` | сохранение и загрузка большого save | progress redraw count/interval | первый, финальный и forced update сохраняются; progress монотонен |
| `patch.saveOutputBufferDedup` | повторное save/load одного состояния | allocation profile output buffers | байты save, close/flush chain и exception behavior не меняются |
| `patch.fastForwardPresentation` | exact-hash build, fast-forward off/on, master switch off | per-target `APPLIED` и hash/loader skip statuses | full simulation выполняется на каждом substep; master off не меняет ни один target; неизвестные bytes fail-open |
| `patch.fastForwardFrameMarker` | outer frames с 1/N steps, ранний/поздний flag/step mismatch и exceptional exit | `frames`, `substeps`, `flagMismatches` + hook harness | final-only cadence только при согласованном marker; mismatch прекращает дальнейшее coalescing без state leak в следующий frame; ранние пропуски не replay'ятся |
| `patch.fastForwardActionIndicators` | action indicators при 1× и fast-forward N× | `skippedAction` + target call counter | safe/default: один финальный visual call вместо N; disabled даёт N; ранний/поздний mismatch проверяет documented latch; realtime/simulation amount проверяется отдельно |
| `patch.fastForwardLocationVisuals` | system/hyperspace background, light fader и particle group | `skippedLocation` + visual capture/call counters | safe/default: final state без пропажи объектов; 1× parity, marker fallback и оба visual-time режима |
| `patch.fastForwardFloatingText` | floating text в normal и paused entity paths | `skippedText` + lifetime/position capture | safe/default: один финальный update; текст, появившийся/удалённый на boundary, корректен; disabled/mismatch сохраняет vanilla cadence |
| `patch.fastForwardFleetView` | несколько fleets, zoom/selection и fast-forward transition | `skippedFleet` + `CampaignFleetView.advance` counter | safe/default: финальный view совпадает, промежуточный state не протекает; 1× и fallback полностью vanilla |
| `patch.fastForwardFleetPresentation` | ability layers, view clear, sensor range и pulse fader | `skippedFleet` + отдельные call-site counters | safe/default: каждый exact site вызывается один раз на финальном step; selection/ability state не stale после окончания fast-forward |
| `patch.fastForwardSensorIndicators` | selection и contact indicators, включая lazy obfuscated bridge | `skippedEntityUi`, `skippedSensor` + bridge resolution smoke | safe/default: final indicator state совпадает; bridge/linkage работает в vanilla/FR game loader; после mismatch original calls больше не подавляются |
| `patch.fastForwardCelestialVisuals` | planets и jump-point ring/corona animations | `skippedCelestial` + capture/call counters | safe/default: geometry/final state корректны; nonlinear difference режима `simulation` зафиксирована и не влияет на campaign state |
| `patch.fastForwardAuroraAnimation` | corona/magnetic/anomaly terrain aurora при N substeps | `skippedAurora` + renderer counter/capture | safe/default: один финальный renderer update; terrain mechanics продолжают каждый substep; wrong class hash остаётся vanilla |
| `patch.fastForwardContinuousSound` | terrain/ability/slipstream/gate loops, filters и music suppression | `skippedSound` + audio API call counters | safe/default: финальный loop/filter state слышим без dropout; 1×/disabled сохраняют multiplicity; mismatch прекращает дальнейшее подавление и сохраняет exception behavior |
| `patch.fastForwardGateJitter` | active/dormant gates, fader/warp/jitter transitions | `skippedGate`, `skippedJitter` + visual capture | safe/default: gate final state корректен; jitter seed меняется один раз только в подтверждённом N-step frame |
| `patch.fastForwardGlobalAnimations` | broad global animations в длительном fast-forward | `skippedAnimations` + animation callback/lifetime counters | **aggressive:** записать изменённую callback cadence и исключить зависимость mechanics/mod callbacks; проверить jumps в обоих visual-time режимах |
| `patch.fastForwardSensorFaders` | sensor visibility/fade-in/fade-out/despawn boundaries | `skippedSensor` + frame-by-frame capture | **aggressive:** visibility и despawn timing приемлемы; нет исчезновения/зависания; disabled возвращает exact vanilla cadence |
| `patch.fastForwardSlipstreamParticles` | вход/выход из slipstream и длительный fast-forward | `skippedSlipstream` + particle counts/lifetime/RNG capture | **aggressive:** документируются density/lifetime/RNG differences; mechanics/sound независимы; no orphan/stale particles после transition |
| `patch.fastForwardParticleEmitters` | gate, mote, coronal tap и Zig emitters на разных N | `skippedEmitters` + spawn/interval/RNG counters | **aggressive:** emission count/timing и RNG difference визуально приняты; interval не теряет state, disabled полностью vanilla, mismatch прекращает дальнейшее подавление |
| `patch.hyperspaceCulling` | hyperspace на широком/высоком viewport и нескольких zoom | offline site count + visual capture | `xEnd` использует width, `yEnd` height; нет пропавших/лишних видимых tiles у subclasses |
| `patch.hyperspaceYClamp` | non-square terrain grids, включая modded subclasses | offline site count + boundary screenshots | вертикальный clamp использует inner dimension без clipping/out-of-bounds |
| `patch.skipNoOpTerrainLayer` | полёт через обычный hyperspace и storms | target status + render/GPU profile | пропускается только `TERRAIN_9`; отсутствие его `preRender`/GL sequence визуально принято |
| `patch.terrainRandomReuse` | длительный terrain render с фиксированным состоянием | JFR allocations `Random`; отсутствие `LongAdder`/clock в tile stack; накопительный `pooledRandomApprox`; exact site counts | seed/draw sequence и итоговая геометрия совпадают; counter монотонен, включает live pending tails и остаётся approximate только из-за concurrent snapshot/weak pool lifecycle |
| `patch.automatonBufferReuse` | несколько rollover, два engine-internal reads, удержание public `getCells()`, unpatched owner и subclass owner | накопительные `automatonAlloc`, `automatonReuse`, `automatonInternalReads` + automaton regression | direct internal read разрешён только exact vanilla owner с подтверждённым reuse patch; public/subclass/unconfirmed fallback вызывает virtual getter; retained alias не меняется; reused buffer zeroed |
| `patch.starfieldCleanupBuffers` | длительный hyperspace flight с массовым parallax expiry | cleanup-list allocations | двухфазный cleanup, survivor set/order и exceptional behavior совпадают |
| `patch.starfieldLinearRemoval` | starfield выше/ниже removal threshold, duplicates/custom equality | removal CPU/counts | stable order и equality-aware fallback совпадают с исходным `removeAll` |

Комбинированный scheduler-аудит измеряется в доставленных callback attempts. При совместном прогоне
нужно отдельно подтвердить, что `commodity.temporalAuditFrames`/`market.structureAuditFrames`
увеличиваются только на фактическом `Market.advance()`, а `market.noOpIndustryAuditFrames` — на
попытке inherited industry callback. Для remote/hidden cadence `4`/`8` допустимое wall-frame окно
приблизительно умножается на этот cadence; hot/safety/save paths должны сокращать его.

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
| Ложная несовместимость локализованного JAR | whole-class hash реагирует на нерелевантные строки/constant pool | локальные structural contracts и exact site counts прогоняются на JAR текущей установки — оригинальной либо локализованной; whole-class hash не используется |
| False-green verifier | успех определяется как `patched > 0`, а `UNCHANGED`/missing target допустим | exit `0` только при точном числе targets и sites; missing/unchanged/duplicate/failed дают non-zero |
| TTL correctness | до истечения TTL не проверяется дешёвый mutable input: position, order, identity, tag | мутация сразу после build сравнивается с vanilla; явно доказано допустимое окно stale-данных либо есть live validation |
| Failed-cache storm | failed build не memoize-ится и повторяет allocation/full scan каждый вызов | malformed/custom объект многократно вызывает path; build выполняется ограниченно, fallback остаётся полным |
| Encoding/platform drift | `javac` использует системную code page; PowerShell содержит bash syntax; проверка шла на другой JDK | explicit UTF-8, parser/syntax check для каждого script, полный прогон на целевом JDK 17 и ОС |

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

## Обязательные test gates

До merge должны пройти:

- positive structural transform и `BasicVerifier` для каждого target class;
- повторный transform с точным `ALREADY_APPLIED` без изменений bytes и без structural skip;
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
- полный `verify-structural` на целевой Java 17; PowerShell parser check и POSIX shell syntax check;
- реальный startup/activity smoke на той же Starsector-установке, для которой публикуется guard.

Численное performance-заявление делается только после этих gates и одинакового A/B-прогона. Если
какой-либо gate временно неприменим, патч остаётся experimental/disabled by default. Исключение
допустимо только по явному решению владельца релиза, при наличии отдельного kill switch и записи
причины/остаточного риска в отчёте соответствующего выпуска из [`releases/`](releases/0.8.0.md).

## Временно отключённые startup-патчи

`patch.loadingTextReader` и `patch.startupLogAggregation` имеют подтверждённую регрессию запуска
миссий в текущей modded-сборке. В `0.7.1` оба переключателя установлены в `false` во всех
поставляемых профилях: default, safe и aggressive. Их реализация и тестовые сценарии сохранены для
отдельной диагностики, но повторное включение в поставляемом профиле запрещено до изоляции причины,
исправления и нового startup/mission B-прогона каждого патча по отдельности.

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
| `patch.commRelaySystemIndex` | hyperspace с functional/nonfunctional relays и 1000+ systems | index hits/builds/fallbacks, candidates/total, validation scans/systems | relay и Random/tie behavior совпадают; size/endpoints проверяются каждый query; exact identity/location audit выполняется по TTL; владелец релиза принимает bounded staleness до TTL для direct middle-system mutation |
| `patch.shipAdvanceScratch` | бой 100–300 ships/fighters с modded listeners/commands | scratch counts + allocation profile | listener snapshot fresh; order, multiplicity, callback count и nested reentrancy совпадают |
| `patch.particleCleanup` | particle-heavy эффект с массовым expiry | group/expiry/linear-removal counts | все particles advance до cleanup; survivor order/duplicates/equality/exception behavior совпадают |
| `patch.loadingTextReader` | **known-disabled:** изолированный startup/mission прогон больших JSON/CSV с CRLF, UTF-8 boundary и ошибками чтения | allocation profile + loading/save runtime suite + mission smoke | текст, normalization, close и exception timing совпадают с vanilla; до доказательства переключатель остаётся `false` во всех профилях |
| `patch.startupLogAggregation` | **known-disabled:** изолированный cold startup/mission прогон большой mod-сборки | число INFO/WARN/ERROR до и после + mission smoke | уменьшаются только целевые repetitive INFO; работа loaders и WARN/ERROR сохраняются; до доказательства переключатель остаётся `false` во всех профилях |
| `patch.rulesLiteralParser` | загрузка большого rules.csv и randomized differential corpus | parser call/CPU profile + differential suite | split/replace semantics совпадают для delimiters, пустых fields и Unicode |
| `patch.saveLoadProgressThrottle` | сохранение и загрузка большого save | progress redraw count/interval | первый, финальный и forced update сохраняются; progress монотонен |
| `patch.saveOutputBufferDedup` | повторное save/load одного состояния | allocation profile output buffers | байты save, close/flush chain и exception behavior не меняются |
| `patch.hyperspaceCulling` | hyperspace на широком/высоком viewport и нескольких zoom | offline site count + visual capture | `xEnd` использует width, `yEnd` height; нет пропавших/лишних видимых tiles у subclasses |
| `patch.hyperspaceYClamp` | non-square terrain grids, включая modded subclasses | offline site count + boundary screenshots | вертикальный clamp использует inner dimension без clipping/out-of-bounds |
| `patch.skipNoOpTerrainLayer` | полёт через обычный hyperspace и storms | target status + render/GPU profile | пропускается только `TERRAIN_9`; отсутствие его `preRender`/GL sequence визуально принято |
| `patch.terrainRandomReuse` | длительный terrain render с фиксированным состоянием | JFR allocations `Random`; отсутствие `LongAdder`/clock в tile stack; накопительный `pooledRandomApprox`; exact site counts | seed/draw sequence и итоговая геометрия совпадают; counter монотонен, включает live pending tails и остаётся approximate только из-за concurrent snapshot/weak pool lifecycle |
| `patch.automatonBufferReuse` | несколько rollover, два engine-internal reads, удержание public `getCells()`, unpatched owner и subclass owner | накопительные `automatonAlloc`, `automatonReuse`, `automatonInternalReads` + automaton regression | direct internal read разрешён только exact vanilla owner с подтверждённым reuse patch; public/subclass/unconfirmed fallback вызывает virtual getter; retained alias не меняется; reused buffer zeroed |
| `patch.starfieldCleanupBuffers` | длительный hyperspace flight с массовым parallax expiry | cleanup-list allocations | двухфазный cleanup, survivor set/order и exceptional behavior совпадают |
| `patch.starfieldLinearRemoval` | starfield выше/ниже removal threshold, duplicates/custom equality | removal CPU/counts | stable order и equality-aware fallback совпадают с исходным `removeAll` |

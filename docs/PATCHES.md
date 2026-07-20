# Карта патчей

## Модель применения

Основной agent выполняет независимое structural matching фактически загружаемого bytecode. Каждый
блок имеет ownership marker, postcondition, ASM verification и fail-open fallback.

Hyperspace-патчи проходят тот же независимый structural pipeline единого agent: каждый патч
проверяет target-класс, метод и descriptor, локальный bytecode-контракт, точное число точек
внедрения, ownership marker, postcondition и результат ASM verification. Allowlist полных SHA-256
не используется, поэтому оригинальный и переводной JAR принимаются автоматически, пока структура
конкретного изменяемого участка совместима; изменения строк и constant pool этому не мешают. При
неизвестном или неоднозначном участке только соответствующий патч остаётся vanilla со статусом
`SKIPPED_STRUCTURAL`, а остальные патчи рассматриваются независимо.

Единственное намеренное исключение — интегрированный FastForward Presentation Patch `1.1.0`.
Его call-site replacements используют whole-class SHA-256 и, по умолчанию, SHA-256 содержащего JAR.
Этот блок поддерживает только exact game files, перечисленные ниже; structural-совместимость
переводных или перепакованных JAR для него не заявляется.

## Main agent

| Config | Target/область | Изменение | Основной compatibility-инвариант |
|---|---|---|---|
| `patch.retainAll` | map `A.H.renderStuff` | linear reconciliation вместо `keySet().retainAll(ArrayList)` | порядок `LinkedHashMap`, equality-aware fallback |
| `patch.scratchCollections` | map renderer/input | reusable internal lists/sets/vectors | no escape, reentrant scratch |
| `patch.labelSpatialCandidates` | map labels | spatial candidate buckets | original exact overlap checks остаются |
| `patch.intelCallbackCache` | Intel map | short TTL для `getMapLocation/getArrowData` | miss/error вызывает callback |
| `patch.intelEntityIndex` | Intel synthetic entities | identity index | generation/TTL/fallback |
| `patch.intelExistingIconLookup` | EventsPanel | direct icon lookup | full scan fallback |
| `patch.intelFastContains` | EventsPanel | hash membership | vanilla equality |
| `patch.hoverHitTestCache` | map hover | cell/TTL cache exact vanilla result | bounded staleness, miss runs original |
| `patch.systemNebulaCache` | map construction | immutable system metadata cache | synthetic entities создаются заново |
| `patch.sampleCacheClearThrottle` | map construction | suppress rapid repeated clear | configurable interval |
| `patch.gridLineCap` | huge sector map | dynamic grid spacing | визуальный LOD only |
| `patch.arrowVectorPool` | Intel arrows | internal vectors | reference не выходит наружу |
| internal lifecycle | `CampaignEngine.set/resetInstance` | clear all campaign-bound caches | two-phase generation boundary |
| `patch.campaignListenerThrottle` | campaign advance | skip unchanged internal repository listener sweep | public method untouched, periodic audit |
| `patch.campaignSnapshotReuse` | `BaseLocation` | reusable eager snapshots | point-in-time order/mutation isolation |
| `patch.entityScriptSnapshotReuse` | entity scripts | inline empty fast return; non-empty fresh vanilla snapshot | no scratch scope for empty lists; mutation isolation unchanged |
| `patch.emptyMemoryAdvanceFastPath` | `Memory.advance` | inline return when expire+require are empty | restoration/pause and non-empty loops remain vanilla |
| `patch.routeJumpPointIndex` | route widget | ordered jump/system candidates | original filter/distance/tie loop |
| `patch.economyLocationCache` | `Economy.advance` | omit only redundant automatic dirty write | explicit mod dirty state authoritative |
| `patch.economySnapshotReuse` | Economy/Market | reusable market/condition/industry snapshots | callback cadence/order unchanged |
| `patch.remoteMarketScheduler` | `Economy.advance` + pre-save boundary | stable-phase full-market scheduling with exact accumulated amount | direct mod calls immediate; hot markets full-rate; callback cadence of remote markets intentionally changes |
| `patch.planetConditionMarketScheduler` | `BaseCampaignEntity.advance` + `Economy` frame boundary + pre-save | independent stable-phase scheduling for `planetConditionMarketOnly` markets | first tick/current location immediate; exact amount; separate identity state and save flush |
| `patch.directMarketObservation` | mod call sites + known engine origins + concrete `Market.advance` entry | synchronous wrappers, eager call-site manifest, sampled timing and interval-bounded stack attribution | direct mod calls are never delayed/merged/suppressed; known planet path is classified separately |
| `patch.economyPersistentSnapshots` | Economy/Market | owner-local copy-on-write snapshots + structure epochs | API mutators invalidate immediately; direct live-list edits bounded by audit |
| `patch.commodityEventModDirtyCache` | `CommodityOnMarket.reapplyEventMod` | skip repeated removal after zero quantity proved private `eMod` absent | first zero call/load and the complete nonzero remove/calculate/add path stay vanilla; direct external mutation of the private key is unsupported |
| `patch.commodityTemporalFastPath` | `Market.advance` + `MutableStat` | ordered active set: stable commodity skips 4 temp-stat advances and event-mod reapply | API mutations wake immediately; subclasses/shared stats fall back; direct live-map edits bounded by audit |
| `patch.tempModExpiryScheduler` | `MutableStatWithTempMods.advance` | direct float countdown ближайшего expiry; one-pass map scan только у deadline/synchronization | sync before mutation/read/save; live-map exposure, subclasses и anomalies используют retained vanilla path |
| `patch.marketNoOpCallbacks` | inherited `BaseIndustry.advance` | after a full dormant proof, skip disruption/build checks until wake or bounded audit | custom `advance/isDisrupted/getDisruptedKey` stay vanilla; direct disruption-memory edits may wait for audit |
| `patch.commRelaySystemIndex` | `IntelManager` | conservative spatial system candidates + TTL position audit | original order/live relay checks; bounded coordinate staleness |
| `patch.shipAdvanceScratch` | `Ship.advance` | reuse 3 lists + command snapshot + 2 sets | fresh listener snapshot, no API objects pooled |
| `patch.particleCleanup` | `DynamicParticleGroup` | reuse expiry list + stable linear removal | all particles advance before removal |
| `patch.loadingTextReader` | `LoadingUtils` | streaming UTF-8 normalization | **known-disabled in 0.7.1 profiles:** mission startup regression requires separate fix |
| `patch.startupLogAggregation` | loaders/specs/rules/sound | remove/aggregate high-volume INFO | **known-disabled in 0.7.1 profiles:** mission startup regression requires separate fix |
| `patch.rulesLiteralParser` | rules loader | literal fixed-delimiter operations | randomized differential semantics |
| `patch.saveLoadProgressThrottle` | progress streams | redraw ceiling | final forced updates retained |
| `patch.saveOutputBufferDedup` | save output chain | remove one duplicate outer buffer | save bytes/format/close chain unchanged |

### Commodity temporal active set

`patch.commodityTemporalFastPath` заменяет только фиксированный commodity-maintenance loop внутри
точного vanilla `Market.advance()`. Каждый market хранит private transient entries в исходном порядке
live commodity list и отдельный ordered active list. Commodity без temporary modifiers, pending dirty
signal и audit work не вызывает:

```text
4 × MutableStatWithTempMods.advance(days)
1 × CommodityOnMarket.reapplyEventMod()
```

В точном vanilla `MutableStat` synthetic owner/role fields связывают stat с единственным commodity.
Четырнадцать штатных mutation methods имеют null-guard notification: `setBaseValue`, `modify*`,
`unmodify*` и temporary-mod operations будят entry до следующего `Market.advance()`. Internal source
`eMod` игнорируется как self-write `reapplyEventMod()`. Shared stats, subclasses и foreign shape
получают полный vanilla loop.

`commodity.temporalAuditFrames` ограничивает задержку для прямых изменений backing maps/lists в
обход mutators. На audit active set проверяет identity/order commodity list, будит все entries и
вызывает private bridge direct temp-mod scheduler, чтобы materialize/rebuild nearest deadline.
Первый audit deadline разнесён между markets по identity/generation. High-volume counters
`Markets/Entries/Active/InactiveSkips` sampled раз в 64 market calls; transition/fallback counters
остаются точными.
Public `getMods()` по-прежнему переводит конкретный stat на retained vanilla scheduler; если мод
позднее изменит сохранённую live map, market audit снова обнаружит непустой stat и вернёт commodity
в active list. Same-size mutation посреди deferred interval имеет неизвестный точный момент и потому
может получить aggregate elapsed attribution — это намеренный aggressive-profile компромисс.

State и binding fields private/transient/synthetic, в save не попадают. Ошибка helper-а, lifecycle
not-ready или structural ambiguity выполняет исходный commodity loop. Порядок обработанных
commodities не меняется, а никакие condition/industry/submarket callbacks этим патчем не
throttling'уются. Dirty mutation другого inactive commodity из callback может перенести его работу
на следующий market tick; это документированная aggressive-семантика. Active set автоматически
устанавливает direct temp-mod scheduler, от которого зависит его membership logic.

### Expiry-aware temporary stats

`patch.tempModExpiryScheduler` сохраняет исходные тела `advance`, `getMods`, private `getMod`,
`removeTemporaryMod`, `hasMod` и `writeReplace` как private synthetic fallback. В exact vanilla owner
шесть private transient scalar fields находятся прямо в `MutableStatWithTempMods`: отдельный helper
object и reflection отсутствуют на hot path. Ближайший deadline обновляется тем же покадровым
`float countdown -= days`, что и vanilla `timeRemaining -= days`; aggregate `deferred >= minimum` не
используется, поскольку сложение и последовательное вычитание округляются по-разному.

До deadline `advance()` выполняет O(1). У ближайшего expiry либо перед mutation/read/save один проход
по `LinkedHashMap` одновременно материализует deferred time, удаляет истёкшие entries через original
`Iterator.remove()`, вызывает `unmodify(source)` в исходном порядке и строит следующий schedule.
Счётчик tied minima позволяет обновлять schedule после refresh/remove без лишнего полного прохода.
`hasMod()` использует уже актуальное membership и не запускает materialization sweep.

Публичный `getMods()` сначала синхронизирует значения и затем навсегда переводит конкретный stat на
retained vanilla per-frame path, поскольку мод может сохранить mutable live map. Подклассы,
необычный backing-map, неожиданная прямая мутация размера и runtime anomaly также fail-open остаются
vanilla. State transient; `writeReplace()` материализует значения, поэтому scheduler не попадает в
save. Reflection-чтение package-private `timeRemaining` без `getMods()` между sync points может
увидеть последнее материализованное значение. Для далёких survivors aggregate materialization может
отличаться на несколько ULP от покадрового vanilla subtraction; exact nearest countdown и порядок
удаления сохраняются без хранения всей истории `days`.

### Dormant inherited BaseIndustry callbacks

`patch.marketNoOpCallbacks` в `0.9.5` не переносит параллельный callback-helper буквально.
Transformer сохраняет полное исходное `BaseIndustry.advance(float)` как private synthetic raw method
и устанавливает прямой wrapper с двумя `private transient synthetic int` fields. После одного полного
vanilla-вызова exact inherited implementation считается dormant, если `building=false` и
`wasDisrupted=false`. Следующие вызовы выполняют только два field checks и countdown decrement.

Full raw call выполняется немедленно, когда:

- `building` или `wasDisrupted` становятся true;
- вызван штатный `BaseIndustry.setDisrupted(float, boolean)` — transformer добавляет wake-prologue;
- истёк `market.noOpIndustryAuditFrames`;
- runtime class переопределяет `advance(float)`, `isDisrupted()` или `getDisruptedKey()`;
- structural matcher или helper classification не подтвердили точный контракт.

Параллельная версия также классифицировала пустой inherited
`BaseMarketConditionPlugin.advance()`, но такой helper-call оказался дороже самого пустого virtual
callback. Поэтому condition callbacks вообще не перехватываются. Для industries нет глобальной
`IdentityHashMap`, reflection или helper на steady-state hot path: class eligibility вычисляется
`ClassValue` только после первого полного вызова конкретного экземпляра.

Патч намеренно меняет callback count только у exact inherited dormant `BaseIndustry`. Модовые
`advance()` overrides продолжают работать каждый market frame. Мод, который меняет disruption
через private market memory в обход `setDisrupted()`, может быть замечен только следующим bounded
audit. Safe profile выключает блок; default/aggressive включают его как performance-first
компромисс. Synthetic state не сериализуется.

Оба startup-патча остаются в коде и structural/runtime suites, но все поставляемые профили держат
их выключенными. Они не возвращаются в default/safe/aggressive до изолированного исправления и
успешного startup/mission прогона каждого переключателя по отдельности.

### Агрессивный remote-market scheduler

`patch.remoteMarketScheduler` меняет только один engine call site — интерфейсный вызов
`MarketAPI.advance(amount)` внутри ordered snapshot-loop `Economy.advance()`. Прямые вызовы
`MarketAPI.advance()` из модов и других engine paths остаются vanilla-immediate.

Настройки default/aggressive profile:

```properties
patch.remoteMarketScheduler=true
market.remote.frames=4
market.remote.hiddenFrames=8
market.remote.maxDeferredFrames=8
market.remote.maxDeferredGameDays=0.02
market.hot.currentLocation=true
market.hot.playerOwned=true
market.hot.interaction=true
market.remote.policyAuditFrames=60
market.remote.fullRateMemoryKey=$starsectorPrepatcher_fullRateMarket
```

Для skipped frame исходный `amount` суммируется. При stable phase, safety cap, переходе в hot state
или pre-save flush рынок получает полный накопленный interval одним вызовом. Новый рынок всегда
получает первый вызов немедленно. Due markets по-прежнему вызываются в исходном порядке market
snapshot; scheduler не запускает background threads и не сериализует state.

Performance-first компромиссы:

- frame-count callback cadence удалённых conditions/industries/submarkets уменьшается;
- RNG sequence plugin'ов, вызывающих random один раз на callback, может измениться;
- точное состояние, наблюдаемое одним рынком у другого между staggered phases, может отставать на
  ограниченное окно;
- direct mutation memory opt-out может стать видимой не позднее `policyAuditFrames`;
- накопленный elapsed time сохраняется, но plugin, игнорирующий `amount` и считающий callbacks,
  поведёт себя иначе.

Frame-based audit-параметры считают фактически доставленные вызовы, а не глобальные engine frames.
В частности, `commodity.temporalAuditFrames` и `market.structureAuditFrames` увеличиваются при
`Market.advance()`, а `market.noOpIndustryAuditFrames` — при попытке вызвать inherited
`BaseIndustry.advance()` внутри такого market tick. Поэтому для удалённого рынка с cadence `4` или
hidden cadence `8` wall-frame окно соответствующего audit может растянуться примерно в `4×` или
`8×` (до safety/hot/save wake). Это составной aggressive-компромисс; current/hot markets остаются
full-rate, а safe profile отключает оба scheduler-а.

Full-rate остаются текущая location, interaction market, player-owned markets и рынки с:

```java
market.getMemoryWithoutUpdate().set(
        "$starsectorPrepatcher_fullRateMarket", true);
```

`profiles/safe.properties` отключает scheduler. Nested/reentrant `Economy.advance()` определяется
через reentrant scope и полностью уходит в immediate fallback, не меняя frame context внешнего
прохода.

### Scheduler `planetConditionMarketOnly`

`patch.planetConditionMarketScheduler` закрывает отдельный vanilla call site:

```text
BaseCampaignEntity.advance(float)
  -> market.isPlanetConditionMarketOnly()
  -> MarketAPI.advance(amount)
```

Этот путь не входит в ordered snapshot-loop `Economy.advance()` и поэтому не мог использовать
`patch.remoteMarketScheduler`. Новый bridge применяется только после точной structural-проверки
predicate, receiver `this.market`, аргумента `amount` и единственного interface-call site.

Настройки default/aggressive profile:

```properties
patch.planetConditionMarketScheduler=true
market.planetCondition.frames=4
market.planetCondition.currentLocationFrames=1
market.planetCondition.maxDeferredFrames=8
market.planetCondition.maxDeferredGameDays=0.02
market.planetCondition.policyAuditFrames=60
market.planetCondition.fullRateMemoryKey=$starsectorPrepatcher_fullRateMarket
```

Scheduler имеет отдельную identity map и не объединяет debt с central remote-market scheduler. Это
сохраняет multiplicity в патологическом случае, когда один и тот же `MarketAPI` достигается обоими
vanilla-путями. Первый вызов нового market выполняется немедленно. В location игрока cadence по
умолчанию равен vanilla; direct opt-out memory key, reentrant fallback, campaign-lifecycle reset и
pre-save flush работают независимо от центрального scheduler.

Если `patch.remoteMarketScheduler=false`, transformer добавляет в `Economy.advance()` только точный
frame-clock/scratch boundary, необходимый planet scheduler; обычный central market call остаётся
vanilla. Если frame context ещё не существует, amount некорректен или helper не может безопасно
получить policy/state, текущий planet-condition вызов выполняется немедленно.

Performance-first компромисс тот же: удалённый condition-only market получает меньше полных
`Market.advance()` callbacks с суммарным elapsed `amount`; код, считающий callbacks вместо времени,
может изменить поведение. `profiles/safe.properties` оставляет этот патч выключенным.

### Level 1: observation прямых mod-вызовов Market.advance

`patch.directMarketObservation` не является scheduler-патчем. Он предназначен для диагностического
прогона перед проектированием robust direct-call policy и **не меняет cadence или результат**
прямых вызовов.

Отдельный transformer рассматривает mod bytecode и заменяет только прямые вызовы:

```text
invokeinterface MarketAPI.advance(F)V
invokevirtual   Market.advance(F)V
```

на typed wrapper. Transformer заранее регистрирует manifest call site после успешной bytecode
verification, поэтому `call-sites.csv` содержит найденные sites даже до первого исполнения. Wrapper:

1. записывает site ID и дешёвые counters;
2. при выбранном sampling измеряет inclusive время и снимает stack;
3. синхронно вызывает исходный `market.advance(amount)` в том же потоке;
4. сохраняет multiplicity, float `amount`, порядок и exception propagation.

Concrete vanilla `Market.advance(float)` дополнительно имеет дешёвый entry probe. Calls от
central scheduler, planet-condition scheduler/immediate path, их fail-open/save-flush путей и
instrumented mod sites помечаются через `ThreadLocal` origin. Поэтому массовый известный vanilla
`planetConditionMarketOnly` путь больше не загрязняет `UNKNOWN_DIRECT`. Непомеченный вход получает
ограниченное число stack samples **на каждый отчётный интервал**, что сохраняет шанс обнаружить
поздние reflection/MethodHandle и нестандартные loader paths.

Настройки default/aggressive profile:

```properties
patch.directMarketObservation=true
directMarket.timingSampleEvery=128
directMarket.stackSampleEvery=2048
directMarket.maxStacksPerSite=8
directMarket.reportIntervalSeconds=15
directMarket.maxSites=4096
directMarket.unknownStackSamples=32
```

`session.json` использует schema 2 и содержит `sessionOrigin`. Обычный запуск имеет значение
`game`; startup/FR validation smoke получает отдельное значение и заметный префикс каталога, чтобы
короткую тестовую JVM нельзя было принять за игровую телеметрию.

Каждый запуск создаёт отдельную session-директорию:

```text
logs/direct-market-observe/session-[<origin>-]<UTC>-pid<PID>/
```

Для анализа нужны все файлы session вместе с `logs/prepatcher.log`. Observer не удерживает
`MarketAPI` references, не пишет данные в save, работает только на daemon reporter thread и
fail-open при любой telemetry/file ошибке. Ненулевой диагностический overhead принят по дизайну;
`profiles/safe.properties` держит этот переключатель выключенным.

### Persistent economy snapshots

При `patch.economyPersistentSnapshots=true` старый `patch.economySnapshotReuse` остаётся
fresh-copy fallback, но normal Economy/Market advance больше не выполняет покадровый
`clear()+addAll()` для markets, conditions и industries.

Каждый transformed owner хранит private transient copy-on-write state:

- Economy: market snapshot, structure epoch и ReachEconomy location fingerprint;
- Market: отдельные condition и industry snapshots.

Owner-local fields убирают глобальный `IdentityHashMap` lookup из каждого `Market.advance()`.
Конструкторы, `readResolve()` и `Market.clone()` создают независимые states. Published snapshot
никогда не очищается после публикации: nested/reentrant callback может создать и опубликовать новый
`ArrayList`, не изменяя список, который уже итерирует внешний callback.

Обычные `Economy.add/removeMarket`, `Market.add/removeCondition` и
`Market.add/removeIndustry` paths повышают structure epoch и перестраивают snapshot на следующем
borrow. Замена самого backing list обнаруживается немедленно по reference identity и также повышает
epoch. Прямые изменения identity/order элементов существующего live list обнаруживаются bounded
audit:

- Economy market list и market/location/id fingerprint: `economy.structureAuditMs`;
- Market conditions и industries: `market.structureAuditFrames`.

`RandomAccess` lists проверяются индексированным identity scan без iterator allocations; остальные
collections используют iterator fallback. Missing/foreign state и helper error возвращают свежий
vanilla-style `ArrayList`.

В persistent режиме ReachEconomy fingerprint хранится в том же Economy state, поэтому steady-state
location path не выполняет synchronized lookup глобальной weak map. Оригинальный
`ReachEconomy.updateLocationMap()` всё равно вызывается каждый frame: explicit dirty flag мода
остаётся authoritative. Unchanged periodic audit лишь переносит deadline и не создаёт новый
fingerprint.

## Fast-forward presentation coalescing

| Config | Target/область | Изменение | Профиль и основной риск |
|---|---|---|---|
| `patch.fastForwardPresentation` | весь presentation-блок | master switch exact-hash transformer/runtime | safe/default/aggressive; выключение оставляет весь блок vanilla |
| `patch.fastForwardFrameMarker` | `CampaignState.advance` | отмечает outer frame, номер и число simulation substeps | обязателен для всех групп; mismatch fast-forward flag немедленно прекращает дальнейшее coalescing в этом frame |
| `patch.fastForwardActionIndicators` | `CampaignEngine` action indicators | один visual `advance` на последнем substep | safe/default; меняется только presentation cadence |
| `patch.fastForwardLocationVisuals` | `BaseLocation` light fader, background/stars, particle group | visual refresh один раз за outer frame | safe/default; визуальные lifetime/скорость следуют выбранному visual time |
| `patch.fastForwardFloatingText` | entity floating text, включая paused path | один visual `advance` на последнем substep | safe/default; текст не ускоряется вместе с simulation при `realtime` |
| `patch.fastForwardFleetView` | `CampaignFleetView.advance` | один view refresh за outer frame | safe/default; промежуточные substep states не рисуются |
| `patch.fastForwardFleetPresentation` | fleet layers/view clear/sensor range/pulse fader | объединяет fleet-only presentation work | safe/default; видимым остаётся финальное состояние outer frame |
| `patch.fastForwardSensorIndicators` | selection/contact indicators | один indicator refresh на последнем substep | safe/default; selection bridge fail-open при marker mismatch |
| `patch.fastForwardCelestialVisuals` | planets, jump-point rings/corona | объединяет графические animation calls | safe/default; nonlinear animation может отличаться при `simulation` visual time |
| `patch.fastForwardAuroraAnimation` | terrain `AuroraRenderer` | один aurora refresh за outer frame | safe/default; промежуточная визуальная анимация пропускается |
| `patch.fastForwardContinuousSound` | terrain, abilities, slipstream и gate loops/filters/music suppression | повторные audio refresh calls выполняются на финальном substep | safe/default; transient промежуточные audio parameters не подаются mixer'у |
| `patch.fastForwardGateJitter` | gate faders/warp/jitter seed | объединяет gate-only visual updates | safe/default; jitter RNG обновляется один раз за outer frame |
| `patch.fastForwardGlobalAnimations` | global `AnimationManager.advanceAll` | объединяет все global animation callbacks | **aggressive opt-in:** широкая callback/lifetime-семантика, возможны скачки и изменённая cadence |
| `patch.fastForwardSensorFaders` | entity sensor faders | один fader update за outer frame | **aggressive opt-in:** может менять visibility/despawn timing |
| `patch.fastForwardSlipstreamParticles` | slipstream particle add/advance | emission и particle advance только на финальном substep | **aggressive opt-in:** меняются density, lifetime, RNG и emission cadence |
| `patch.fastForwardParticleEmitters` | gate/mote/coronal/Zig emitter intervals | interval advances только на финальном substep | **aggressive opt-in:** меняются spawn count/timing и RNG sequence |

Simulation logic по-прежнему выполняется на каждом substep. Runtime coalescing действует только
внутри подтверждённого multi-step outer frame и выполняет целевой presentation-call на последнем
substep. Если frame marker, число шагов или `CampaignEngine.isFastForwardIteration()` расходятся с
ожидаемой формой, runtime фиксирует mismatch до конца frame и с момента обнаружения выполняет
последующие wrappers с vanilla cadence. Уже пропущенные calls ранних substeps намеренно не
проигрываются задним числом; следующий `beginOuterFrame` полностью сбрасывает это состояние.

Exact-build allowlist в этой интеграции ограничен следующими container SHA-256:

```text
starfarer_obf.jar  5dd222b9e266d2ac2d63b3dad4983eb05caaf5a247d7dfb82aaeba47ea774cc8
starfarer.api.jar  a7ba18f3476ffe704729bd0a7a47443f035fea98a32ac2930eae8b391d013c2a
```

Каждый target дополнительно требует exact whole-class hash. При несовпадении class/JAR bytes патч
этого target получает `SKIPPED_CLASS_HASH` или `SKIPPED_CONTAINER_HASH` и оставляет original bytes.
`fastForward.guardJar=true` сохраняет обе проверки. Значение `false` отключает только container
guard: exact class hash остаётся обязательным и поддержка другого build этим не заявляется.

`fastForward.visualTime=realtime` передаёт финальному call обычный `amount`: визуальная/audio
presentation идёт в реальном frame cadence, пока simulation ускорена. Значение `simulation`
умножает `amount` на число substeps; оно сохраняет суммарное visual time, но может давать заметные
скачки и нелинейные отличия. `fastForward.verbose` управляет подробными сообщениями об успешном
применении; hash/loader/error skips всегда остаются видимыми. `fastForward.metrics` включает
накопительные frame/substep/skip counters и потому по умолчанию
выключен.

Default и safe profiles включают master/frame marker и консервативные группы до gate jitter
включительно; четыре bold-marked группы остаются `false`. Aggressive profile включает все группы,
но не меняет `visualTime=realtime`. Название safe означает более узкую область риска, а не
byte-for-byte visual parity: сама цель блока — намеренно убрать повторные presentation callbacks.

Интеграция не устанавливает второй agent. Exact-hash transformer регистрируется внутри того же
`StarsectorPrepatcherAgent.jar` перед structural transformer, а
`StarsectorPrepatcherPresentationHooks` входит в общий target/game-loader runtime payload. Поэтому
для vanilla и Faster Rendering сохраняется одно loader-identity правило и одна `-javaagent` запись;
исходный отдельный FastForward Presentation Patch agent одновременно устанавливать не нужно.

Ранняя загрузка presentation-only target не является причиной отменять прежний structural-блок:
этот target получает `SKIPPED_ALREADY_LOADED` и остаётся vanilla, а остальные targets продолжают
загружаться через оба transformer'а. Если уже загружен обязательный `CampaignState` frame marker,
отключается только presentation transformer; structural patches всё равно устанавливаются.

У Faster Rendering `ProtectionDomain.CodeSource` для `com.fs.*` пуст, поэтому container guard берёт
точный `jar:` resource у defining loader и всё равно проверяет имя entry, bytes entry, имя JAR и
SHA-256 всего JAR. Это не разрешает upstream-modified class bytes: если FR изменил target до вызова
Instrumentation, exact whole-class hash даёт `SKIPPED_CLASS_HASH`. В текущем `fr.jar` так происходит
с `HyperspaceTerrainPlugin`; его presentation-call остаётся vanilla, а независимые structural
patches применяются своим transformer'ом.

## Hyperspace

| Config | Target | Изменение | Принятое поведение/риск |
|---|---|---|---|
| `patch.hyperspaceCulling` | `BaseTiledTerrain.render/isTileVisible` | third width load in yEnd changed to height | affects every subclass by design |
| `patch.hyperspaceYClamp` | same | second end clamp uses inner dimension | affects every subclass by design |
| `patch.skipNoOpTerrainLayer` | `HyperspaceTerrainPlugin.getActiveLayers` | removes `TERRAIN_9` from backing set | also skips that layer's `preRender` sequence by design |
| `patch.terrainRandomReuse` | tiled/hyperspace terrain | seeded `Random` ring + batched diagnostics | same seed/draw sequence; cumulative approximate counter; no per-tile `LongAdder`/clock call |
| `patch.automatonBufferReuse` | automaton + terrain internal reads | owner-local spare `int[][]`; confirmed exact-owner internal reads bypass public escape mark | public/mod/subclass/unconfirmed paths use virtual getter; escaped aliases are never reused; transient state, zero-init |
| `patch.starfieldCleanupBuffers` | parallax starfield implementation | reusable stale list | two-phase cleanup retained |
| `patch.starfieldLinearRemoval` | same | thresholded stable iterator removal | order retained; equality-aware fallback |

## Намеренно не реализовано

- storm/automaton update throttling;
- dropped simulation debt;
- неявные callback-frequency changes вне явно документированных `patch.remoteMarketScheduler`, `patch.planetConditionMarketScheduler` и exact dormant-наследников `BaseIndustry`;
- public combat-grid reuse;
- generic particle object pooling;
- fleet-pair broadphase без runtime parity harness;
- GL batching/FBO/VBO;
- inter-frame terrain geometry cache;
- save-format или serialized-object changes.

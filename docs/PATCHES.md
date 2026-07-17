# Карта патчей 0.4.0-exp6

Каждый блок независимо сопоставляется с фактически загружаемым bytecode по symbolic/data-flow
контракту. Несовместимый блок пропускается без отмены уже подтверждённых патчей класса.

| Блок | Target | Vanilla-проблема | Изменение | Совместимость/риск |
|---|---|---|---|---|
| `patch.retainAll` | `A.H.renderStuff(FZ)V` | `keySet().retainAll(ArrayList)` даёт `O(K×E)` каждый render | identity fast path; reusable HashSet fallback для custom equality; явный iterator | низкий; сохраняется equals/hashCode-семантика modded keys |
| `patch.scratchCollections` | `A.H`, `A.A` | hot-path `ArrayList/HashSet/Vector2f` allocations | thread-local scratch | низкий/средний |
| `patch.labelSpatialCandidates` | `A.H.getTextAlignmentFor` | полный scan icons для каждой новой подписи | spatial buckets, затем оригинальные exact checks | средний, TTL index |
| `patch.hoverHitTestCache` | `A.A.OO0000(FFF)` | entity + all-system scan на mouse events | short screen-cell/TTL cache; vanilla method на miss | средний, краткая задержка hover |
| `patch.intelCallbackCache` | `A.A`, `A.Z`, `EventsPanel` | повторные `getMapLocation/getArrowData` callbacks | per-plugin/per-map TTL | средний |
| `patch.intelEntityIndex` | `A.H.getIntelIconEntity` | scan Intel synthetic entities | identity index | средний, TTL |
| `patch.intelExistingIconLookup` | `EventsPanel` | scan `icons.values()` при известном ключе | direct lookup + vanilla fallback | низкий |
| `patch.intelFastContains` | `EventsPanel` | вложенный `ArrayList.contains` | thread-local HashSet | низкий |
| `patch.arrowVectorPool` | `A.Z` | две temporary vectors на arrow/frame | thread-local ring | низкий |
| `patch.systemNebulaCache` | `A.H.updateSystemNebulas` | три прохода systems при каждом создании map | metadata cache; fresh synthetic entities сохраняются | средний |
| `patch.sampleCacheClearThrottle` | `A.H.<init>` | повторный terrain sample-cache clear | min interval | средний |
| `patch.gridLineCap` | `A.H.null(F)V` | grid lines растут с физическим размером сектора | dynamic spacing/max lines | средний, визуальный LOD |
| internal `campaignCacheLifecycle` | `CampaignEngine.setInstance/resetInstance` | process-lifetime javaagent-кэши могут удерживать старую кампанию | two-phase identity generation reset всех campaign-object caches | safety invariant; без отдельной настройки, begin до singleton write + completion на normal return |
| `patch.campaignListenerThrottle` | `CampaignEngine.advance` | каждый кадр O(N) `readdChangeListeners()` | first/change/audit refresh | низкий; public method не меняется |
| `patch.campaignSnapshotReuse` | `BaseLocation.advance`, `advanceEvenIfPaused` | пять `new ArrayList(Collection)` snapshots и их iterators на campaign advance | scratch-scoped array-backed point-in-time snapshots | низкий; exact iterator-only sites, no mutation/escape, ссылки очищаются на всех exits |
| `patch.entityScriptSnapshotReuse` | `BaseCampaignEntity.runScripts` | defensive `ArrayList(Collection)` и iterator на каждый entity-script advance | reusable point-in-time snapshot; shared empty list для пустого source | низкий; один structurally proven iterator-only site, scratch очищается на всех exits |
| `patch.emptyMemoryAdvanceFastPath` | `Memory.advance` | пустые expire list и require values всё равно создают два iterator-объекта | `Collections.emptyIterator()` только для пустого source | низкий; непустой путь вызывает исходный iterator, порядок expiry/require не меняется |
| `patch.routeJumpPointIndex` | `A.O0Oo.getNextStep`, `getLastLegDistance` | 3 scans всех hyperspace jump points + 1 scan systems | ordered identity indexes, original selection loop сохраняется | средний; полный snapshot раз в TTL 250 мс, miss -> full list; safe profile: off |

## Новое в exp6: telemetry-driven campaign allocations

Профиль allocation/CPU на большой modded-кампании указал на оставшийся churn в vanilla
`BaseLocation.advance`, `BaseCampaignEntity.runScripts` и `Memory.advance`. Это исходная
телеметрия для выбора targets, а не измерение результата exp6: повторный A/B-прогон после сборки
ещё требуется.

`patch.campaignSnapshotReuse` заменяет ровно три defensive copies в `BaseLocation.advance` и две
в `advanceEvenIfPaused`. `patch.entityScriptSnapshotReuse` заменяет одну такую copy в
`BaseCampaignEntity.runScripts`. Каждый вызов по-прежнему получает point-in-time содержимое и
порядок исходной collection; повторно используется только array/iterator storage после warm-up.
Transformer доказывает, что результат сразу записан в local, local не сливается с другим значением
и используется только ожидаемым числом вызовов `List.iterator()` — без mutation, return, field
store или передачи стороннему коду.

Оба snapshot-блока устанавливают reentrant scratch scope с cleanup на каждом normal и exceptional
exit. Cleanup обнуляет занятые array slots, включая путь, на котором custom collection бросила
исключение во время копирования. Process-lifetime `ThreadLocal` сохраняет только пустую ёмкость, а
не entity, location, script или другой campaign object. Отключённая настройка вызывает исходный
`new ArrayList(source)`.

`patch.emptyMemoryAdvanceFastPath` принимает только два однозначных site: `List.iterator()` от
instance expire-list и `Collection.iterator()` от `LinkedHashMap.values()` другого instance field.
Matcher также требует прежний порядок после `CampaignClock.convertToDays`: expire scan раньше
require scan. При пустом source hook возвращает shared empty iterator; при непустом вызывает
`source.iterator()`, поэтому remove/iteration семантика рабочего пути остаётся vanilla. При
отключённой настройке исходный iterator вызывается и для пустого source.

## Новое: campaign cache lifecycle

`CampaignEngine.setInstance(newEngine)` и `resetInstance()` теперь используют two-phase noexcept
границу. Begin-hook закрывает generation и очищает кэши непосредственно перед публикацией нового
singleton-state; completion-hook с тем же transition token активирует поколение только на normal
return, после `Global.setSector`, factory и остальной штатной логики. Matcher требует единственное
static self-typed поле engine, доказывает data-flow записей `argument 0`/`null` и проверяет обе фазы;
одних имён методов недостаточно.

При смене identity очищаются `INTEL_CACHE`, `LABEL_INDEXES`, `INTEL_ENTITY_INDEXES`, `HIT_CACHES`,
`SAMPLE_CLEAR_TIMES`, `CAMPAIGN_LISTENER_STATES`, оба route index, `nebulaCacheSlot` и scratch текущего
потока. `CampaignEngine` отслеживается слабой ссылкой. Scratch-объекты других вызовов безопасны
за счёт reentrant scope с очисткой на каждом normal/exceptional exit.

Stateful-кэши активируются только после успешного normal return non-null lifecycle event. После reset или
при несовместимом lifecycle bytecode они вызывают исходную игровую логику и ничего не сохраняют.
В поддерживаемых сборках hook устанавливается до первой кампании, поэтому все оптимизации работают
в полном объёме; после смены кампании они лишь лениво прогреваются заново. Monotonic generation epoch
проверяется повторно под блокировкой каждой static-структуры, поэтому завершившийся с опозданием
вызов старой кампании не может повторно опубликовать её объекты после очистки. Lifecycle заменяет
volatile-ссылки на восемь map-кэшей и отдельный nebula-slot новыми пустыми экземплярами, а не ждёт их
monitors: старые roots отсоединяются сразу, и обратного порядка cache locks при reentrant mod callbacks
нет. Завершившийся поздно nebula builder пишет только в уже отсоединённый slot и удаляет stale value.
Устаревший или
несовпадающий completion token принудительно закрывает generation и повторяет detachment fail-closed.

## Новое: campaign listener refresh

Vanilla `CampaignEngine.readdChangeListeners()`:

```text
hyperspace.getObjects().setListener(engine)
for each starSystem:
    starSystem.getObjects().setListener(engine)
```

`ObjectRepository.setListener()` — простая запись поля. При этом `CampaignEngine.advance()`
вызывает метод каждый кадр. Начиная с exp5 трансформируется только этот один call site. Методы
`createStarSystem`, `removeStarSystem` и публичный `readdChangeListeners` не меняются.

Refresh выполняется при:

- первом вызове;
- смене identity списка systems или hyperspace;
- изменении size/first/last system;
- истечении audit interval.

Audit покрывает неподдерживаемую прямую замену элемента внутри list без изменения размера.

## Новое: route candidate indexes

Патч не заменяет метод маршрутизации целиком. В трёх точках:

```java
Global.getSector().getHyperspace().getJumpPoints()
```

подставляется ordered subset jump points, у которых первая destination относится к нужной системе. Далее оригинальный bytecode снова проверяет:

- `isWormhole()`;
- наличие destinations;
- identity содержащей location;
- star destination;
- расстояние и tie-break.

В одном месте `CampaignEngine.getStarSystems()` подставляется ordered candidate list для известного
hyperspace anchor; несколько systems с одинаковым anchor не теряются. Оригинальный bytecode повторно
сравнивает anchor identity, поэтому stale positive безопасно отфильтровывается. Cache miss возвращает
полный vanilla list.

На каждом hit проверяются size/first/last. Раз в `route.indexTtlMs` дополнительно сверяется полный
identity snapshot списка и отношение jump-point -> destination system / system -> anchor. Если
сторонний мод изменил середину списка или переназначил объект, индекс перестраивается. Между полными
проверками возможна ограниченная TTL задержка видимости прямой мутации; поэтому safe profile держит
этот блок выключенным. Неиндексируемый `List` и malformed/custom getter сразу используют vanilla
fallback; исключение полного аудита переводит индекс в failed state до следующего TTL, без hot-loop
исключений.

## Проверка совместимости

Точные structural/data-flow preconditions, postconditions, статусы и ограничения описаны в
[`STRUCTURAL_COMPATIBILITY.md`](STRUCTURAL_COMPATIBILITY.md). Хеши JAR и классов не участвуют в
решении о применении патча.

Уровни доказательств, runtime-сигналы и отдельные A/B-сценарии для каждого блока перечислены в
[`PATCH_VALIDATION_CHECKLIST.md`](PATCH_VALIDATION_CHECKLIST.md).

## Намеренно не изменено

- Staggered advance всех неактивных systems: удаление scan требует отдельной модели `activeThisFrame` и может ломать моды.
- Economy, intel manager, listeners, faction and script advances.
- `Misc.findNearestJumpPointTo()` для выхода из текущей системы.
- Порядок маршрутизационных candidates и окончательный выбор.
- Public API и save serialization.

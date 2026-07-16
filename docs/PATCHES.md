# Карта патчей 0.4.0-exp4

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
| `patch.campaignListenerThrottle` | `CampaignEngine.advance` | каждый кадр O(N) `readdChangeListeners()` | first/change/audit refresh | низкий; public method не меняется |
| `patch.routeJumpPointIndex` | `A.O0Oo.getNextStep`, `getLastLegDistance` | 3 scans всех hyperspace jump points + 1 scan systems | ordered identity indexes, original selection loop сохраняется | средний; полный snapshot раз в TTL 250 мс, miss -> full list; safe profile: off |

## Новое: campaign listener refresh

Vanilla `CampaignEngine.readdChangeListeners()`:

```text
hyperspace.getObjects().setListener(engine)
for each starSystem:
    starSystem.getObjects().setListener(engine)
```

`ObjectRepository.setListener()` — простая запись поля. При этом `CampaignEngine.advance()` вызывает метод каждый кадр. Exp4 трансформирует только этот один call site. Методы `createStarSystem`, `removeStarSystem` и публичный `readdChangeListeners` не меняются.

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

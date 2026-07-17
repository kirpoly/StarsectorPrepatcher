# Сборка из исходников

Требуется JDK 17+ и установленный Starsector 0.98a-RC8. Скрипты предполагают, что каталог мода расположен в `<Starsector>/mods/StarsectorMapOptimizer`.

Windows:

```text
build-agent.bat
```

Linux/macOS из каталога мода:

```bash
chmod +x build-agent.sh
./build-agent.sh
```

Agent использует ASM, встроенный в JDK (`jdk.internal.org.objectweb.asm`, `tree`, `tree.analysis`).
На старте он открывает эти пакеты только своему unnamed module через
`Instrumentation.redefineModule`; пользовательские `--add-exports` в `vmparams` не требуются.

Сборка создаёт:

```text
agent/StarsectorMapOptimizerAgent.jar
jars/StarsectorMapOptimizerBootstrap.jar
```

Структурный regression harness для одного или нескольких core JAR:

```powershell
.\verify-structural.ps1 -CoreJars 'C:\path\one.jar','C:\path\two.jar'
```

Linux/macOS:

```bash
chmod +x verify-structural.sh
./verify-structural.sh /path/one.jar /path/two.jar
```

Он пересобирает agent, затем выполняет все трансформации в памяти, проверяет точную runtime-linkage
hooks, `BasicVerifier`, scratch exception frames, идемпотентность/ownership и отрицательные
missing/ambiguous cases. После структурной проверки отдельный lifecycle harness загружает собранный
agent JAR, заполняет все campaign-кэши фактических packaged-классов, переключает identity engine
и проверяет two-phase inactive/complete boundary и очистку через `WeakReference`/`ReferenceQueue`.
Game JAR не изменяется. Lifecycle
harness намеренно завершается ошибкой при запуске JVM с `-XX:+DisableExplicitGC`, поскольку такой
режим делает GC-проверку недоказательной.

Для `0.4.0-exp6` structural harness также должен подтвердить пять snapshot-sites
`BaseLocation.advance()/advanceEvenIfPaused()`, один site `BaseCampaignEntity.runScripts()` и два
iterator-sites `Memory.advance()`. Для snapshot-блоков проверяются точный constructor/data-flow
contract, отсутствие mutation/escape, reentrant scratch scope и очистка normal/exceptional exits;
для `Memory` — происхождение expire list и `LinkedHashMap.values()`, их порядок после clock
conversion и сохранение исходного iterator на непустом пути.

Успешная сборка и structural verification подтверждают linkage/bytecode-контракт, но не измеряют
выигрыш. Перед численным заявлением об exp6 нужен отдельный A/B-прогон одного сохранения и сценария
с тремя новыми `patch.*` сначала выключенными, затем включёнными, с одинаковой profiler/JFR
методикой.

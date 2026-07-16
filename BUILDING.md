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

Он пересобирает agent, затем выполняет все трансформации в памяти, проверяет точную runtime-linkage
hooks, `BasicVerifier`, scratch exception frames, идемпотентность/ownership и отрицательные
missing/ambiguous cases. Game JAR не изменяется.

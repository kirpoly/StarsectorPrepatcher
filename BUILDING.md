# Сборка StarsectorPrepatcher

## Требования

- JDK 17+;
- установленная Starsector `0.98a-RC8`;
- каталог мода расположен как `<Starsector>/mods/StarsectorPrepatcher`.

Build scripts используют JDK-internal ASM, уже входящий в Java. В собранные JAR не добавляются
сторонние ASM dependencies.

## Сборка

Windows PowerShell:

```powershell
.\build-agent.ps1
```

Linux/macOS:

```bash
./build-agent.sh
```

Создаются:

```text
agent/StarsectorPrepatcherAgent.jar
agent/StarsectorPrepatcherHyperspaceAgent.jar
jars/StarsectorPrepatcherBootstrap.jar
```

В конце сборка атомарно регенерирует `SHA256SUMS.txt` для поставляемых файлов. Сам manifest,
runtime-логи и содержимое `.build/` в него не входят.

## Полная проверка

Windows PowerShell:

```powershell
.\verify-structural.ps1
```

Для явного набора core JAR поддерживаются как именованный, так и прежний positional-вызов:

```powershell
.\verify-structural.ps1 -CoreJars @(
    'C:\Games\Starsector_test\starsector-core\starfarer_obf.jar',
    'C:\Games\Starsector_test\starsector-core\fs.common_obf.jar',
    'C:\Games\Starsector_test\starsector-core\fs.sound_obf.jar'
)
```

Linux/macOS:

```bash
./verify-structural.sh
```

Проверка:

1. заново собирает оба agent и bootstrap;
2. компилирует tests;
3. проверяет SemVer/changelog, относительные ссылки, достижимость документации и все SHA-256;
4. трансформирует `starfarer_obf.jar`, `fs.common_obf.jar`, `fs.sound_obf.jar` в памяти;
5. выполняет ASM `Analyzer + BasicVerifier` для concrete methods;
6. проверяет idempotency/ownership/negative structural cases;
7. запускает lifecycle, exp6, exp8 и loading/save runtime suites;
8. отдельно проверяет exact-hash hyperspace targets из `starfarer.api.jar` и `starfarer_obf.jar`;
9. запускает оба собранных javaagent в одной JVM и сохраняет startup smoke.

Structural harness создаёт внутренний `.build/structural-all-enabled.properties` из aggressive
profile и только там включает known-disabled loading/startup patches, чтобы продолжать проверять
их bytecode-контракты. Этот файл не поставляется игроку и не используется startup smoke; во всех
поставляемых профилях оба проблемных переключателя остаются `false`.

Сырые отчёты `documentation-consistency.txt`, `structural-verification.txt`,
`runtime-regression.txt`, `hyperspace-verification.txt` и `startup-smoke.txt` создаются в
`.build/reports/` и намеренно не входят в документацию или дистрибутив.
Краткие пользовательские изменения фиксируются в [`CHANGELOG.md`](CHANGELOG.md), а причины,
измерения и остаточные риски — в [`docs/releases/`](docs/releases/0.8.0.md). Обязательные regression
gates для новых pre-load патчей описаны в [`docs/VALIDATION.md`](docs/VALIDATION.md).

## Java 17 compatibility

Scripts используют `-encoding UTF-8 -source 17 -target 17`. Из-за доступа к JDK-internal ASM вместо `--release 17`
применяются compile-time `--add-exports`. Runtime vmparams flags не нужны: оба startup-agent вызывают
`Instrumentation.redefineModule()` до регистрации transformer.

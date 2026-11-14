# Localized Rain

[简体中文](README.md) | English

A NeoForge mod that turns vanilla Minecraft's "the whole dimension changes weather at once" into **weather determined independently per biome patch**.

The weather unit is a **3D biome patch**: every 16×16 block column is sampled at quart (4-block) resolution into a list of vertical biome segments. Adjacent columns whose segments share the same biome and overlap in Y are merged into one connected component (union-find). Caves, floating islands, and the 3D biomes of the Nether and the End therefore all get their own weather — no "surface height" or "`|substate`" approximation anymore. The server simulates every patch that belongs to a loaded chunk; weather in unloaded areas is frozen and its state is kept.

When the player crosses a patch boundary, the sky, rain sound, and precipitation particles fade over about 5 seconds, while the Xaero's Minimap weather line switches immediately.

- Minecraft **1.21.1**
- NeoForge **21.1.265+**
- Mod ID `localizedrain`, package `com.spectrum255.localizedrain`

## Features

- Weather units are **3D biome patches**: a 16×16 column is sampled at quart resolution into vertical biome segments; segments of adjacent columns with overlapping Y ranges are merged into one connected component. The canonical key is the lexicographically smallest column/segment coordinate, the weather state moves with it when patches merge, and the union-find parents are persisted with the save, so ownership survives restarts.
- Unconnected biomes of the same type have separate weather; every position inside one connected 3D component shares it. Caves, floating islands, and Nether/End 3D biomes are resolved correctly without sampling height or 3D substate suffixes.
- The server advances **every patch in loaded chunks** (in batches every `weatherSimulationIntervalTicks`, advancing each patch by the same number of ticks so the total rate is unchanged). By default all segments of a column are advanced (including caves/floating islands); with `scan3dSubstates` disabled only the surface segment of each column is advanced. Unloaded patches are frozen and keep their state.
- Dimensions without skylight (the Nether and the End) also take part; they stay clear by default, and weather set via commands/API runs for its own duration.
- Inactive weather states are pruned automatically (`stateRetentionDays` + `maxStoredStates`), together with union-find parents that no live state references, so saves do not grow forever.
- Vanilla rain/thunder levels, sky brightness, undead burning, and monster spawning are all localized to the patch the entity is in.
- Thunder patches spawn lightning explicitly at the vanilla density (equivalent to 1/100000 per chunk per tick), instead of relying on vanilla's global-only thunder roll.
- Client rendering and rain sound keep the server-side 0.01/tick per-player smoothing, so crossing patches fades over about 5 seconds.
- Biome weather allow/deny lists, forced weather, and **candidate weather** (a set of possible types per biome, one is rolled when the current phase ends). Built-in types are `clear`/`rain`/`thunder`; addon mods may register their own (see the API section).
- Performance: column scanning has a per-tick budget (`maxColumnScansPerTick` + 2 ms); a scan resolves a whole column and classifies all of its segments at once; hot weather lookups are cached per quart cell in an LRU; state ticking can advance N ticks at once (closed-form fast-forward when timers do not flip), so the scan interval adds no extra CPU cost.
- Weather types are a dedicated NeoForge registry (`localizedrain:weather_type`). Addon mods can register their own types and query/set weather through `WeatherApi`; all server-side checks (`isRainingAt`, `isPrecipitatingAt`, `isThunderingAt`, …) work by type.

## Requirements

- Minecraft 1.21.1
- NeoForge 21.1.265 or newer
- Java 21

## Configuration

`config/localizedrain-common.toml`:

```toml
weatherBiomeWhitelist = []
weatherBiomeBlacklist = []
weatherBiomeOverrides = []
weatherBiomeCandidates = []
weatherSimulationIntervalTicks = 20
scan3dSubstates = true
stateRetentionDays = 7
maxStoredStates = 4096
maxParentEntries = 16384
maxColumnScansPerTick = 4
debugLogging = false
```

Entries accept `minecraft:desert` (exact), `minecraft:*` (whole namespace), and `#minecraft:is_ocean` (biome tag).

`weatherSimulationIntervalTicks` controls how often loaded chunks are swept (default 20 = once per second, range 1–200). It only changes the granularity: every sweep advances each patch by "interval" ticks, so weather durations and fade rates stay the same. Setting it to 1 means sweeping every tick at linearly higher scan cost.

`scan3dSubstates` (default true) controls whether all segments of a column take part: when enabled, one column scan covers caves, floating islands, the stratosphere, etc.; when disabled only the surface segment is advanced, which helps on very heavy servers (3D biomes then fall back to surface weather).

`stateRetentionDays` (default 7, 0 = no time-based pruning) and `maxStoredStates` (default 4096, range 256–65536) control save cleanup: every 1200 ticks, patches not advanced for longer than the retention period are removed, then entries are evicted by last-advanced time until the count limit is met. A pruned patch starts a fresh clear-weather cycle when it is loaded again.

`maxParentEntries` (default 16384, range 4096–1000000) is the hard cap for persisted union-find parents; orphaned entries are evicted when it is exceeded. It is sized at roughly 4× `maxStoredStates`.

`maxColumnScansPerTick` (default 4, range 1–64) limits how many columns may be scanned per tick; there is also a 2 ms per-tick time budget. The column the player is in is not budgeted, so entering a new area never shows a temporary "clear weather" gap.

`debugLogging` (default false) prints `[LRDBG]` lines every 20 ticks (player patch, column scan time, merge counts) for diagnosing ownership.

`weatherBiomeCandidates` gives a biome a set of possible weather types: `<biome selector>=<type>|<type>`, or `<selector>={type1, type2}`. One is rolled when the phase ends; candidate types count as allowed and take priority over the built-in clear/rain/thunder cycle; non-precipitating candidates do not raise the rain level, and thunder only appears when the type allows it.

- **Whitelist**: empty means everything is allowed; when non-empty, only listed biomes participate in localized weather.
- **Blacklist**: always clear (no weather ticking, no lightning, no snow accumulation).
- **Forced weather**: `<biome selector>=<weather type id>`, for example `minecraft:desert=clear`, `minecraft:*=rain`, `#minecraft:is_ocean=thunder`, or any registered type such as `minecraft:desert=examplemod:sandstorm`. The short names `clear`/`rain`/`thunder` are the built-in types. A matched patch keeps that weather and no longer switches randomly; with several matches the later entry in the config wins (so `minecraft:*` can be used as a fallback and individual biomes overridden). Type ids are resolved lazily: the config is not validated against the registry at load time, so addon types can be written directly. Forced weather snaps the server-side rain/thunder level immediately and freezes the timers; the client still fades at 0.01/tick.

Priority is **forced weather > candidate weather > blacklist > whitelist**. Biomes excluded by a list or locked by forced weather are skipped by `/weather`, which reports how many were skipped. With all lists empty the behavior is the same as before.

## Commands

```
/weather clear|rain|thunder [duration]
/weather clear|rain|thunder at <pos>
/weather clear|rain|thunder area <from> <to>
/weather clear|rain|thunder global
/weather type <id> [duration|at <pos>|area <from> <to>|global]
```

Requires permission level 2. `type` takes an id from the `localizedrain:weather_type` registry (with tab completion); the built-in `clear`/`rain`/`thunder` are shortcuts for the three built-in ids.

`at` / no argument targets the 3D biome patch at that position (using the position's actual height). `area` samples the patches covered by `from`/`to` on a 128-block horizontal grid (512 sample points / 64 patches maximum; at most 8 column scans per invocation; otherwise it asks you to narrow the area or run `at` inside it first). `global` targets all patches already recorded in the current dimension.

## Weather types (library API)

Weather types are registered in `localizedrain:weather_type` (`com.spectrum255.localizedrain.api.WeatherType`): `displayKey`, `hasPrecipitation`, `Biome.Precipitation`, `allowsThunder`. Built-ins are `clear` / `rain` / `thunder`.

Registering a type from an addon mod (use your own namespace so the id becomes `examplemod:sandstorm`):

```java
private static final DeferredRegister<WeatherType> WEATHER_TYPES =
        DeferredRegister.create(WeatherTypes.REGISTRY_KEY, "examplemod");

public static final DeferredHolder<WeatherType, WeatherType> SANDSTORM =
        WEATHER_TYPES.register("sandstorm",
                () -> new WeatherType("weather.example.sandstorm", false, Biome.Precipitation.NONE, false));
```

Call `WEATHER_TYPES.register(modEventBus);` in the mod constructor, then set weather:

```java
WeatherApi.setWeatherAt(level, pos, ResourceLocation.fromNamespaceAndPath("examplemod", "sandstorm"), 6000);
// or set an already-resolved set of patches:
WeatherApi.setWeather(level, patches, typeId, 6000);
```

Query entry points: `WeatherApi.typeAt` / `patchAt` / `isRainingAt` / `isPrecipitatingAt` / `isThunderingAt` / `isThunderingRegionAt` / `thunderLevelAt` / `skyDarkenAt` / `patches`.

`patchAtCell(level, cellX, cellZ)` takes 16×16 column (chunk) coordinates (it converts to quart internally); for quart coordinates use `patchAtQuart(level, quartX, quartZ[, budget])`. Patch resolution remains 16×16 columns. Intensity reuses vanilla's `rainLevel`/`thunderLevel` channels, and client rendering is unchanged.

## Optional compatibility

**Xaero's Minimap**: only enabled when the mod is present; nothing Xaero-related is loaded otherwise, and it is not needed to compile. The integration uses a "target weather" payload plus a replacement of the built-in `weather` info line, so the HUD switches immediately while rendering keeps its fade.

## Building

JDK 21 is required (Gradle 9.2.1 does not support newer JDKs). The first build downloads NeoForge dependencies; once the cache is complete the build works offline:

```powershell
$env:JAVA_HOME='C:\Program Files\Microsoft\jdk-21.0.6.7-hotspot'
.\gradlew.bat clean build --offline --console=plain
```

The artifact is `build/libs/localizedrain-<version>.jar`.

## Known limitations

- Client rain/snow particles are rendered from the rain level of the patch the player is in, not per block (intentional): vanilla has a single rain column around the camera, and crossing patches is smoothed by the 0.01/tick fade.
- Horizontal resolution is a 16-block column; vertical resolution is a quart (4 blocks). Very thin or narrow biome layers may be quantized into a neighbouring segment.
- Column scanning samples at the chunk center; biome changes inside one column follow the center.
- The player's column is not subject to the scan budget, so entering completely unexplored terrain still costs one full column scan.
- Unloaded patches do not tick and keep their state; patches that have never been scanned are treated as clear.
- A patch removed by cleanup has no persistent weather memory: it starts a new clear-weather cycle when loaded again.
- The default 1-second scan interval means weather changes (rain/clear) take up to 1 second to apply; the rain level fade still runs at 0.01/tick.
- Old (`p:`, `g:`, and `|biome` substate) save keys are discarded on load; old weather states are not migrated.
- Candidate/custom weather types only provide server-side weather semantics; particles, sounds, and fog must be rendered by the addon mod itself.

## Docs

- [docs/idea.md](docs/idea.md): design goal
- [docs/release-1.0.0-preview.md](docs/release-1.0.0-preview.md): 1.0.0-preview release notes

## License

MIT License, see [LICENSE](LICENSE). `mod_license` in `gradle.properties` is `MIT` and is written into `neoforge.mods.toml` in the built jar.

This repository does not contain Xaero's Minimap / XaeroLib jars, nor any third-party mod binaries.

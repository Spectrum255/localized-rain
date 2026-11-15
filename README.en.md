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
- Biome weather whitelist, blacklist, and forced weather, all using a `biome=weather` format: the whitelist defines a biome's weather pool, the blacklist removes weather from it, and forced weather pins a biome to one type. Weather may be a built-in type (`clear`/`rain`/`thunder`), an addon-registered type, or a type tag such as `#localizedrain:dry` (see Configuration).
- Performance: column scanning has a per-tick budget (`maxColumnScansPerTick` + 2 ms); a scan resolves a whole column and classifies all of its segments at once; hot weather lookups are cached per quart cell in an LRU; state ticking can advance N ticks at once (closed-form fast-forward when timers do not flip), so the scan interval adds no extra CPU cost.
- Weather types are a dedicated NeoForge registry (`localizedrain:weather_type`). Addon mods can register their own types and query/set weather through `WeatherApi`; all server-side checks (`isRainingAt`, `isPrecipitatingAt`, `isThunderingAt`, …) work by type.
- A new **mobile weather mode** (`weatherMode`): weather is carried by storms shaped as a horizontal circle plus a vertical range, which drift with the wind and evolve through data-driven stages. It is strictly exclusive with the biome-patch mode and the all-vanilla mode — see "Three weather modes" below.

## Requirements

- Minecraft 1.21.1
- NeoForge 21.1.265 or newer
- Java 21

## Three weather modes

`weatherMode` takes one of three values (default `patches`). The modes are mutually exclusive: exactly one system advances weather at a time.

| Mode | Behavior | Notes |
|---|---|---|
| `patches` | Default. 3D biome-patch weather: every biome segment of a 16×16 column is resolved and advanced independently. | Existing behavior, unchanged |
| `mobile` | Dedicated storm system: a storm is a horizontal circle plus a vertical range, drifts with the wind and evolves through its profile's stages. The client derives a target rain/thunder level from the player's distance and height, then feeds it smoothly into vanilla's rain/snow/thunder channels. | Clear everywhere while no storm exists (natural or spawned) |
| `vanilla` | Everything goes back to vanilla: all weather injections are bypassed (mixins return early), so precipitation, sky darkening and lightning are vanilla again. | Useful alongside other weather mods |

The mode can only be changed in the config file, and **switching requires a game restart**. Save data of each mode is kept, so switching back resumes where it left off.

## Configuration

`config/localizedrain-common.toml`:

```toml
weatherMode = "patches"
weatherBiomeWhitelist = []
weatherBiomeBlacklist = []
weatherBiomeOverrides = []
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

`weatherBiomeCandidates` has been removed (old entries are ignored). All three lists use `<biome selector>=<weather>`:

- **Whitelist** `weatherBiomeWhitelist`: a matching biome's natural cycle only draws from the listed weather (several entries may target the same biome). For example `minecraft:plains=clear` plus `minecraft:plains=localizedrain:rain` gives plains clear-or-rain only. When empty, the default natural pool is used: the `#localizedrain:natural` data-pack tag if it exists, otherwise built-in clear/rain/thunder.
- **Blacklist** `weatherBiomeBlacklist`: a matching biome never gets the listed weather but still cycles through the rest. `minecraft:desert=localizedrain:rain` makes deserts rainless while clear/thunder can still happen.
- **Forced weather** `weatherBiomeOverrides`: a matching biome always has exactly that weather; when several entries match, the FIRST entry (top to bottom) wins (so `minecraft:*` can be used as a fallback and individual biomes overridden). Type ids are resolved lazily, so addon types can be written directly. A biome forced to clear is truly locked: commands and the API cannot create weather there either.

The whitelist and blacklist only constrain the natural cycle: when a biome is clear merely because its natural pool has nothing but clear (for example a whitelist that only lists `clear`, or a dry biome with precipitation types dropped), commands and the API can still create weather there. Only a **forced clear** is a hard lock.

The weather part may be a type id (`weatherplus:blizzard`), a short name (`clear`/`rain`/`thunder`), or a weather type tag such as `#localizedrain:dry` (tags are only supported in the whitelist and blacklist; forced weather needs a concrete type).

A biome without an explicit whitelist that does not precipitate (`Biome#hasPrecipitation() == false`, e.g. vanilla Nether/End biomes) automatically drops weather that precipitates or thunders from its pool, so it stays clear by default without any forced rule.

When both lists match, the blacklist wins (it removes weather from the whitelist pool). With all lists empty the behavior is unchanged.

The default pool `#localizedrain:natural` is **not** shipped as a tag file: a data pack may define it in `data/<namespace>/tags/localizedrain/weather_type/natural.json`. Once defined, every biome without an explicit whitelist draws weather from that tag (an empty list means no natural weather by default). When the tag does not exist, built-in clear/rain/thunder is used.

### Mobile weather (`weatherMode = "mobile"`)

Apart from `weatherMode` itself, the keys below only matter in `mobile` mode:

```toml
weatherMode = "patches"
isolatedBiomeWeather = "clear"
naturalCaveStorms = false
caveStormRadius = 64
maxStormsPerDimension = 8
maxStormsPerPlayer = 2
maxManualStormsPerDimension = 16
spawnIntervalTicks = 200
spawnMinDistance = 300
spawnMaxDistance = 700
windChangeIntervalTicks = 1200
windMinSpeed = 0.02
windMaxSpeed = 0.08
despawnDistance = 1000
despawnGraceTicks = 600
manualStormRadius = 128
vanillaCommandStormRadius = 512
stormSyncIntervalTicks = 40
falloffRatio = 0.3
lightningMultiplier = 1.0
```

- `weatherMode`: `patches` / `mobile` / `vanilla`, default `patches`. An unknown value falls back to `patches` with a warning, and a change requires a game restart.
- `isolatedBiomeWeather`: weather inside biomes tagged `#localizedrain:storm_isolated`, default `clear`. `patch` keeps the biome-patch machine running for them, while `manual` keeps them clear unless a command/API sets weather.
- `naturalCaveStorms` (default false) and `caveStormRadius` (default 64, range 8–512): whether storms may spawn underground naturally, and their radius.
- `maxStormsPerDimension` (default 8, range 0–64): cap on natural storms per dimension (manual storms do not count towards it); `maxStormsPerPlayer` (default 2, range 0–16): cap on natural storms considered to belong to one player.
- `maxManualStormsPerDimension` (default 16, range 0–128): cap on manual storms per dimension (commands/API), 0 disables them. Setting the same weather type on the same area again replaces the old storm instead of stacking.
- Natural storm spawn points follow the same weather filters: a forced-clear biome, a whitelist pool without the storm type, or a blacklist entry for that type all block the spawn; a biome forced to one weather only spawns storms of that type.
- `spawnIntervalTicks` (default 200, range 20–24000): how often a natural storm spawn is attempted.
- `spawnMinDistance` / `spawnMaxDistance` (default 300 / 700): horizontal distance band from the chosen player.
- `windChangeIntervalTicks` (default 1200): how often the dimension wind takes one bounded random step — direction stays within ±30° of the current heading and speed jitters by ±0.01; `windMinSpeed` / `windMaxSpeed` (default 0.02 / 0.08 blocks per tick) clamp the speed.
- `despawnDistance` (default 1000) and `despawnGraceTicks` (default 600): a natural storm this far from every player starts fading after that grace period.
- `manualStormRadius` (default 128): default radius of manual storms created by `setWeatherAt`; `/weather storm area` also adds it to the area coverage radius.
- `vanillaCommandStormRadius` (default 512): radius of the manual storm vanilla `/weather rain|thunder` creates per player.
- Manual storms created by commands/API have zero velocity and do not drift with the wind; a storm spawned with an explicit radius keeps that radius across stage changes.
- `/weather clear`, sleeping through the night or vanilla weather clearing fires `StormRemoveEvent` (`Reason.CLEARED`) and syncs immediately.
  In `mobile` mode `/weather clear` deletes **every** storm in the dimension (natural and manual); sleeping only removes natural storms and keeps manual ones.
- `stormSyncIntervalTicks` (default 40): how often the full storm list is re-sent to clients as a correction.
- `falloffRatio` (default 0.3): width of the storm edge falloff band as a fraction of the radius.
- In multiplayer the client uses the server's `isolatedBiomeWeather` and `falloffRatio` (sent with every storm snapshot);
  local values only apply in single-player or before the first snapshot arrives.
- `lightningMultiplier` (default 1.0): lightning frequency multiplier for storms.

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

### `/weather storm` (`mobile` mode)

```
/weather storm list
/weather storm spawn <profile> [radius]
/weather storm spawn <profile> <radius> band <minY> <maxY>
/weather storm remove <id>|all
/weather storm stage <id> <index>
/weather storm advance <id>
/weather storm intensity <id> <rain> <thunder>
/weather storm wind <x> <z>
/weather storm at <pos> <profile> [radius] [duration]
/weather storm area <from> <to> <profile> [duration]
/weather storm global <profile> [radius] [duration]
```

`profile` is an id from the `localizedrain:storm_profile` registry (tab-completed; built-ins are `drizzle` / `shower` / `squall` / `thunderstorm`), and `id` is the storm UUID printed by `list`.

- `spawn` creates a manual storm centered on the executing source; when `radius` is omitted the profile's `radius` range is used. `band <minY> <maxY>` gives the storm an explicit vertical range (caves / high altitude).
- `at` / `area` / `global`: `area` centers on the midpoint of the two corners with radius equal to half the horizontal diagonal plus `manualStormRadius`; `global` spawns one storm per player (radius defaults to `vanillaCommandStormRadius`); when `duration` is omitted the profile's stages run their full course.
- `remove all` deletes every storm in the dimension (manual storms included); `remove <id>` deletes one.
- `intensity` overrides the current stage's rain/thunder levels (0–1); `advance` moves to the next stage; `stage` jumps to the given index (0-based); `wind` overwrites the dimension wind.

In `mobile` mode the existing `/weather clear|rain|thunder` also routes through the storm system: `clear` deletes every storm in the dimension (natural and manual), while `rain` spawns a manual storm with radius `vanillaCommandStormRadius` around each player and `thunder` does the same with a thunder type.
The `storm` subtree only affects weather in `mobile` mode; in the other modes it can still create or delete storm data, but weather is decided by the active mode.

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

### Mobile weather (`mobile` mode)

Storm profiles live in the data-pack registry `localizedrain:storm_profile`; the JSON goes to `data/<namespace>/storm_profile/<name>.json`:

```json
{
  "weight": 20,
  "natural": true,
  "speed_multiplier": 1.0,
  "radius": [150, 300],
  "vertical": "surface",
  "stages": [
    {
      "type": "localizedrain:rain",
      "min_ticks": 4000,
      "max_ticks": 8000,
      "rain": [0.4, 0.8],
      "thunder": [0.0, 0.0],
      "radius_scale": [1.0, 1.0]
    }
  ],
  "environment": {
    "stage_speed_from_moisture": true,
    "moisture_gain": 0.002,
    "moisture_decay": 0.001
  }
}
```

- `weight` (default 10) is the natural spawn weight; `natural` (default true) decides whether the profile joins the natural pool.
- `speed_multiplier` (default 1.0) scales how fast the storm is carried by the wind.
- `radius` is a two-element `[min, max]` array (default `[150, 300]`).
- `vertical` is `surface`, `band`, or `column`. All three use the same horizontal circle and `minY`–`maxY` range; only `surface` additionally requires sky visibility, while `band` / `column` are meant for cave and high-altitude storms.
- `stages` needs at least one entry: `type` (weather type id), `min_ticks` / `max_ticks` (default 4000 / 8000), `rain` / `thunder` (intensity ranges, default `[0.4, 0.8]` / `[0, 0]`), `radius_scale` (radius multiplier range, default `[1, 1]`), plus optional `advance_when_moisture` / `die_when_moisture`.
- `environment`: `stage_speed_from_moisture` (default true), `moisture_gain` (default 0.002), `moisture_decay` (default 0.001).

Built-in profiles: `drizzle`, `shower`, `squall`, `thunderstorm`.

Queries: `WeatherApi.weatherMode` / `stormsAt` / `listStorms` / `storm` / `wind` / `isStormIsolatedAt`.

Writes: `WeatherApi.spawnStorm` / `removeStorm` / `removeStorms` / `advanceStage` / `setStage` / `setVelocity` / `setIntensity` / `setWind`.

```java
StormSnapshot storm = WeatherApi.spawnStorm(level, StormSpawnSpec
        .builder(ResourceLocation.fromNamespaceAndPath("localizedrain", "thunderstorm"), Vec3.atCenterOf(pos))
        .radius(256)
        .stage(0)
        .manual(true)
        .lifetime(6000)
        .build());
```

Events: `StormSpawnEvent` (cancellable), `StormRemoveEvent` (`Reason` is `MANUAL` / `FINISHED` / `FAR_AWAY` / `MISSING_PROFILE` / `CLEARED`), and `StormStageChangeEvent`.

Storm-free zones: add biomes to the biome tag `#localizedrain:storm_isolated` (`data/<namespace>/tags/worldgen/biome/storm_isolated.json`) and storms inside them produce no rain, thunder or lightning, and do not darken the sky. `isolatedBiomeWeather` controls where their weather comes from: `clear` is always clear, `patch` keeps the biome-patch machine running, and `manual` is clear unless a command/API sets weather.

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
- In `mobile` mode `WeatherApi.setWeather(level, patches, ...)` (the old per-patch-key entry point) has no effect and only logs a single warning; the positional `setWeatherAt` works normally (it spawns a single-stage manual storm). Use `spawnStorm` / `removeStorms` for bulk control.
- Whether rain/snow particles of underground `BAND` storms are hidden by vanilla's `canSeeSky` has not been verified in game yet; if they are, a narrow client-side mixin will be added (already reserved by implementation plan 7.4).

## Docs

- [docs/idea.md](docs/idea.md): design goal
- [docs/storm-system.md](docs/storm-system.md): mobile weather system (internal reference: architecture, data model, config, commands, API, sync and known limitations)
- [docs/release-1.0.0-preview.md](docs/release-1.0.0-preview.md): 1.0.0-preview release notes (mobile weather system, filter rules, storm consistency fixes)

## License

MIT License, see [LICENSE](LICENSE). `mod_license` in `gradle.properties` is `MIT` and is written into `neoforge.mods.toml` in the built jar.

This repository does not contain Xaero's Minimap / XaeroLib jars, nor any third-party mod binaries.

# Localized Rain

[简体中文](README.md) | English

Localized Rain is a weather mod for Minecraft 1.21.1 / NeoForge.

<br />

## What does it do?

Vanilla Minecraft weather is always triggered at random and applies to the entire dimension, which makes little sense. This mod completely rebuilds the weather system so that weather is localized to an area.

<br />

## Three weather modes

`weatherMode` picks one of three modes. The default is `patches`; you can switch it in the config file.

| Mode      | Description                                                                                                             |
| --------- | ----------------------------------------------------------------------------------------------------------------------- |
| `patches` | 3D biome-patch weather: every connected region of the same biome (including caves, floating islands and other 3D structures) gets its own independent weather state |
| `mobile`  | Mobile weather system: weather is carried by drifting storms, each a horizontal circle plus a vertical range, evolving according to the state stored in the storm itself                          |
| `vanilla` | Disables this feature of the mod and keeps vanilla weather                                                                |

The three modes are mutually exclusive. Each keeps its own save data, and no state is migrated when you switch.

## Patch mode (`patches`)

- Biome patches in loaded chunks are advanced in batches every `weatherSimulationIntervalTicks` (20 ticks by default); weather in unloaded areas is frozen.
- Each patch has its own clear timer, rain timer, thunder delay and thunder timer, with values drawn from the configured tick ranges.
- Rain and thunder levels ease at 0.01/tick, so sky and rain sound transition over roughly 5 seconds when moving between patches instead of snapping.
- Custom weather types are supported: a type registered in `localizedrain:weather_type` only joins natural cycling if it is added to the natural-pool tag `#localizedrain:natural`, or written into the whitelist of the corresponding biome (see the API section).
- Biomes without precipitation (vanilla Nether/End biomes, or any biome with `Biome#hasPrecipitation()==false`) stay clear by default.

## Mobile mode (`mobile`)

Weather is carried by **storms**: each storm is a horizontal circle plus a vertical range, drifts with the wind field and evolves through the stages of a profile.

- Storm profiles live in the datapack registry `localizedrain:storm_profile`. Five profiles are built in:
  `drizzle` / `shower` / `squall` / `thunderstorm` (natural, `surface`) and
  `cave_rain` (`band`, non-natural, for dimensions without sky light or with a ceiling).
  Custom profiles go to `data/<pack namespace>/localizedrain/storm_profile/<id>.json`: NeoForge prefixes the
  directory with the registry namespace too, so the path contains `localizedrain` twice; the file's namespace
  becomes the namespace of the profile id.
- The parameters describe spawn weight, whether the profile spawns naturally, movement speed multiplier, radius, vertical mode (`SURFACE` / `BAND` / `COLUMN`), as well as stage types, rain/thunder intensity, stage durations and environmental evolution parameters.
- Each dimension has its own wind field; the wind direction performs a bounded random walk every `windChangeIntervalTicks`.
- Moisture and temperature decide how fast a storm advances, whether it enters the next stage early, or dies. Natural spawning obeys the weather filters: a biome that cannot produce a weather type never spawns that type of storm, but storms that already exist are unaffected when they drift over a filtered biome.
- The client is driven by full snapshots (corrected every `stormSyncIntervalTicks`, pushed immediately on spawn/remove/stage change);
  `isolatedBiomeWeather` and `falloffRatio` are delivered with these snapshots, so on a server the server's values win.
- Lightning is generated with a probability based on the storm's thunder level and `lightningMultiplier`; vanilla difficulty only affects the skeleton-horse trap, which replaces the damaging bolt with a `visualOnly` one.
- Forced weather means something different in mobile mode: `weatherBiomeOverrides` only filters natural storm spawn points and does not create persistent precipitation for forced rain/thunder biomes (see "Biome weather filters" below).

## Biome weather filters: whitelist / blacklist / forced weather

All three lists use the form `<biome selector>=<weather>`, for example:

```toml
weatherBiomeOverrides = ["minecraft:desert=clear"]
weatherBiomeWhitelist = ["minecraft:plains=clear", "minecraft:plains=rain"]
weatherBiomeBlacklist = ["#minecraft:is_ocean=thunder"]
```

Selectors accept a concrete biome, a `namespace:*` pattern, or a `#biome_tag`; weather accepts a type id, the short names
`clear`/`rain`/`thunder`, and (for whitelist/blacklist only) a `#weather_tag` (the default natural pool is
`#localizedrain:natural`; when the tag is absent it falls back to the built-in clear/rain/thunder).

| List                    | Semantics                                                                                          |
| ----------------------- | -------------------------------------------------------------------------------------------------- |
| `weatherBiomeOverrides` | Weather lock: the biome is forced to this weather; when several rules match the same biome, the first rule in config order wins |
| `weatherBiomeWhitelist` | Whitelist: natural cycling for the biome only draws from this weather list                          |
| `weatherBiomeBlacklist` | Blacklist: the listed weathers never occur in the biome; cycling continues among the remaining ones |

Forced weather behaves differently per mode:
- **Patch mode (`patches`)**: locks the biome's weather: natural cycling, commands and the API cannot override it, and natural storms never spawn in that biome (storms that already exist still rain when they drift over it).
- **Mobile mode (`mobile`)**: precipitation only comes from storms. Forcing a concrete weather (rain / thunder / custom type) only acts as a **natural-storm spawn-point filter** and does not create persistent precipitation in that biome; only forced `clear` blocks manual commands/API, while forced rain/thunder no longer blocks them. To get rain in a forced biome under mobile mode, spawn a storm manually (`/weather rain`, `/weather storm ...`, or the storm API).

Whitelist/blacklist only constrain natural cycling; commands can still manually set weather that has been excluded.

## Weather types and extension API

- Weather type registry: `localizedrain:weather_type`, with built-in `clear` / `rain` / `thunder`;
  other mods can register their own types (a `WeatherType` record).
- Queries live in `api.WeatherApi`: `typeAt` / `patchAt` / `isRainingAt` / `isPrecipitatingAt` /
  `isThunderingAt` / `thunderLevelAt` / `skyDarkenAt` / `patches` / `setWeather` / `setWeatherAt`.
- Storm events: `StormSpawnEvent` (cancellable), `StormStageChangeEvent`, `StormRemoveEvent`;
  spawning entry points are `StormManager` / `StormSpawnSpec`.

## Installation

1. Install NeoForge for Minecraft 1.21.1 (21.1.265 or newer).
2. Install this mod on both the server and the client.

> Client and server must run the same version (network protocol `"6"`). If they differ, the connection is not dropped,
> but you will be notified on login, the server log records a warning, and storm/weather sync silently does nothing.

## Quick start

```
# Query / set local weather
/weather rain at ~ ~ ~ 6000
/weather type localizedrain:thunder area ~-64 ~ ~-64 ~64 ~ ~64 12000
/weather clear global

# Storms in mobile mode
/weather storm list
/weather storm at ~ ~ ~ shower 200 6000
/weather storm spawn thunderstorm 300 band -40 -5
/weather storm advance <id>
/weather storm remove <id>
```

## Configuration

Config files are split into global and per-save layers:

| File | Location | Content |
| --- | --- | --- |
| `common.toml` | `config/localizedrain/` (global) | debug switches, shared by every save |
| `weather.toml` | global defaults at `config/localizedrain/weather.toml`; each save uses `<save>/serverconfig/localizedrain/weather.toml` | weather mode, simulation timings, moving-storm settings; per save |
| `filters.toml` | global defaults at `config/localizedrain/filters.toml`; each save uses `<save>/serverconfig/localizedrain/filters.toml` | weather filters (biome=weather), three root-level lists; per save |

On first launch of each save the mod copies the global `config/localizedrain/weather.toml` and `filters.toml` to that save's `<save>/serverconfig/localizedrain/` (existing files are never overwritten); edits stay independent from then on. A brand-new installation's first save may not have the global files yet: that session uses the built-in/global defaults and starts using the per-save copies from the next launch. Edit the per-save files to change an existing world. The legacy `config/localizedrain-common.toml` is split automatically on launch: weather/storm keys go to `weather.toml`, the three filter lists go to `filters.toml`, and debug keys go to `common.toml` (existing targets are skipped; the old file is kept as a backup). In existing saves a `weatherMode` that is still the default `PATCHES` is migrated once from the legacy file (a `.weatherMode-migrated` marker is written to the save directory, so setting it back to `PATCHES` later is respected); a mode you picked in a given save is never overwritten.

Main options (with defaults):

### General and patch mode

| Key                              | Default   | Description                                              |
| -------------------------------- | --------- | -------------------------------------------------------- |
| `weatherMode`                    | `patches` | `patches` / `mobile` / `vanilla`; restart required to switch |
| `weatherSimulationIntervalTicks` | `20`      | Advance interval for loaded biome patches; larger is cheaper |
| `scan3dSubstates`                | `true`    | Whether to scan 3D substates such as caves and floating islands |
| `stateRetentionDays`             | `7`       | Days to keep patch states; `0` disables time-based cleanup |
| `maxStoredStates`                | `4096`    | Maximum number of patch states in the save                |
| `maxParentEntries`               | `16384`   | Hard cap for the union-find parent table                  |
| `maxColumnScansPerTick`          | `4`       | Maximum number of newly scanned columns per tick          |
| `debugLogging`                   | `false`   | Emit patch scan / merge debug logging                      |
| `logWeatherChanges`              | `false`   | Log biome patch weather changes with position and type    |
| `logStormSpawns`                 | `true`    | Log detailed storm spawn parameters                       |

### Weather pacing (ticks)

| Key                                             | Default            |
| ----------------------------------------------- | ------------------ |
| `minClearTicks` / `maxClearTicks`               | `12000` / `168000` |
| `minRainTicks` / `maxRainTicks`                 | `12000` / `24000`  |
| `minThunderDelayTicks` / `maxThunderDelayTicks` | `12000` / `180000` |
| `minThunderTicks` / `maxThunderTicks`           | `3600` / `15600`   |

### Mobile mode

| Key                                     | Default         | Description                                        |
| --------------------------------------- | --------------- | -------------------------------------------------- |
| `isolatedBiomeWeather`                  | `clear`         | Behaviour in isolated biomes: `clear` / `patch` / `manual` |
| `naturalCaveStorms`                     | `false`         | Whether cave storms spawn naturally                |
| `caveStormRadius`                       | `64`            | Radius of natural cave storms                      |
| `maxStormsPerDimension`                 | `8`             | Natural storm limit (manual storms do not count)   |
| `maxStormsPerPlayer`                    | `2`             | Natural storm limit near each player               |
| `maxManualStormsPerDimension`           | `16`            | Manual storm limit (commands/API)                  |
| `spawnIntervalTicks`                    | `200`           | Natural spawn check interval                       |
| `spawnMinDistance` / `spawnMaxDistance` | `300` / `700`   | Distance range from the player for spawn points    |
| `windChangeIntervalTicks`               | `1200`          | Wind field change interval                         |
| `windMinSpeed` / `windMaxSpeed`         | `0.02` / `0.08` | Wind speed range (blocks/tick)                     |
| `despawnDistance`                       | `1000`          | Distance at which natural storms fade out          |
| `despawnGraceTicks`                     | `600`           | How long a storm stays far away before fading      |
| `manualStormRadius`                     | `128`           | Default radius for manual storms                   |
| `stormSyncIntervalTicks`                | `40`            | Storm snapshot correction interval                 |
| `falloffRatio`                          | `0.3`           | Width ratio of the fading edge around a storm      |
| `lightningMultiplier`                   | `1.0`           | Lightning probability multiplier; `0` disables     |

## Compatibility

- **Mods that read dimension-level weather**: this mod does not write back `rainLevel` / `thunderLevel`, so `Level#isRaining()` keeps the vanilla clear value.
  Third-party logic that depends on dimension-level checks will not see local weather; call `WeatherApi`'s position-based queries for local weather.
  Vanilla snow accumulation and cauldron filling have been restored per area through a redirect inside `tickPrecipitation`.
- **Xaero's Minimap**: with this mod and Xaero's Minimap installed, the weather info line automatically switches to local weather and updates immediately when you cross patches/storms; the bridge is pure reflection, bundles no Xaero classes, and is skipped when Xaero is absent.
- **Addon mods / mobile weather**: in mobile mode `WeatherApi.setWeather(level, patches, ...)` maps each patch centre
  to one manual storm (`CLEAR_ID` maps to a column clear). A single call processes at most
  `maxManualStormsPerDimension` patches to avoid scanning every patch in the dimension; anything beyond that is
  truncated silently. Use the position-based entry points or `spawnStorm` for exact ranges.

## Known limitations

- Client rain/snow particles render according to the overall rain level of the player's patch/storm and are not localized per block; near the edge of a rainy area you may still see rain in a neighbouring clear area.
- Biome patches are quantized to 16 blocks horizontally and 4 blocks vertically; unloaded areas are frozen and treated as clear when queried.
- Natural storms only spawn in dimensions with sky light and no ceiling; `SURFACE` manual storms are rejected in dimensions without sky light, but `BAND` / `COLUMN` cave storms can still be created manually.
- Underground `BAND` / `COLUMN` storms only rain, never strike lightning (lightning requires an opening directly above the strike point, `canSeeSky`); non-`SURFACE` thunderstorms in dimensions without sky light or with a ceiling are rejected outright.
- Stopping the server or switching saves clears the storm static caches; reloading the config resets one-shot warnings.
- In mobile mode, `weatherBiomeOverrides` forced to a concrete weather (rain/thunder/custom type) does not create precipitation by itself: the rule only filters natural storm spawn points. Spawn a storm manually for precipitation, or use patch mode.

## Debug logging

- With `debugLogging` enabled (off by default), the weather simulation prints `[LRDBG]` patch scan and union-find merge details.
- With `logWeatherChanges` enabled (off by default), every biome patch weather change (type / raining / thundering) prints one line:

  ```
  [LRDBG] weather change dim=minecraft:overworld pos=8,64,8 patch=p3:0,4,0 biome=minecraft:plains type=localizedrain:rain raining=true thundering=false rain=0.42 thunder=0.00 previous=localizedrain:clear|false|false
  ```

  `pos` is the patch center (quantized to 16 blocks horizontally), `patch` is the internal patch id, and `previous` is the previous `type|raining|thundering`.
- `logStormSpawns` (on by default) controls the detailed storm spawn log line (dimension, id, profile, position, radius, vertical range, stage, type, intensity, velocity, manual flag and lifetime):

  ```
  [localizedrain] storm spawned dim=minecraft:overworld id=... profile=localizedrain:thunderstorm x=120.5 z=-88.0 radius=269.0 vertical=SURFACE minY=48.0 maxY=320.0 stage=0/3 type=localizedrain:rain rain=0.52 thunder=0.0 velocity=0.0,0.0 manual=true lifetimeTicks=-1 stageTicks=5400.0
  ```

## Building

```powershell
$env:JAVA_HOME='C:\Program Files\Microsoft\jdk-21.0.6.7-hotspot'
.\gradlew.bat clean build --offline --console=plain
```

Artifact: `build/libs/localizedrain-<version>.jar`.

## License

MIT License; see `LICENSE` in the repository root.

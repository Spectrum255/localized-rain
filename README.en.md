# Localized Rain

[简体中文](README.md) | English

Localized Rain is a weather mod for Minecraft 1.21.1 / NeoForge.

<br />

## What does it do?

Vanilla Minecraft weather is always triggered at random and applies to the entire dimension, which makes little sense. This mod completely rebuilds the weather system so that weather is localized to an area.

<br />

## Three weather modes

`weatherMode` picks one of three modes. Newly created worlds default to `mobile` (moving storms). Switch it at runtime with `/weather mode <patches|mobile|vanilla>` (takes effect within about a second), or edit the save's `weather.toml`; both are hot-reloaded without restarting the game.

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
- Custom weather types are supported as datapack entries in `localizedrain:weather_type`: a type only joins natural cycling if it is added to the natural-pool tag `#localizedrain:natural`, or written into the whitelist of the corresponding biome (see the API section).
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
- A profile may add `"biomes": []` to restrict where it spawns. Entries accept biome tags (`#minecraft:is_taiga`), a single biome (`minecraft:desert`) or a whole namespace (`minecraft:*`). When the field is omitted the default filter applies: a `SURFACE` profile with any rain-producing stage only spawns in biomes that have precipitation; an explicit list overrides that (e.g. a sandstorm can list deserts). Cave/column profiles are not subject to the default precipitation rule.
- Storm profiles can also carry tags: `data/<namespace>/tags/localizedrain/storm_profile/<tag>.json`. The mod ships `#localizedrain:natural` as the natural spawn pool: when the tag exists (and is non-empty) its members are used, otherwise the per-profile `"natural": true` flag is used. APIs: `StormProfiles.idsInTag(level, tag)` and `StormProfiles.tagIds(level, profileId)`.
- Each dimension has its own wind field; the wind direction performs a bounded random walk every `windChangeIntervalTicks`.
- Moisture and temperature decide how fast a storm advances, whether it enters the next stage early, or dies. Natural spawning obeys the weather filters: a biome that cannot produce a weather type never spawns that type of storm, but storms that already exist are unaffected when they drift over a filtered biome.
- The client is driven by full snapshots (corrected every `stormSyncIntervalTicks`, pushed immediately on spawn/remove/stage change);
  `isolatedBiomeWeather` and `falloffRatio` are delivered with these snapshots, so on a server the server's values win.
- Lightning is generated with a probability based on the storm's thunder level and `lightningMultiplier`; vanilla difficulty only affects the skeleton-horse trap, which replaces the damaging bolt with a `visualOnly` one.
- Forced weather means something different in mobile mode: `weatherBiomeOverrides` only filters natural storm spawn points and does not create persistent precipitation for forced rain/thunder biomes (see "Biome weather filters" below).
- Natural spawning only picks points inside loaded chunks (the effective radius is the simulation distance minus 2 chunks; about 96–160 blocks at simulation distance 12) and drifts toward the player from upwind; each check rolls `naturalStormSpawnChance` (default `0.035`, roughly one storm every 4.8 minutes) to decide whether a storm actually spawns. With `stormSpawnPlacement=anywhere_loaded` it instead spawns at a random loaded chunk.

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
- Natural storms are evaluated against the profile's **initial stage type** for whitelist/blacklist (a multi-stage profile cannot bypass the filter through later stages); forced weather matches **any stage** of the profile (a thunderstorm profile starts with rain).
- Manual storm radius: `/weather storm at|spawn|global` and the `setWeatherAt` API use the profile's own `radius` range when no radius is given (e.g. `drizzle` 180–320); `/weather storm area` uses "distance from the center to the farthest corner of the area + a fixed 64 blocks" and spawns a single circular storm as an approximation. The `band <minY> <maxY>` subcommand only sets the vertical range and does not change the radius.

Whitelist/blacklist only constrain natural cycling; commands can still manually set weather that has been excluded.

## Weather types and extension API

After `/reload` the mod re-parses weather types and storm profiles, then refreshes the capability bits of existing patch states and storms (type IDs, timers, manual flags and spatial ownership are unchanged). Invalid or deleted types log a single warning instead of being silently turned into clear weather.

Weather types are a **datapack registry** `localizedrain:weather_type`, with built-in `clear` / `rain` / `thunder`
(`data/localizedrain/localizedrain/weather_type/*.json`). Any datapack can add a type under
`data/<namespace>/localizedrain/weather_type/<id>.json`:

```json
{
  "display": "Sandstorm",
  "display_key": "weather.mypack.sandstorm",
  "has_precipitation": true,
  "precipitation": "rain",
  "allows_thunder": false,
  "particle": "weatherplus:sandstorm",
  "sound": "weatherplus:weather.sandstorm",
  "extra": { "color": "#d8c080" }
}
```

- `display` / `display_key`: literal name or translation key (`display` wins; if neither is present the key is
  derived as `weather.<namespace>.<path>`).
- `has_precipitation` / `precipitation` (`none` / `rain` / `snow`) / `allows_thunder`: capability flags.
- `particle` / `sound` / `extra`: **render hints for creators**. This mod does not hardcode particles or special
  effects for custom types; it only exposes these fields so packs/mods can render them themselves.

Custom types can be used directly in storm stages, whitelist/blacklist, forced weather, `/weather type` and
`WeatherApi`. To let a type join **natural cycling**, add it to the natural-pool tag
`data/<namespace>/tags/localizedrain/weather_type/natural.json` (used when present) or to a biome whitelist.

Queries and read APIs:

- `api.WeatherApi`: `typeAt` / `patchAt` / `isRainingAt` / `isPrecipitatingAt` / `isThunderingAt` /
  `thunderLevelAt` / `skyDarkenAt` / `patches` / `setWeather` / `setWeatherAt`.
- `api.WeatherTypes`: `resolve(level, id)` / `ids(level)` / `naturalIds(level)` / `tagIds(level, tag)`; these work on
  the client too (datapack registries are synced on login).
- `api.WeatherType`: `displayName()` / `particle()` / `sound()` / `extra()`.
- Client state: `network.ClientWeatherTargets.typeKey()` and `client.ClientStormCache.storms()`.
- Storm events: `StormSpawnEvent` (cancellable), `StormStageChangeEvent`, `StormRemoveEvent`;
  spawning entry points are `StormManager` / `StormSpawnSpec`.

## Installation

1. Install NeoForge for Minecraft 1.21.1 (21.1.265 or newer).
2. Install this mod on both the server and the client.

> Client and server must run the same version (network protocol `"7"`). If they differ, the connection is not dropped,
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

| File | Location | Editable in main menu | Content |
| --- | --- | --- | --- |
| `common.toml` | `config/localizedrain/` (global) | yes | debug switches, shared by every save |
| `global-weather.toml` | `config/localizedrain/` (global) | yes | weather defaults for **new saves** (weather mode, simulation timings, moving-storm settings) |
| `global-filters.toml` | `config/localizedrain/` (global) | yes | weather filter defaults for **new saves** (biome=weather) |
| `weather.toml` | `<save>/serverconfig/localizedrain/` | no (edit in game) | weather settings of the current save; copied from `global-weather.toml` when the save is created |
| `filters.toml` | `<save>/serverconfig/localizedrain/` | no (edit in game) | weather filters of the current save; copied from `global-filters.toml` when the save is created |

The global files can be edited from the main menu (Mods → Localized Rain → Config); they define the starting values for **new worlds** and never touch existing saves. When a save is created, the mod copies `global-weather.toml` / `global-filters.toml` to `<save>/serverconfig/localizedrain/weather.toml` / `filters.toml`; edit the per-save copies in game (Server options) to change an existing world. The legacy `config/localizedrain-common.toml` is split automatically on launch: weather/storm keys go to `global-weather.toml`, the three filter lists go to `global-filters.toml`, and debug keys go to `common.toml` (existing targets are skipped; the old file is kept as a backup). In existing saves a `weatherMode` that is still the default `PATCHES` is migrated once from the legacy file (a `.weatherMode-migrated` marker is written to the save directory, so setting it back to `PATCHES` later is respected); a mode you picked in a given save is never overwritten.

### Datapacks in the config folder

Besides the built-in `data/`, datapacks can be dropped straight into the config directory:

```text
config/localizedrain/datapacks/<name>/data/<namespace>/localizedrain/storm_profile/<id>.json
config/localizedrain/datapacks/<name>/data/<namespace>/localizedrain/weather_type/<id>.json
```

- Every sub-directory is one datapack; `pack.mcmeta` is optional. Folder names may only contain `a-z`, `0-9`, `_`, `.` and `-`; other names are ignored with a warning.
- `loadConfigDatapacks` in `datapacks.toml` (default `true`) controls whether the whole folder is loaded; with `false` these packs are not added to the pack repository.
- The pack repository is assembled at startup, so adding, removing or renaming a folder needs a game restart; `/reload` only re-reads packs that are already selected.
- These packs are always enabled and sit above the built-in content, so a profile/type with the same id overrides the built-in definition.

Main options (with defaults):

### General and patch mode

| Key                              | Default   | Description                                              |
| -------------------------------- | --------- | -------------------------------------------------------- |
| `weatherMode`                    | `mobile`  | `patches` / `mobile` / `vanilla`; new worlds default to `mobile`; hot-switchable at runtime (about a second, or use `/weather mode`) |
| `weatherSimulationIntervalTicks` | `20`      | Advance interval for loaded biome patches; larger is cheaper |
| `scan3dSubstates`                | `true`    | Whether to scan 3D substates such as caves and floating islands |
| `stateRetentionDays`             | `7`       | Days to keep patch states; `0` disables time-based cleanup |
| `maxStoredStates`                | `4096`    | Maximum number of patch states in the save                |
| `maxParentEntries`               | `16384`   | Hard cap for the union-find parent table                  |
| `maxColumnScansPerTick`          | `4`       | Maximum number of newly scanned columns per tick          |
| `debugLogging`                   | `false`   | Emit patch scan / merge debug logging                      |
| `logWeatherChanges`              | `false`   | Log biome patch weather changes with position and type    |
| `logStormSpawns`                 | `false`   | Log detailed storm spawn parameters (debug, off by default) |
| `logStormSpawnsToChat`           | `false`   | Report storm spawns in chat (debug, off by default)       |

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
| `spawnMinDistance` / `spawnMaxDistance` | `300` / `700`   | Distance range from the player for spawn points (clamped to the simulation distance) |
| `naturalStormSpawnChance`               | `0.035`         | Chance that each check actually spawns a natural storm (about one per 4.8 min; 0 = off) |
| `stormSpawnPlacement`                    | `near_player`   | Where natural storms spawn: `near_player` (near a random player, per-player cap applies) or `anywhere_loaded` (any loaded chunk, per-player cap ignored) |
| `windChangeIntervalTicks`               | `1200`          | Wind field change interval                         |
| `windMinSpeed` / `windMaxSpeed`         | `0.02` / `0.08` | Wind speed range (blocks/tick)                     |
| `despawnDistance`                       | `1000`          | Distance at which natural storms fade out          |
| `despawnGraceTicks`                     | `600`           | How long a storm stays far away before fading      |
| `stormSyncIntervalTicks`                | `40`            | Storm snapshot correction interval                 |
| `falloffRatio`                          | `0.3`           | Width ratio of the fading edge around a storm      |
| `lightningMultiplier`                   | `1.0`           | Lightning probability multiplier; `0` disables     |
| `stormRadiusBase`                       | `0.0`           | Base radius (blocks) for newly spawned storms; `0` = use the profile's own `radius` range |
| `stormRadiusVariance`                   | `0.3`           | Random variation of the base radius (`0.3` = ±30%); only used when `stormRadiusBase > 0` |

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
- `logStormSpawns` (off by default) controls the detailed storm spawn log line (dimension, id, profile, position, radius, vertical range, stage, type, intensity, velocity, manual flag and lifetime):

  ```
  [localizedrain] storm spawned dim=minecraft:overworld id=... profile=localizedrain:thunderstorm x=120.5 z=-88.0 radius=269.0 vertical=SURFACE minY=48.0 maxY=320.0 stage=0/3 type=localizedrain:rain rain=0.52 thunder=0.0 velocity=0.0,0.0 manual=true lifetimeTicks=-1 stageTicks=5400.0
  ```
- `logStormSpawnsToChat` (off by default) sends every player in the dimension a chat message whenever a storm spawns, with the center (x, z), Y range, radius and weather type; turn it off if you do not want the messages.

## Building

```powershell
$env:JAVA_HOME='C:\Program Files\Microsoft\jdk-21.0.6.7-hotspot'
.\gradlew.bat clean build --offline --console=plain
```

Artifact: `build/libs/localizedrain-<version>.jar`.

## License

MIT License; see `LICENSE` in the repository root.

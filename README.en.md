# Localized Rain

[简体中文](README.md) | English

Localized Rain is a weather mod for Minecraft 1.21.1 / NeoForge.

<br />

## What does it do?

Vanilla Minecraft weather is always triggered at random and applies to the entire dimension, which makes little sense. This mod completely rebuilds the weather system so that weather only applies within a range.

<br />

## Three weather modes

The mod adds two new weather modes on top of vanilla weather; pick one with `weatherMode` in the config file.

| Mode      | Description                                                                                                                        |
| --------- | ---------------------------------------------------------------------------------------------------------------------------------- |
| `patches` | 3D biome-patch weather: every connected region of the same biome (including caves, floating islands and other 3D structures) gets its own independent weather state |
| `mobile`  | Moving storm weather: weather is carried by drifting storms (a horizontal circle plus a vertical range), evolving according to the state stored in the storm itself |
| `vanilla` | Disables the mod's new weather modes; weather is dimension-wide again                                                               |

The mod defaults to the moving storm mode. While playing, switch with `/weather mode <patches|mobile|vanilla>` (takes effect within about a second), or edit the current save's `weather.toml`. After changing the mode, the weather state that belonged to the previous mode is saved and is reloaded the next time you switch back to that mode.

## Patch mode (`patches`)

- Biome patches in loaded chunks are advanced in batches every `weatherSimulationIntervalTicks` (20 ticks by default); weather in unloaded areas is frozen.
- Each patch has its own clear timer, rain timer, thunder delay and thunder timer, with values drawn from the configured tick ranges.
- Weather transitions are adjustable: by default rain and thunder levels ease at 0.01/tick, so sky and rain sound transition over roughly 5 seconds when crossing biome borders.
- Custom weather types are supported: a type only joins natural cycling if it is added to the natural-pool tag `#localizedrain:natural`, or written into the whitelist of the corresponding biome (see "Custom datapacks").
- Biomes without precipitation (vanilla Nether/End biomes, or any biome with `Biome#hasPrecipitation()==false`) stay clear by default.

## Mobile mode (`mobile`)

- In this mode, weather is carried by **storms**: each storm is a horizontal circle plus a vertical range, drifts with the wind field and evolves through the stages of a profile.

- Storm profiles are registered as the datapack registry `localizedrain:storm_profile` (see "Custom datapacks"). The mod ships some basic profiles, including
  `drizzle` / `shower` / `squall` / `thunderstorm` (natural, `surface`),
  `cave_rain` (`band`, non-natural, for dimensions without sky light or with a ceiling), and the 9 profiles described in "Built-in weather presets".

- The `biomes` parameter restricts where a storm may spawn; when omitted, the default filter applies: a `SURFACE` profile with precipitation only spawns in biomes that have precipitation. When an explicit biome list is given, spawning follows that list exactly (for example a sandstorm may list deserts). Cave/column profiles are not subject to the default precipitation rule.
  A biome that cannot produce a weather type never spawns that type of storm, but storms that already exist are unaffected when they drift over a filtered biome.

- `/weather thunder` also matches custom types that are not `thunder` but have `allows_thunder=true`
  (for example a thunder-capable type defined by another datapack); `/weather rain` likewise matches any stage whose precipitation is rain, without overriding the profile's own appearance.

- Each dimension has its own wind field; every `windChangeIntervalTicks` it adjusts once: the direction starts random
  and then fluctuates around its current value, while the speed starts at the initial value and then fluctuates
  around its current value. If the current speed drifts more than `windSpeedDeviationLimit` away from the initial
  value, fluctuation pauses and the speed is pulled back by `windReturnRate` per step before fluctuating again.
  The default initial speed is 10 blocks/s (further multiplied by the profile's `speed_multiplier`).

- Moisture and temperature decide how fast a storm advances, whether it enters the next stage early, or dies.

- Lightning is generated with a probability based on the storm's thunder level and `lightningMultiplier`; the same setting also affects the skeleton-horse trap.

- Natural spawning only picks points inside **loaded chunks** (the effective radius is the simulation distance minus 2 chunks; about 96–160 blocks at simulation distance 12) and drifts toward the player from upwind; each check rolls `naturalStormSpawnChance` (default `0.035`, roughly one storm every 4.8 minutes) to decide whether a storm actually spawns. Storms spawn within a certain distance of players by default; with `stormSpawnPlacement=anywhere_loaded` they spawn in any loaded chunk instead.

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
| `weatherBiomeOverrides` | Weather lock: the biome is locked to this weather; when several rules match the same biome, the first rule in config order wins |
| `weatherBiomeWhitelist` | Whitelist: natural cycling for the biome only draws from this weather list                          |
| `weatherBiomeBlacklist` | Blacklist: the listed weathers never occur in the biome; cycling continues among the remaining ones |

The weather lock behaves differently per mode:

- **Patch mode (`patches`)**: locks the biome's weather; natural cycling, commands and the API cannot override it, and natural storms never spawn in that biome (storms that already exist still rain when they drift over it).
- **Mobile mode (`mobile`)**: precipitation only comes from storms. Forcing a concrete weather (rain / thunder / custom type) only acts as a **natural-storm spawn-point filter** and does not create persistent precipitation in that biome; only forced `clear` blocks manual commands/API. To get rain in a forced biome under mobile mode, spawn a storm manually (`/weather rain`, `/weather storm ...`, or the storm API).
- Whitelist/blacklist are evaluated against the profile's **initial stage type** (a multi-stage profile cannot bypass the filter through later stages); forced weather matches **any stage** of the profile (a thunderstorm profile starts with rain).
- Manual storm radius: `/weather storm at|spawn|global` and the `setWeatherAt` API use the profile's own `radius` range when no radius is given (e.g. `drizzle` 180–320); `/weather storm area` uses "distance from the center to the farthest corner of the area + a fixed 64 blocks" and spawns a single circular storm as an approximation. The `band <minY> <maxY>` subcommand only sets the vertical range and does not change the radius.

Whitelist/blacklist only constrain natural cycling; commands can still manually set weather that has been excluded.

## Built-in weather presets

Besides `clear` / `rain` / `thunder`, the separate built-in expansion pack provides 9 extra weather types and their own storm profiles. They use the same registries as datapack content and can be referenced directly by `/weather type`, storm profiles, whitelist/blacklist and `WeatherApi`.

| Weather type              | Appearance                                              | Dimension | Natural profile (weight) |
| ------------------------- | ------------------------------------------------------- | --------- | ------------------------ |
| `localizedrain:snow`      | Snowfall (snowflake particles)                           | Skylit    | `snow` (14)              |
| `localizedrain:blizzard`  | Blizzard: dense snowflakes + cold fog + freezing damage + slower movement | Skylit | `blizzard` (8, 3 stages) |
| `localizedrain:sandstorm` | Sandstorm: white-ash particles + dust fog + damage + slower movement | Skylit | `sandstorm` (8, 3 stages) |
| `localizedrain:fog`       | Thick fog: low-density cloud particles + grey fog        | Skylit    | `fog` (12)               |
| `localizedrain:overcast`  | Overcast: cloud particles + dark grey fog (no precipitation) | Skylit | `overcast` (18)         |
| `localizedrain:ashstorm`  | Ash storm: ash particles + dark grey fog + damage + slower movement | Nether | `ashstorm` (6, 2 stages) |
| `localizedrain:soulmist`  | Soul mist: soul particles + teal fog + slower movement   | Nether    | `soulmist` (8)           |
| `localizedrain:voidstorm` | Void storm: portal particles + purple-black fog + magic damage | End | `voidstorm` (6, 2 stages) |
| `localizedrain:voidhaze`  | Void haze: portal particles + dark fog + slower movement | End       | `voidhaze` (8)           |

<br />

- The extra presets live in the separate built-in pack `mod/localizedrain:builtin_datapacks/weather_presets`. It is registered automatically when Weather+ is absent. When Weather+ is installed, the pack is not registered at all and Weather+ provides the full upgrade; core clear/rain/thunder and core storm profiles are unaffected. Adding or removing Weather+ requires a restart; `/reload` does not toggle this pack.
- These preset weathers **do not participate in natural generation in skylit dimensions by default**. The four Nether/End profiles (ashstorm, soulmist, voidstorm, voidhaze) always take part in natural storms there — otherwise turning the switch off would leave those dimensions with no natural storms at all. To enable natural spawning for all presets, set `enableBuiltinPresets` to `true` in `weather.toml` (or the global default file `global-weather.toml`).
- `enableBuiltinPresets` only controls natural generation for the presets, not their registration or manual commands. A profile's own `natural` field always wins over the `storm_profile/natural` tag (a profile written as `false` is never pulled into the natural pool by the tag). Once Weather+ disables the built-in pack, the switch no longer affects these presets.
- Every preset weather has a storm profile with the same name. Once natural generation is enabled, mobile-mode natural storms draw from these profiles, while patch-mode weather types join biome weather cycling directly.
- The preset types also carry `skylight`: snow/blizzard/sandstorm/fog/overcast only join natural cycling in skylit dimensions, while ashstorm/soulmist/voidstorm/voidhaze only join in no-skylight dimensions, so they never leak into Overworld patch weather.
- How presets relate to datapacks (overriding, removing from the natural pool) is covered in "Custom datapacks → Presets and datapacks".

## Custom datapacks

Weather types (`localizedrain:weather_type`) and storm profiles (`localizedrain:storm_profile`) are both datapack registries: the jar's built-in content, add-on mods and external datapacks share the same registries, and definitions with the same id override each other. This section collects everything related to datapacks.

### Where datapacks go and how they are enabled

Besides the mod's own `data/`, datapacks can also be dropped straight into the config directory, or installed as a normal datapack:

```text
config/localizedrain/datapacks/<name>/pack.mcmeta
config/localizedrain/datapacks/<name>/data/<namespace>/localizedrain/storm_profile/<id>.json
config/localizedrain/datapacks/<name>/data/<namespace>/localizedrain/weather_type/<id>.json
```

- Every sub-directory is one datapack and **must contain `pack.mcmeta`**; a missing or broken file is ignored with a warning. The display name is read from `pack.mcmeta`'s `description`.

- Folder names may only contain `a-z`, `0-9`, `_`, `.` and `-`; other names are ignored with a warning.

- On startup the folder is scanned and every pack is registered in `config/localizedrain/datapacks.toml` (new packs default to enabled). Packs can be switched off one by one by folder name:

  ```toml
  enabled = true          # false ignores the whole datapacks folder

  [packs]
  # <display name from pack.mcmeta>
  "my-pack" = true
  ```

- These packs are registered as **server data** packs only (`SERVER_DATA`): particles, sounds and language files must come from a resource pack; a custom type's `display_key` shows the raw key when it is not translated.

- The pack repository is assembled at startup, so adding, removing or renaming a folder, or changing a switch, needs a game restart; `/reload` only re-reads packs that are already selected.

- These packs are always enabled and sit above the built-in content, so a profile/type with the same id overrides the built-in definition; the built-in `clear` capability bits are forced back to clear with a single warning.

### Weather types

Weather types are the datapack registry `localizedrain:weather_type`, with built-in `clear` / `rain` / `thunder`
(`data/localizedrain/localizedrain/weather_type/*.json`). Any datapack can add a type under
`data/<namespace>/localizedrain/weather_type/<id>.json`:

```json
{
  "display": "Sandstorm",
  "display_key": "weather.mypack.sandstorm",
  "has_precipitation": false,
  "precipitation": "none",
  "allows_thunder": false,
  "particle": "minecraft:white_ash",
  "sound": "minecraft:block.sand.break",
  "particle_count": 140,
  "sound_interval_ticks": 45,
  "visuals": {
    "fog_color": "#8a7050", "fog_near": 8, "fog_far": 64,
    "particle_motion": [0.0, -0.05, 0.0], "sky_darken": 0.25,
    "sky_color": "#c9a45c", "cloud_color": "#9a7a4a",
    "sunrise_color": "#e0a060", "star_brightness": 0.0,
    "sun_visible": false, "moon_visible": false
  },
  "effects": {
    "damage": { "amount": 0.5, "interval_ticks": 60, "only_when_exposed": true, "type": "minecraft:generic" },
    "movement_speed": 0.9,
    "ignite": { "seconds": 3, "only_when_exposed": true },
    "freeze": { "ticks": 20, "only_when_exposed": true },
    "potions": [
      { "effect": "minecraft:slowness", "duration_ticks": 100, "amplifier": 0, "ambient": true, "visible": true }
    ]
  },
  "extra": { "color": "#d8c080" }
}
```

- `display` / `display_key`: literal name or translation key (`display` wins; if neither is present the key is
  derived as `weather.<namespace>.<path>`).
- `has_precipitation` / `precipitation` (`none` / `rain` / `snow`) / `allows_thunder`: capability flags.
- `particle` / `sound` / `particle_count` (default 80, **per tick** base count) / `sound_interval_ticks` (default 40, 0 = off):
  the built-in client visuals consume them — `particle` must be a **simple particle** (`SimpleParticleType`) and is
  spawned around the player by intensity; the actual count is `particle_count × intensity`, capped at 160 per tick
  per player; `sound` is played as an ambience loop. You can also render everything yourself from `extra` if you want
  a fully custom look.
- `visuals`: `fog_color` (`#rrggbb` or `0xrrggbb`) / `fog_near` / `fog_far`, interpolated by intensity on the client.
  `particle_motion` is the initial velocity the built-in visuals use when spawning particles (three components,
  default `[0.0, -0.05, 0.0]`; `[0.0, -0.6, 0.0]` gives visible rain/snow fall, `[0.03, 0.0, 0.0]` drifting dust).
  `sky_darken` (0–1, default 0) lowers the sky brightness at that position while the weather is active: it maps onto
  vanilla's 0–11 sky-darken range (`1.0` is a full thunderstorm) and is combined with rain/thunder darkening by max.
  It applies in both patch and mobile modes, and because it changes sky brightness it affects mob spawning and entity
  skylight like a thunderstorm, even when the weather has no precipitation at all.
  Sky overrides: `sky_color` replaces the biome sky base color (interpolated by intensity; the day-night factor,
  rain/thunder graying and lightning flash are still applied by vanilla, and the default fog color changes with it —
  an explicit `fog_color` still wins); `cloud_color` is a cloud-color multiplier (`#ffffff` equals vanilla, darker
  tints only darken); `sunrise_color` only recolors the vanilla sunrise/sunset fan when there is one (alpha is kept,
  no fan is invented); `star_brightness` (0–1) multiplies star brightness, `0` hides the stars; `sun_visible` /
  `moon_visible` (default `true`) swap the sun/moon texture for a fully transparent one when `false`.
  These overrides only apply to `NORMAL` skies such as the Overworld (the End sky uses a separate implementation and
  never drew the vanilla sun, moon or stars); colors and stars fade in by intensity, while the sun/moon switches are
  discrete (active above 0.01 intensity).
- `effects`: `damage` (`amount` / `interval_ticks` / `only_when_exposed` / `type` damage type id, default
  `minecraft:generic`; intervals below 10 are raised to 10 because vanilla has a 10-tick hurt cooldown),
  `movement_speed` (speed multiplier), `ignite` (`seconds` of fire, never shortens a longer burn),
  `freeze` (`ticks` of powder-snow freeze added per tick) and `potions` (list of `effect` / `duration_ticks`
  (default 100) / `amplifier` (default 0) / `ambient` (default true) / `visible` (default true)).
  Every sub-effect supports `only_when_exposed` (default true), which requires the player to see the sky;
  potions are refreshed while the weather is active, so use a duration longer than the refresh interval —
  they expire naturally after leaving the weather.
  In patch mode it applies to players inside the weather patch; in mobile mode it applies to players **covered by the
  dominant storm**; leaving the area restores speed and resets the damage timer, and switching modes never leaves stacked modifiers.
- `effects.attributes` (optional, 2.0.0-alpha): list of attribute modifiers
  `[{ "attribute": "minecraft:generic.movement_speed", "amount": 0.2, "operation": "add_multiplied_base", "only_when_exposed": true }]`.
  `operation` is `add_value` / `add_multiplied_base` / `add_multiplied_total` (default `add_value`). Attributes are resolved by id at
  runtime and may come from vanilla or any registered mod; unknown ids are skipped with a single warning. Entries on the same attribute
  and operation are summed per layer, with at most 16 modifier groups per player, and everything is removed when the weather ends, the
  player leaves the area, dies, or the mode switches. `only_when_exposed=true` requires the player to see the sky.
  `movement_speed` stays as a convenience field; when both are present the stronger one wins. Requires
  `[compat] effectsApplyAttributes` (default `true`); disabling it only turns off the attribute part. With attribute mods such as
  Apothic Attributes, derived stats (attack damage, crit, etc.) are computed by that mod — this mod only writes attributes registered in
  `BuiltInRegistries.ATTRIBUTE`.
- `requires_skylight` (default `false`): when `true` the type only appears in **skylit** dimensions (natural pools and
  forced weather skip it); in no-skylight dimensions it can still be set explicitly with `/weather type`.
  Legacy field, equivalent to `skylight: required`.
- `skylight` (default `any`): `any` / `required` (skylit dimensions only) / `forbidden` (no-skylight dimensions only).
  Natural pools, whitelists and forced weather all filter by the current dimension; the built-in presets use it to keep
  Nether/End weather out of the Overworld.
- `extra`: free-form JSON kept as-is.
- `lifecycle` (optional): `{ "ramp_in_ticks": 200, "ramp_out_ticks": 200 }` makes patch-mode rain/thunder intensity
  ramp up/down linearly instead of switching instantly; `0` disables one side. Only applies while `patchLifecycle`
  in `[patches]` is `true`; manually set, forced and locked-clear patches are unaffected.
- `next` (optional): `[ { "type": "minecraft:rain", "weight": 3, "min_biome_temperature": 0.4 }, ... ]` lets patch mode
  pick the next type by weight when the current one ends; supports `min/max_biome_temperature` and `biome_tag`
  conditions. Without a matching entry it falls back to the patch's candidate pool; only active while
  `patchWeatherEvolution` is `true`, and the target must still be in that pool (whitelist/blacklist/forced weather
  already filtered it).

Custom types can be used directly in storm stages, whitelist/blacklist, forced weather, `/weather type` and `WeatherApi`.
To let a type join **natural cycling**, add it to the natural-pool tag `data/<namespace>/tags/localizedrain/weather_type/natural.json`
(used when present) or to the whitelist of the corresponding biome.

### Storm profiles

Storm profiles live in `data/<datapack namespace>/localizedrain/storm_profile/<id>.json`: NeoForge prefixes the
directory with the registry namespace too, so the path contains `localizedrain` twice; the file's namespace becomes
the namespace of the profile id.

- These parameters describe spawn weight, whether the profile spawns naturally, movement speed multiplier, radius,
  vertical mode (`SURFACE` / `BAND` / `COLUMN`), as well as stage types, rain/thunder intensity, stage durations and
  environmental evolution parameters.
- A storm profile may add `"biomes": []` to restrict where it spawns. Entries accept biome tags (`#minecraft:is_taiga`),
  a single biome (`minecraft:desert`) or a whole namespace (`minecraft:*`). When omitted the default filter applies:
  a `SURFACE` profile with rain only spawns in biomes that have precipitation; an explicit list overrides that
  (e.g. a sandstorm can list deserts). Cave/column profiles are not subject to the default precipitation rule.
- Storm profiles can also carry tags: `data/<namespace>/tags/localizedrain/storm_profile/<tag>.json`. The mod ships
  `#localizedrain:natural` as the natural spawn pool: when the tag exists (and is non-empty) its members are used,
  otherwise the per-profile `"natural": true` flag is used. APIs: `StormProfiles.idsInTag(level, tag)` and
  `StormProfiles.tagIds(level, profileId)`.

### Dimensions and sky light

**Dimension compatibility**: the `dimensions` field of a storm profile accepts dimension ids, dimension type ids,
`namespace:*` and `#dimension_type_tag` (e.g. `"dimensions": ["#minecraft:is_overworld", "mypack:aether"]`); an empty
list means any dimension.
So **any registry dimension (including datapack dimensions) works as long as its dimension type has skylight and the
profile/biome rules allow it; the mod no longer hardcodes the overworld**.
Misspelled `biomes` / `dimensions` selectors are dropped with a warning; if **the whole list is invalid the profile keeps
its "restricted" flag, so it never spawns in any biome/dimension (instead of silently becoming unrestricted because the
list became empty)**.

Profiles can also set `skylight`: `any` (default) / `required` (skylit dimensions only) / `forbidden`
(no-skylight dimensions only). Built-in blizzard, sandstorm, fog and overcast are `required`; ashstorm,
soulmist, voidstorm and voidhaze are `forbidden`. Ashstorm and soulmist additionally use
`"dimensions": ["minecraft:the_nether"]` to stay in the Nether, while voidstorm and voidhaze use
`"dimensions": ["minecraft:the_end"]` to stay in the End.

No-skylight / ceiling dimensions (the Nether, custom cave dimensions): patch-mode weather keeps ticking
(precipitation no longer requires seeing the sky); mobile mode does not spawn `SURFACE` storms but does allow
**BAND storms** — a vertical band around the player's height, subject to `skylight` / `requires_skylight`.

### Presets and datapacks

Custom datapacks can override same-id types/profiles from the presets, and can remove presets from the
`#localizedrain:natural` tag so they no longer participate in natural generation. The extra presets are registered only
when Weather+ is absent; with Weather+ installed, use its `weatherplus:*` types and profiles instead.

### What data cannot express

The config GUI (driven by code-side `ModConfigSpec`/managed TOML; datapack content does not appear there — use
`/reload` after editing), arbitrary gameplay logic outside the schema, complex particle behaviour, sky boxes/shaders
and other custom rendering — those still need an add-on mod or client code.

## Weather types and extension API

After `/reload` the mod re-parses weather types and storm profiles, then refreshes the capability bits of existing patch
states and storms (type IDs, timers, manual flags and spatial ownership are unchanged); invalid or deleted types have
their capability bits cleared and log a single warning, and a manually set type that was deleted returns to clear
weather immediately.

The JSON definitions, tags, dimension and sky-light rules of weather types and storm profiles are described in
"Custom datapacks".

Queries and read APIs:

- `api.WeatherApi`: `typeAt` / `patchAt` / `isRainingAt` / `isPrecipitatingAt` / `isThunderingAt` /
  `thunderLevelAt` / `skyDarkenAt` / `patches` / `setWeather` / `setWeatherAt`.
- `api.WeatherTypes`: `resolve(level, id)` / `ids(level)` / `naturalIds(level)` / `tagIds(level, tag)`; these work on
  the client too (datapack registries are synced on login).
- `api.WeatherType`: `displayName()` / `particle()` / `sound()` / `extra()`.
- Client state: `network.ClientWeatherTargets.typeKey()` (HUD target type) and
  `client.ClientStormCache.storms()` (current storm snapshots, with type, position, radius and intensity).
- Storm events: `StormSpawnEvent` (cancellable), `StormDyingEvent` (cancellable; delays the fade and grants a 20-tick immunity window), `StormStageChangeEvent`, `StormRemoveEvent`; 2.0 adds `StormMergeEvent` / `StormSplitEvent` (cancellable);
  spawning entry points are `StormManager` / `StormSpawnSpec`.
- Storm merge/split (mobile mode): checked every 5 seconds. Storms of the same (or `typesCompatible`) type whose centers are
  closer than `(r1 + r2) x 0.6` merge; the older storm survives, position/temperature/moisture/intensity are area-weighted and
  the radius is the root sum of squares. A storm whose radius exceeds `1.4x` the profile maximum has a `25%` chance to split
  off a child on the cross-wind side (parent radius x0.62, intensity x0.9). Both can be cancelled by the corresponding event
  and are controlled by the `merge*` / `split*` keys in `[storm.lifecycle]`. After a split, parent and child receive a
  `mergeImmunityTicks` merge-immunity window (default 1200 ticks = 60 s) so a fresh split cannot be undone immediately.

## Installation

1. Install NeoForge for Minecraft 1.21.1 (21.1.265 or newer).
2. Install this mod on both the server and the client.

> Client and server must run the same version (network protocol `"10"`). If they differ, the connection is not dropped,
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

> Command semantics worth knowing:
> - `/weather clear|rain|thunder` and `/weather type` act on **the biome patch containing the command source** by default (use `at` / `area` / `global` to change the scope), so they intentionally differ from vanilla's dimension-wide weather; add `global` when you want the whole dimension.
> - `/weather storm ...` only works with `weatherMode=mobile`; other modes report an error instead of silently creating a storm that never renders.
> - `/weather type <id>` fails with a message for an unregistered id instead of silently turning the patch clear.

## Configuration

Config files are split into global and per-save layers under `config/localizedrain/` or `<save>/serverconfig/localizedrain/`, and are indexed by section:

| File                    | Location                             | Editable in main menu     | Content                                                                                                    |
| ----------------------- | ------------------------------------ | ------------------------- | ---------------------------------------------------------------------------------------------------------- |
| `common.toml`           | `config/localizedrain/` (global)     | yes                       | `[logging]` debug switches, shared by every save                                                          |
| `datapacks.toml`        | `config/localizedrain/` (global)     | no (edit the file)        | master switch and per-pack switches for config datapacks (see "Custom datapacks"); new packs are registered on startup |
| `global-weather.toml`   | `config/localizedrain/` (global)     | yes                       | weather defaults for **new saves** (same section layout as `weather.toml`)                                |
| `weather.toml`          | `<save>/serverconfig/localizedrain/` | no (edit in game)         | weather settings of the current save; copied from `global-weather.toml` when the save is created            |
| `client.toml`           | `config/localizedrain/` (global)     | yes                       | `[visual]` client transitions, indoor visuals/sound and the Xaero toggle (per client)                       |

Section layout of `weather.toml` / `global-weather.toml` (also the order used in the file):

```text
[general]          weather mode, preset switch, isolated-biome behaviour
[patches]          patch-mode weather cycle and save cleanup
[storm.spawn]      storm spawning
[storm.wind]       wind field
[storm.lifecycle]  storm lifecycle
[storm.terrain]    storm terrain / biome interaction
[storm.sync]       sync and intensity
[storm.stacking]   weather stacking
[compat]           vanilla query compatibility
[filters]          weather filters (whitelist / blacklist / forced weather)
```

`[storm]` is only a grouping index and holds no keys itself. Flat top-level keys written by older versions are moved into
their sections on startup (the original file is backed up as `*.flat.bak`); legacy `filters.toml` / `global-filters.toml`
are merged into `[filters]` and renamed to `*.merged.bak`. The old `config/localizedrain-common.toml` is still split into the
new layout (the old file is kept as a backup). In existing saves a `weatherMode` that is still the default `PATCHES` is
migrated once from the legacy file (a `.weatherMode-migrated` marker is written, so setting it back later is respected).

Main options (with defaults).

### [general]

| Key                              | Default   | Description                                              |
| -------------------------------- | --------- | -------------------------------------------------------- |
| `weatherMode`                    | `mobile`  | `patches` / `mobile` / `vanilla`; new worlds default to `mobile`; hot-switchable at runtime (about a second, or use `/weather mode`) |
| `enableBuiltinPresets`           | `false`   | Whether the 9 extra built-in weather presets take part in natural weather/natural storms; when off, skylit dimensions skip presets while the Nether/End presets still spawn; a profile's `natural` field wins over the tag |
| `isolatedBiomeWeather`           | `clear`   | Behaviour in isolated biomes: `clear` / `patch` / `manual` |

### [patches]

| Key                              | Default   | Description                                              |
| -------------------------------- | --------- | -------------------------------------------------------- |
| `minClearTicks` / `maxClearTicks` | `12000` / `168000` | Clear-weather duration range (ticks)        |
| `minRainTicks` / `maxRainTicks`   | `12000` / `24000`  | Rain duration range (ticks)                 |
| `minThunderDelayTicks` / `maxThunderDelayTicks` | `12000` / `180000` | Delay range before the next thunderstorm |
| `minThunderTicks` / `maxThunderTicks` | `3600` / `15600` | Thunderstorm duration range (ticks)       |
| `weatherSimulationIntervalTicks` | `20`      | Advance interval for loaded biome patches; larger is cheaper |
| `scan3dSubstates`                | `true`    | Whether to scan 3D substates such as caves and floating islands |
| `stateRetentionDays`             | `7`       | Days to keep patch states; `0` disables time-based cleanup |
| `maxStoredStates`                | `4096`    | Maximum number of patch states in the save                |
| `maxParentEntries`               | `16384`   | Hard cap for the union-find parent table                  |
| `maxColumnScansPerTick`          | `4`       | Maximum number of newly scanned columns per tick          |
| `patchWeatherEvolution`          | `true`    | Whether weather types evolve along their own `next` list (off: random from the pool) |
| `patchLifecycle`                 | `true`    | Whether `lifecycle` ramp-in/ramp-out affects intensity    |

### [storm.spawn]

| Key                                     | Default         | Description                                        |
| --------------------------------------- | --------------- | -------------------------------------------------- |
| `naturalCaveStorms`                     | `false`         | Whether cave storms spawn naturally                |
| `caveStormRadius`                       | `64`            | Radius of natural cave storms                      |
| `maxStormsPerDimension`                 | `8`             | Natural storm limit (manual storms do not count)   |
| `maxStormsPerPlayer`                    | `2`             | Natural storm limit near each player               |
| `maxManualStormsPerDimension`           | `16`            | Manual storm limit (commands/API)                  |
| `spawnIntervalTicks`                    | `200`           | Natural spawn check interval                       |
| `spawnMinDistance` / `spawnMaxDistance` | `300` / `700`   | Distance range from the player for spawn points (clamped to the simulation distance) |
| `naturalStormSpawnChance`               | `0.035`         | Chance that each check actually spawns a natural storm (about one per 4.8 min; 0 = off) |
| `stormSpawnPlacement`                   | `near_player`   | Where natural storms spawn: `near_player` (near a random player, per-player cap applies) or `anywhere_loaded` (any loaded chunk, per-player cap ignored) |

### [storm.wind]

| Key                                     | Default         | Description                                        |
| --------------------------------------- | --------------- | -------------------------------------------------- |
| `windChangeIntervalTicks`               | `1200`          | Wind field adjustment interval (ticks)             |
| `windInitialSpeed`                      | `10.0`          | Initial wind speed (blocks/s) and the value it returns to |
| `windSpeedVariationMin` / `windSpeedVariationMax` | `1.0` / `3.0` | Range for each speed fluctuation (blocks/s); picked randomly inside, random sign |
| `windSpeedDeviationLimit`               | `5.0`           | Force a return to the initial speed once the current value differs by more (blocks/s) |
| `windReturnRate`                        | `0.25`          | Fraction of the gap closed per adjustment while returning (0–1) |
| `windDirectionVariationMin` / `windDirectionVariationMax` | `10.0` / `30.0` | Range for each direction fluctuation (degrees); picked randomly inside, random sign |

### [storm.lifecycle] / [storm.sync]

| Key                                     | Default         | Description                                        |
| --------------------------------------- | --------------- | -------------------------------------------------- |
| `despawnDistance`                       | `1000`          | Distance at which natural storms start to despawn  |
| `despawnGraceTicks`                     | `600`           | How long a storm is far away before fading starts  |
| `stormRadiusBase`                       | `0.0`           | Base radius (blocks) of newly spawned storms; `0` uses the profile's `radius` range |
| `stormRadiusVariance`                   | `0.3`           | Randomness of the base radius (`0.3` = ±30%), only when `stormRadiusBase > 0` |
| `mergeEnabled`                          | `true`          | Merge nearby storms of the same (or compatible) weather type |
| `mergeRadiusFactor`                     | `0.6`           | Merge when center distance is below (r1 + r2) * this; `0.6` = only overlapping storms |
| `mergeCooldownTicks`                    | `200`           | Minimum ticks between two merges in the same dimension |
| `mergeManual`                           | `false`         | Whether manually spawned storms also merge          |
| `mergeImmunityTicks`                    | `1200`          | Merge immunity between parent and child after a split, in ticks; `0` = off |
| `splitEnabled`                          | `true`          | Whether oversized storms split off a child storm   |
| `splitRadiusFactor`                     | `1.4`           | A storm may split when its radius exceeds profile max radius * this |
| `splitChance`                           | `0.25`          | Chance to split on each check                      |
| `splitCooldownTicks`                    | `400`           | Minimum ticks between two splits in the same dimension |
| `stormSyncIntervalTicks`                | `40`            | Storm snapshot correction interval                 |
| `falloffRatio`                          | `0.3`           | Width of the storm edge falloff, as a fraction of the radius |
| `lightningMultiplier`                   | `1.0`           | Lightning chance multiplier; `0` disables          |

### [storm.terrain]

| Key | Default | Description |
| ------------------------------ | ------------- | --------------------------------------------------------------------------- |
| `terrainInfluence`             | `true`        | Master switch; when off, storms ignore terrain height and biome precipitation (all factors stay `1.0`) |
| `altitudeRainFalloff`          | `0.5`         | Fraction of rain lost linearly from sea level (63) to `63 + altitudeRange`; `0.5` halves rain at the top |
| `highlandThunderBoost`         | `0.25`        | Relative thunder gain over the same height range (`0.25` = +25% at the top, clamped to `1.0`) |
| `highlandSpeedBoost`           | `0.2`         | Relative movement speed gain over the same height range (`0.2` = 20% faster at the top) |
| `dryBiomeRainFactor`           | `0.35`        | Rain multiplier over biomes without precipitation (deserts, badlands, ...)  |
| `dryBiomeThunderFactor`        | `0.5`         | Thunder multiplier over biomes without precipitation                       |
| `altitudeRange`                | `128`         | Reference height range in blocks (16–512)                                  |
| `sampleIntervalTicks`          | `20`          | How often each storm resamples terrain height and biome (ticks)            |

### [storm.stacking]

| Key                        | Default | Description                                                                 |
| -------------------------- | ------- | --------------------------------------------------------------------------- |
| `compatibleWeather`        | `[]`    | Weather types allowed to stack (pairs: `<type>=<id or #tag>,...`; empty = every type is compatible, the testing default) |
| `blockedWeather`           | `[]`    | Directed blocks: `<earlier type>=<blocked later types,...>` (one-way; reverse order still stacks; empty = no extra restriction) |
| `maxStackedWeatherTypes`   | `3`     | Maximum number of weather layers at one point (1–8)                          |

### [compat]

| Key                            | Default | Description                                                                                   |
| ------------------------------ | ------- | --------------------------------------------------------------------------------------------- |
| `compatVanillaWeatherQueries`  | `false` | Make coordinate-less vanilla queries (`isRaining()` / `getRainLevel()` etc.) return the dimension-wide local maximum; off keeps vanilla values and has zero impact |
| `effectsApplyAttributes`       | `true`  | Apply the arbitrary attribute modifiers declared in `effects.attributes` (including attributes from other mods); off disables only the attribute part |

### [filters]

`filters.toml` / `global-filters.toml` are gone; the three lists now live in the `[filters]` section of the weather config (legacy files are merged on startup).

| Key                        | Default | Description                                                     |
| -------------------------- | ------- | --------------------------------------------------------------- |
| `weatherBiomeWhitelist`    | `[]`    | Whitelist: `<biome selector>=<weather>`; those biomes only get the listed weather |
| `weatherBiomeBlacklist`    | `[]`    | Blacklist: `<biome selector>=<weather>`; those weathers are removed from the biome |
| `weatherBiomeOverrides`    | `[]`    | Forced weather: `<biome selector>=<weather>`; the biome always has exactly that weather (first match wins; clear locks it clear) |

### [logging] (`common.toml`)

| Key                        | Default | Description                                                |
| -------------------------- | ------- | ---------------------------------------------------------- |
| `debugLogging`             | `false` | Emit patch scan / merge debug logging                      |
| `logWeatherChanges`        | `false` | Log biome patch weather changes with position and type     |
| `logStormSpawns`           | `false` | Log detailed storm spawn parameters (debug, off by default) |
| `logStormSpawnsToChat`     | `false` | Report storm spawns in chat (debug, off by default)        |

### [visual] client visuals (`client.toml`)

| Key | Default | Description |
| ---------------------- | ---- | ------------------------------------------------------------ |
| `weatherTransitionTicks` | `40` | Fade time (ticks) for fog/particles/sound/sky when stacked weather changes; `0` switches instantly |
| `indoorVisualFactor`   | `0.0` | Visual intensity kept while indoors (no sky above the head): fog, particle count and sky overlay multiply by it |
| `indoorSoundFactor`    | `0.35` | Ambience volume kept indoors; when `exposure < 0.5` the pitch is multiplied by `0.6` to sound muffled |
| `xaeroWeatherOverlay`  | `true` | With Xaero installed: minimap weather line plus the world-map weather overlay (experimental, auto-disabled on failure) |

Indoor detection costs at most 6 `canSeeSky` calls per frame (eye, one above, four horizontal neighbours) and yields
three exposure levels 1.0 / 0.5 / 0: open sky is outdoors, only a side window is half-outdoors, fully enclosed is
indoors. Visuals and sound use separate factors, so "heavy rain outside, almost no fog inside but still a muffled
drizzle" is representable. Particle count scales with total exposure instead of per-particle occlusion checks
(per-particle `canSeeSky` costs far more than it is worth); transitions only smooth layer changes — within one type
the intensity still follows the server value directly.
### Weather stacking (mobile mode)

In mobile-storm mode a position covered by several storms stacks **all weather types by default** (testing
behaviour), limited by `maxStackedWeatherTypes` (default 3):

- Optionally list allowed pairs in `compatibleWeather`; once entries exist, only those pairs stack.
  Compatibility is pairwise: listing B under A, or A under B, is enough; the right-hand side accepts weather type
  tags (`#tag`).
- Visuals: each layer spawns its own particles (global cap), fog colour is blended by layer intensity, while
  sky / clouds / sun & moon follow the strongest layer and fog distance takes the smallest value among layers
  (the thickest fog, with near and far planes taken from that same layer). At most two layers play ambient sound.
- Effects: movement speed uses the slowest layer, ignite/freeze use the longest duration, potions keep the strongest
  instance per effect, and periodic damage uses the largest amount (sharing one timer).
- Xaero's minimap weather line joins the layers into one line with ` / ` (up to `maxStackedWeatherTypes` layers).
- Patch mode (`weatherMode=patch`) never stacks; it keeps the single weather of the current patch.

## Weather map

Press `K` (rebindable under Controls → Localized Rain) to open the weather map:

- The centre is a 512×512 block map: every 16×16 biome patch is tinted with its dominant weather type,
  with alpha increasing with rain intensity; storms are drawn as circles scaled by their real radius,
  coloured by the type's `sky_color` (default blue when unset).
- The right panel shows the forecast and the storm list. The forecast comes from the server's
  `WeatherManager.forecastAt` (patch mode: remaining time until the current weather changes and the next type;
  mobile mode: storm stage transitions), up to 8 steps. The storm list shows type, stage, radius and rain/thunder intensity.
- Hovering a patch shows its coordinates, rain/thunder percentages, remaining time and whether it is forced by
  configuration or locked by biome rules.
- The bottom-left corner shows the current wind vector. Data is pushed on demand (at most once per 10 ticks per player)
  and stops when the screen is closed.
- Only nearby patches that actually have weather are sent. Mobile mode has no patches, so only storms are shown.
- With Xaero's World Map installed, opening the Xaero map also tries to draw the same patches and storms on top:
  this uses reflection to read Xaero's camera and scale fields, is **experimental**, and disables itself
  automatically when the version does not match (one warning in the log). It never affects Xaero itself;
  turn it off with `xaeroWeatherOverlay` in `client.toml`.
- The protocol version is now `"10"`: client and server must be updated together, otherwise login negotiation is
  rejected (`optional()` only covers a peer that does not have the channel at all).

## Compatibility

- **Mods that read weather**: the position-based `Level#isRainingAt(pos)` is taken over by this mod, so third-party calls see local weather.
  Dimension-level, coordinate-less queries (`Level#isRaining()`, `getRainLevel()`, `isThundering()`, `getThunderLevel()`)
  still return vanilla values by default (this mod does not write back `rainLevel` / `thunderLevel`). With
  `compatVanillaWeatherQueries` enabled in `weather.toml`, the server returns local intensity:
  - Frames that already carry a position: block random ticks, block/fluid scheduled ticks, block entities, entity ticks,
    precipitation ticks, neighbour updates, and chunk ticks (chunk centre).
  - Other calls fall back to the maximum anywhere in the dimension (dimension-level approximation).
  - Values match vanilla semantics: rain below 0.2 snaps to 0 (the vanilla `isRaining()` threshold), and thunder never
    exceeds rain and snaps to 0 below 0.9.
  - Mods that only check those methods (cauldron filling, crop growth, spawn checks) can therefore react to local weather.
  - When the option is off, those frames only pay one boolean check: no scans, no allocation.
  - In non-vanilla modes this mod owns the dimension-level `rainLevel` / `oRainLevel` / `thunderLevel` /
    `oThunderLevel` fields (they are zeroed every tick), so values written by other mods through
    `setRainLevel` / `setThunderLevel` are not preserved.
  - Off-thread calls (parallel chunk generation, third-party workers) do not use the proxy and see vanilla values:
    the position caches are not thread-safe and are only resolved on the server thread.
  - The proxy is **server-side only**: vanilla client queries are already driven per player (rain-level packets in patch
    mode, `ClientStormWeather` in mobile mode), so no client-side proxy is layered on top. During
    `Level#updateSkyBrightness()` the proxy is temporarily suppressed, keeping global sky brightness vanilla; per-position
    darkening still goes through this mod's own sky-light/spawn paths.
  - Use `WeatherApi.isRainingAt` / `WeatherApi.rainLevelAt` for explicit per-position checks, and
    `WeatherApi.withPosition(pos, action)` (server thread only) to give custom coordinate-based code a position scope;
    `/weather compat` inspects the effective values.
  - Hot-reload limit: after `/reload` the server uses the new definition immediately; remote clients only refresh
    same-id visual definitions when the datapack registry syncs. If particle/fog/sound changes to an existing id do not
    show up while the client stays connected, reconnect (or reload resources in single-player) to pick them up.
    The server notifies online players when the definitions change; storms whose type was hot-removed are logged once
    per dimension/type, stop occupying spawn limits, and reappear automatically once the type returns.
  - Coordinate contexts that are not covered (block placement `onPlace`/`updateShape`, entity spawning/removal,
    structure generation) still fall back to the dimension maximum; neighbour updates (`neighborChanged` and the
    redstone/comparator updates it triggers) are covered per position: only the outermost call pushes a frame, so deep
    update chains cannot exhaust the frame stack, and the position is accurate to within a few blocks. Vanilla snow
    accumulation and cauldron filling have been restored per area through a redirect inside `tickPrecipitation`.
- **Chunk ticks are positional too**: `ServerLevel#tickChunk` pushes the chunk centre as a frame, so the chunk-level
  `isRaining()` / `isThundering()` gates and the lightning roll resolve at that chunk's position; lightning placement
  itself still goes through `isRainingAt` and only lands in local rain.
- **Xaero's Minimap**: with this mod and Xaero's Minimap installed, the weather info line automatically switches to local weather and updates immediately when you cross patches/storms; the bridge is pure reflection, bundles no Xaero classes, and is skipped when Xaero is absent.
- **Addon mods / mobile weather**: in mobile mode `WeatherApi.setWeather(level, patches, ...)` maps each patch centre
  to one manual storm (`CLEAR_ID` maps to a column clear). A single call processes at most
  `maxManualStormsPerDimension` patches to avoid scanning every patch in the dimension; anything beyond that is
  truncated silently. Use the position-based entry points or `spawnStorm` for exact ranges.

## Known limitations

- Client rain/snow particles render according to the overall rain level of the player's patch/storm and are not localized per block; near the edge of a rainy area you may still see rain in a neighbouring clear area.
- Biome patches are quantized to 16 blocks horizontally and 4 blocks vertically; unloaded areas are frozen and treated as clear when queried.
- Natural storms: skylit dimensions spawn `SURFACE` storms; no-skylight / ceiling dimensions only spawn `BAND` storms, with eligible profiles decided by `skylight` and the stage type's `requires_skylight`.
- `BAND` / `COLUMN` storms can strike lightning in no-skylight dimensions; skylit dimensions still require an opening directly above the strike point (`canSeeSky`).
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

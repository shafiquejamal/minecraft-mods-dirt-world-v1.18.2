# Dirt World

`Dirt World` is a Minecraft Forge `1.18.2` mod written in Kotlin. It periodically converts terrain around players in the Overworld, Nether, and End into dirt while preserving a small set of blocks and critical structures.

## Current Behavior

- Every 100 in-game ticks, loaded chunks within 4 chunks of each player are queued for conversion.
- Full blocks in queued chunks are converted to dirt in small batches to keep the game responsive.
- Underwater full blocks are converted to dirt without replacing water or lava blocks.
- Player-placed full blocks in the conversion radius can be converted on later passes.
- The conversion applies in:
  - Overworld
  - Nether
  - End

## Blocks Preserved From Conversion

- `minecraft:bedrock`
- `minecraft:dirt`
- `minecraft:gravel`
- `minecraft:grass`
- `minecraft:snow`
- `minecraft:water`
- `minecraft:lava`
- `minecraft:end_portal`
- vanilla flowers, saplings, and other small plant/non-full blocks

## Structures And Special Cases Preserved

- Villages stay intact.
- Pillager outposts stay intact.
- Strongholds stay intact.
- The main End obsidian pillars stay intact.
- The End exit portal stays intact.

## Structures Not Preserved

These are intended to be converted into dirt as part of world generation:

- mineshafts
- dungeons
- ruined portals
- shipwrecks
- nether fortresses
- bastions
- end cities

## Mob And World Goals

The design target for this mod is:

- wandering traders should still spawn
- villagers should still spawn in the overworld
- piglins and magma cubes should still spawn in the Nether
- endermen and the Ender Dragon should still spawn in the End

## Client Popup

When a player joins a world with the mod, the client shows this popup message:

`All the blocks are turning into dirt. Place a new block to restore life. But that block cannot be dirt.`

## Build

Requirements:

- Java `17`
- Minecraft `1.18.2`
- Forge `40.3.12`

Build the mod:

```bash
./gradlew build
```

Run the dev client:

```bash
./gradlew runClient
```

Run the dev server:

```bash
./gradlew runServer
```

## Notes

- First world generation is slower than vanilla because generated chunks are rewritten block-by-block over multiple ticks.
- The current built jar is produced under `build/libs/`.
- Mod ID: `dirt_world`
- Package: `com.github.shafiquejamal.dirtworld`

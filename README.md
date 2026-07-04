# Dirt World

`Dirt World` is a Minecraft Forge `1.18.2` mod written in Kotlin. It converts newly generated terrain in the Overworld, Nether, and End into dirt while preserving a small set of blocks and critical structures.

## Current Behavior

- Newly generated non-air blocks with at least one visible face are converted to dirt.
- Fully enclosed blocks stay unchanged during initial chunk conversion.
- Player-placed blocks do not get re-converted after a chunk has already been processed.
- The conversion applies in:
  - Overworld
  - Nether
  - End

## Blocks Preserved From Conversion

- `minecraft:bedrock`
- `minecraft:dirt`
- `minecraft:gravel`
- `minecraft:water`
- `minecraft:lava`
- `minecraft:end_portal`

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

- First world generation is much slower than vanilla because generated chunks are rewritten block-by-block.
- The current built jar is produced under `build/libs/`.
- Mod ID: `dirt_world`
- Package: `com.github.shafiquejamal.dirtworld`

# AGENTS.md

## Scope

- This repository is a Minecraft Forge `1.18.2` mod written in Kotlin.
- The mod name is `Dirt World`.
- The mod ID / resource namespace is `dirt_world`.
- The Java package currently used by the code is `com.github.shafiquejamal.dirtworld`.

## Build And Verification

- Use Java `17` for Gradle and Minecraft tasks.
- Primary verification command:

```bash
./gradlew build
```

- Useful local run commands:

```bash
./gradlew runClient
./gradlew runServer
```

- Do not invent other repo scripts unless they are added to the project.

## Project Layout

- Main mod entrypoint: `src/main/kotlin/com/github/shafiquejamal/dirtworld/DirtWorldMod.kt`
- World conversion logic: `src/main/kotlin/com/github/shafiquejamal/dirtworld/world/`
- Client-only UI logic: `src/main/kotlin/com/github/shafiquejamal/dirtworld/client/`
- Mod metadata: `src/main/resources/META-INF/mods.toml`
- Asset namespace root: `src/main/resources/assets/dirt_world/`
- Data namespace root: `src/main/resources/data/dirt_world/`

## Current Mod Behavior

- Converts newly generated non-air blocks to dirt in the Overworld, Nether, and End.
- Leaves these blocks unchanged during conversion:
  - `minecraft:dirt`
  - `minecraft:bedrock`
  - `minecraft:gravel`
  - `minecraft:water`
  - `minecraft:lava`
  - `minecraft:end_portal`
- Preserves villages, pillager outposts, and strongholds instead of turning them into dirt.
- Preserves the main End obsidian pillars and the End exit portal so the Ender Dragon flow still works.
- Player-placed blocks do not get re-converted after a chunk has already been processed.
- Shows a client popup when entering a world with the mod.

## Editing Rules

- Keep the mod ID as `dirt_world` unless the user explicitly asks to rename it.
- Keep resource paths, language keys, and registered IDs aligned with the `dirt_world` namespace.
- Prefer small, direct Kotlin changes over adding unnecessary abstractions.
- Keep client-only code under `client/`.
- After each code change, test the changes, bump the minor or patch version number, then commit the changes and push them to GitHub.
- When changing world conversion behavior, verify both dedicated server startup and new-world chunk generation behavior. Do not treat a successful build as sufficient validation.
- Preserve the requested exception rules for villages, pillager outposts, strongholds, the End exit portal, and the main End obsidian pillars unless the user explicitly changes them.

## Instruction Feedback

- When a user instruction is clear enough to execute but is materially ambiguous, under-specified, or likely to cause the wrong implementation, provide brief feedback on how the instruction could be written more precisely.
- Give this feedback after completing the requested task unless the ambiguity blocks progress.
- Keep the feedback concise: explain the ambiguity and provide one improved example phrasing.
- Do not give instruction-writing feedback for minor wording issues that do not affect implementation.

## Notes

- This project uses Kotlin for Forge on Forge `40.3.12`.
- World conversion is intended for newly generated chunks; existing processed chunks should remain stable after players place new blocks.

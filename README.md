# Xray_SeedEstimate
## Version
Currently tested with ONLY java 26.3

## Setup

For setup instructions, please see the [Fabric Documentation page](https://docs.fabricmc.net/develop/getting-started/creating-a-project#setting-up) related to the IDE that you are using.

## Build 
run `./gradlew build` in the home repository to get the .jar file in 
`/build/libs/`
## SeedMap ore searches

Use `/xray on` and `/xray off` to show or hide the wireframes.

The client command `/xray ore <type>` submits the hard-coded seed `-3048587972680030099`, Java 26.3, and your current block coordinates to the SeedMap Ore Finder. Supported types are `diamond`, `netherite`, `gold`, `iron`, `copper`, `redstone`, and `lapis`. Searches request up to 24 results from the site and print the results in chat. Results are estimates and may differ from the world. Each unmined returned coordinate is marked with a bright magenta, always-on-top wireframe until the next search or until you leave that world. Breaking one of those exact blocks removes its wireframe and adds its dimension and block coordinates to `config/xray_seedestimate-mined-ores.txt`; future searches continue to list blacklisted results in chat but never highlight them. Delete an entry from that file to allow its coordinate to be highlighted again. Existing `xray-temp-mined-ores.txt` and `xray-temp.properties` files are migrated to the new names automatically.

Ore searches require Chromium to be installed separately. On the first search, the mod creates `config/xray_seedestimate.properties` in the Minecraft game directory. Set `chromiumPath` in that file to the Chromium executable path, for example `chromiumPath=/usr/bin/chromium`. The mod also checks common Linux Chromium paths automatically. The browser is launched headlessly only when an ore command is run; no browser or browser automation runtime is bundled with the mod.

## Disclaimer
Most of the code was written by an AI Agent, This is a for fun project just to test limits, The author does not endorse using this mod on public server where it is against the rules to Xray to gain advantage over other players.

## License

This template is available under the CC0 license. Feel free to learn from it and incorporate it in your own projects.

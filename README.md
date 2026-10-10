# TFMC Core

> Shared roleplay features and server systems for TF-Minecraft.

TFMC Core brings together small gameplay features and server rules. It supports personalized items and activity records, alongside custom drops and crafting-station interactions.

These features give other TF-Minecraft plugins common building blocks while also adding everyday interactions players can use directly.

## Features

- **Personalized items** — lorestones add descriptive text (or `clear` it all) and namestones rename an item through an in-game prompt. A plain name is followed by a clickable colour palette; names typed with `&` codes skip it.
- **Animal whistles** — highlight nearby supported animals to help players locate them.
- **Shared gameplay rules** — custom drop handling and station interactions connect everyday world actions to server content.
- **Player utilities** — shared player commands and resource-pack delivery support everyday server use.
- **Book appearance** — optionally hides the default glint on signed books through ProtocolLib.
- **Cross-plugin statistics** — records supported vehicle, character, crafting, skill, and faction events for staff queries.

TFMC Core works alongside the server's specialist plugins, connecting their systems with the smaller details that make the roleplay world feel consistent.

## Documentation

[Project documentation](https://github.com/TF-Minecraft/Docs/blob/main/projects/TFMCCore/README.md)

Technical documentation is maintained in [TF-Minecraft/Docs](https://github.com/TF-Minecraft/Docs).

## Tests

With Java 21 and the pinned plugin dependencies installed (see the build workflow), run:

```sh
mvn -B --no-transfer-progress clean verify
```

JUnit 5 and Mockito tests cover drops, statistics storage, stone and whistle
configuration, player commands, signed-book appearance, the Xaero fair-play listener
and resource-pack compaction and delivery, with server and plugin APIs mocked.
Surefire writes test results to `target/surefire-reports/`. CI runs verification
on pushes and pull requests to `main` and uploads those reports;
JaCoCo writes HTML/XML to `target/site/jacoco/` and enforces 100% production
line coverage in `verify`, with no class or package exclusions. CI also uploads
coverage reports. The gate does not require 100% branch coverage. The suite does not start a live Paper server.

## License

Copyright (c) 2026 TF-Minecraft contributors.

TF-Minecraft-authored material in this repository is licensed under the
[Artistic License 2.0](LICENSE). Third-party dependencies and bundled material
retain their own licenses.

# TFMC Core

> Shared roleplay features and server systems for TF-Minecraft.

TFMC Core brings together small gameplay features and server rules. It supports personalized items and activity records, alongside custom drops and crafting-station interactions.

These features give other TF-Minecraft plugins common building blocks while also adding everyday interactions players can use directly.

## Features

- **Personalized items** — lorestones add descriptive text and namestones change an item's name through an in-game prompt.
- **Animal whistles** — highlight nearby supported animals to help players locate them.
- **Shared gameplay rules** — custom drop handling and station interactions connect everyday world actions to server content.
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
configuration, `/tfmc` commands, the Xaero fair-play listener and resource-pack
compaction and delivery, with server and plugin APIs mocked. CI runs the same
command on every push and pull request to `main` and uploads the Surefire reports;
JaCoCo writes HTML/XML to `target/site/jacoco/` and enforces 100% production
line coverage in `verify`, with no class or package exclusions. CI also uploads
coverage reports. The gate does not require 100% branch coverage. The suite does not start a live Paper server.

## License

Copyright (c) 2026 TF-Minecraft contributors.

TF-Minecraft-authored material in this repository is licensed under the
[Artistic License 2.0](LICENSE). Third-party dependencies and bundled material
retain their own licenses.

# TeleporterControl

A Hytale server mod that removes the teleporter limit and lets players control who can use their teleporters, all from the teleporter's own menu.

> [!NOTE]
> This isn't meant to be a mainstream mod. We built it for our own use on the [Informal Lounge Hytale server](https://informallounge.com/hytale), so features and priorities follow what our server needs. We saw no reason to keep it private though, so you're welcome to use it, fork it, and change it however you like.

## Features

- **No teleporter limit.** The Memories-based cap (2–8 teleporters) is removed.
- **No unlocking.** A teleporter's destination list shows every teleporter you can access, in every world.
- **Ownership.** The player who places a teleporter owns it. Teleporters placed before the mod was installed are given to their original placer.
- **Public or private.** Teleporters are public by default. Owners can make them private.
- **Sharing.** Owners can share private teleporters with specific players, including offline players who have joined the server before.
- **Give up ownership.** The teleporter becomes public, and the next player to open it becomes the owner.
- **Access checks.** Stepping on a pad only works if you can access both the pad and its destination. Mobs only travel between public teleporters.
- **No commands.** Everything is done in the teleporter menu, which has Destination, Permissions and Ownership tabs.
- **Admin control.** Players with the `teleportercontrol.admin` permission can use and manage every teleporter, and remove owners.

<!-- ## Screenshots -->

<!-- TODO: add screenshots to docs/screenshots/ and uncomment.
| Destination | Permissions | Ownership |
| --- | --- | --- |
| ![Destination tab](docs/screenshots/destination.png) | ![Permissions tab](docs/screenshots/permissions.png) | ![Ownership tab](docs/screenshots/ownership.png) |

![A private teleporter, as seen by someone without access](docs/screenshots/private.png)
-->

## Installing

Download the latest jar from [Releases](https://github.com/InformalLounge/hytale-teleporter-control/releases/latest).

- **Server:** put the jar in the server's `mods/` folder and restart. Players don't need to install anything.
- **Singleplayer:** put the jar in your Hytale `UserData/Mods` folder and enable **TeleporterControl** in your world's mod settings.

## Data

Ownership and sharing are stored in `access.json` in the mod's data folder, keyed by world and block position. A backup is kept as `access.json.bak`.

- Back up `access.json` together with your world.
- A teleporter moved with builder tools or a prefab paste loses its owner and becomes public.
- Renaming a world resets ownership of the teleporters in it.

## Building

Requires Java 25.

```sh
./gradlew build
```

The jar is written to `build/libs/`. The mod compiles against the Hytale server version set by `hytaleVersion` in `gradle.properties`, downloaded from Hytale's Maven repository. To build against a local server jar instead, add `-PhytaleServerJar=/path/to/HytaleServer.jar`.

Pushing a `v*` tag (e.g. `v1.0.0`) builds the mod and publishes a GitHub release. A daily workflow opens an issue when a new Hytale version is released, saying whether the mod still builds against it.

## AI usage

This mod was developed with the help of [Claude Code](https://claude.com/claude-code), Anthropic's AI coding assistant. We understand the downsides of AI and do not use AI for any creative work, but rather strictly as a tool to assist in developing our software tools. More information can be found on our [website](https://informallounge.com/dev).

## License

[MIT](LICENSE). Hytale is a trademark of Hypixel Studios. This project isn't affiliated with or endorsed by Hypixel Studios.

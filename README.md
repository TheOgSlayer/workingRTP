# JalebiRTP

Random teleport plugin for Paper/Spigot (1.16+) for the Overworld, Nether and End.

- `/rtp` opens a GUI with one button per dimension
- No safe-spot searching, the teleport always goes through
- 2 minute cooldown (configurable, `jalebirtp.bypass` skips it)
- Target world per dimension is set in `config.yml` (works with Multiverse worlds)
- `/rtp reload` reloads the config

## Build
`mvn package` -> `target/JalebiRTP.jar`, or download it from the Actions tab (Artifacts).

## Permissions
- `jalebirtp.use` (default: everyone)
- `jalebirtp.bypass` (default: op)
- `jalebirtp.reload` (default: op)

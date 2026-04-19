# HyStamina

## Documentation

- [Hytale stamina system vs Minecraft hunger system](docs/hytale-stamina-vs-minecraft.md)
- [Mod Menu handling](docs/modmenu-handling.md)

## Tagged Backups

Pushing a Git tag now triggers a release build on GitHub Actions. That workflow runs a clean Gradle build, stores the jars as workflow artifacts, and attaches the same jars to a GitHub Release for the tag.

Example:

```powershell
git tag v1.0.0
git push origin v1.0.0
```

## Setup

For setup instructions, please see the [Fabric Documentation page](https://docs.fabricmc.net/develop/getting-started/creating-a-project#setting-up) related to the IDE that you are using.

## License

This template is available under the CC0 license. Feel free to learn from it and incorporate it in your own projects.

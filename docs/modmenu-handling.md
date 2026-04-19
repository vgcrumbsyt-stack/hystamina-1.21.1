# Mod Menu handling

## Overview

HyStamina exposes its config UI through Mod Menu, but Mod Menu is treated as an optional client-side integration rather than a hard runtime dependency.

## Registration

- The Mod Menu entrypoint is declared in `src/main/resources/fabric.mod.json` under the `modmenu` entrypoint key.
- That entrypoint points to `hystamina.client.HyStaminaModMenuIntegration`.
- The integration class implements `ModMenuApi` and returns `HyStaminaConfigScreen` through `getModConfigScreenFactory()`.

This keeps the Mod Menu integration thin: Mod Menu only opens the existing HyStamina config screen, and all real config behavior remains in the shared config and client UI code.

## Dependency model

- Mod Menu is declared as `modCompileOnly("maven.modrinth:modmenu:11.0.4")` in `build.gradle.kts`.
- HyStamina does not require Mod Menu to run.
- If Mod Menu is not installed, the mod still works normally; only the Mod Menu config entry is absent.

## Config authority

The Mod Menu screen edits the same config path used elsewhere in the mod:

- Local values are read from and written to `config/hystamina.json` through `HyStaminaConfig`.
- `HyStaminaConfig.save(...)` persists the local client config and immediately updates local runtime settings.

The effective config depends on where the player is running:

### Singleplayer

- Saving from the Mod Menu screen also applies the same values as a server override when `minecraft.hasSingleplayerServer()` is true.
- This makes the integrated server use the newly saved config immediately, instead of waiting for a reconnect or reload.

### Multiplayer

- The server remains authoritative while connected.
- The client can still save local values from the screen, but active gameplay uses the server override received through HyStamina networking.
- The config screen shows a status banner when a server override is active so the player knows the visible values are currently being overridden.

## Screen behavior

`HyStaminaConfigScreen` is a custom screen owned by the mod. Current handling is:

- Category panels are rendered by the screen itself instead of delegating layout to a third-party config library.
- The settings area scrolls inside a clipped viewport.
- Footer actions stay fixed at the bottom of the screen.
- Validation and server-override notices are rendered in a separate status banner above the footer.

This means future Mod Menu changes should generally stay isolated to the entrypoint class unless the actual config UX needs to change.

## Maintenance note

If Mod Menu handling needs to change later, prefer keeping this split:

- `HyStaminaModMenuIntegration` should stay as a thin adapter.
- `HyStaminaConfigScreen` should remain the single source of truth for the UI.
- `HyStaminaConfig` and `HyStaminaNetworking` should remain the source of truth for local persistence and multiplayer/server-authoritative behavior.
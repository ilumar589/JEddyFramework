# jeddy-input

Module `dev.jeddy.input`. The event pump, device state, action mapping, input contexts, gamepads and CRPG gestures.

## Key types

- `EventPump` (main thread) → `Input` (game thread) through a lock-free ring
- `ActionMap`, `Binding`, `Rebinder`, `KeyLabels`, `Scancode`
- `CrpgGestures`: click vs drag, box selection, edge scroll, camera rotate, pan and zoom
- `VirtualCursor`, `GamepadMappings`

## Threading

`EventPump` on the main thread; everything else on the game thread.

## More

- Design: [DESIGN.md](../../docs/DESIGN.md) §12.2
- API: `./gradlew :input:javadoc`, or the Javadoc site (`./gradlew :site:javadocSite`)
- Tests: `./gradlew :input:test`

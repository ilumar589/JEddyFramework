# jeddy-ui

Module `dev.jeddy.ui`. An immediate-mode user interface in pure Java: the game declares its screens every frame as a tree of boxes and text, the module lays the tree out at the end of the frame with a flex-like algorithm (fit, grow, percent, wrap, floating), resolves hover, clicks, drags and focus against the previous frame's rectangles, and emits a draw list the renderer draws in one call. Text is signed-distance-field glyphs from fonts the cooker bakes (`.jfont` + a KTX2 atlas), so one atlas serves every size. No GL and no input dependency: the module runs in plain unit tests.

## Key types

- `Ui`: the context. `beginFrame(UiInput)`, then `box(id)`/`text(id)` with the `Box` builder (`open()`/`close()` nest), then `endFrame(UiDrawList)`. Queries: `hovered`, `active`, `clicked`, `focused`, `dragging`/`dropped`, `wantsMouse`/`wantsKeyboard`; per-widget state slots (`state(id)`); id scopes (`pushId`/`popId`) for lists
- `Box`: the reusable builder: sizing (`width`, `widthFit`, `widthGrow`, `widthPercent` and the height twins), `row`/`column`, `padding`, `gap`, `align`, `background`, `border`, `radius`, `sprite`, `clip`, `scroll`, `floating` with `anchor`/`offset`/`z`, interaction flags (`hit`, `focusable`, `dragSource`, `dropTarget`), and text (`append` of chars, ints and floats without allocating, `font`, `fontSize`, `color`, `wrap`, `textAlign`)
- `Widgets`: the built-in widget set on a `Theme`: panels, labels, buttons, check boxes, radios, sliders, drag values, text fields (`TextBuffer`), drop-downs, tabs, tree nodes, progress bars, tooltips, popups and menus, movable windows
- `UiInput`: one frame of input (pointer, buttons, wheel, keys as SDL scancodes, typed text, window size, display scale); `UiKey` the scancodes the UI reacts to
- `UiDrawList`: what a frame looks like: 10-float vertices, indices, a clip-rectangle table and commands (one per texture change); `Sprite` names a rectangle of a UI texture (plain or nine-slice)
- `FontLoader`: the `.jfont` asset loader; `dev.jeddy.asset.format.FontData` holds the metrics

`render` draws the list (`dev.jeddy.render.ui.UiRenderer`, after post-processing), `app` connects input, fonts and the clipboard (`dev.jeddy.app.ui.UiBridge`, `UiFonts`), and `samples/iso-demo` shows portraits, an inventory with drag and drop, a combat log and a pause menu built with it. [docs/learn/13-ui.md](../../docs/learn/13-ui.md) explains the layout algorithm, the interaction model and the text pipeline.

## Threading

`Ui` and `Widgets` belong to the game thread; `UiDrawList`s travel in render packets to the render thread. Nothing here allocates per frame once the context's capacities (`UiConfig`) are large enough.

## More

- Design: [DESIGN.md](../../docs/DESIGN.md) §18, D82–D84
- API: `./gradlew :ui:javadoc`, or the Javadoc site (`./gradlew :site:javadocSite`)
- Tests: `./gradlew :ui:test`; benchmark: `./gradlew :bench:jmh -Pjmh.includes=UiBench`

# Iso demo

The integration demo and performance scene of Jeddy (TASKS M10, M12-07): a six-member party in a
crypt full of skeletons. It exercises every module together: the renderer (shadows, clustered
lights, outlines, decals, particles, fog of war, post, GPU skinning), animation (idle, walk and run
blended by speed, strikes, casts, hit reactions, deaths and the skeletons' awakening, baked from
two KayKit animation libraries), physics (collision, picking, navmesh, crowd with formations,
visibility, turn-based movement range), audio (ambience, positional effects), assets (paks,
background loading) and the app loop (30 Hz simulation, interpolated rendering, RTwP pause).

![The iso demo on Mesa llvmpipe](../../docs/images/iso-demo.png)

```
./gradlew :iso-demo:run                 # cooks and packs the assets first
./gradlew :iso-demo:run --args="--help"
```

## Controls

| Input | Action |
|---|---|
| Left click | select a party member, or move the selection |
| Left drag | box select |
| Right click | move the selection (in formation when several are selected) |
| 1-6, Tab | select a member, select everyone (Shift adds) |
| G | cycle formations (wedge, box, line, column, circle) |
| F | fireball: click to cast, right click cancels (Shift keeps aiming) |
| T | turn-based movement range of the selection, with the cost to the cursor |
| WASD / arrows, screen edges | pan |
| Wheel | zoom |
| Q / E, middle drag | rotate |
| C | follow the selection |
| Space | pause (simulation stops; orders can still be given) |
| F3 | profiler overlay (CPU threads, GPU passes) |
| F12 | screenshot to `build/screenshots` |
| Esc | cancel aiming, else quit |

## Performance capture

```
./gradlew :iso-demo:run --args="--perf 600"                     # 10 minutes, rendered
./gradlew :iso-demo:run --args="--headless --perf 600"          # game thread only, no GPU
./gradlew :iso-demo:run -Pjeddy.jvmArgs="-Xlog:gc*:file=build/perf/gc.log" --args="--perf 600"
```

The perf scene has 300 animated agents, 16k extra static props and 256 point lights (4 shadowed),
and a script that tours the crypt in formation and throws a fireball every 1.5 s while the camera
orbits. After a warm-up (10 s, 600 frames and the first fireballs) it measures game-thread and
main-thread CPU per frame, the animation update, GPU time per render pass, allocation of the game,
main and job threads, and GC, and writes `build/perf/perf.md` and `perf.json` against the budgets
of DESIGN §14. `--fail-on alloc,game,animation` makes failed budgets an exit code (CI runs the headless capture), and
`--alloc-probe` adds the game thread's allocation by system. Results and the fix list of M10 are
in [docs/perf/m10.md](../../docs/perf/m10.md).

## Assets

- `assets/levels/crypt.glb`, `assets/characters/*.glb` and the animation libraries
  `assets/characters/anims/{heroes,skeletons}.glb` (each rig's nodes and the clips the demo plays)
  are built by `tools/kaykit.py` from the
  [KayKit](https://github.com/KayKit-Game-Assets) Dungeon Remastered, Adventurers and Skeletons packs by Kay
  Lousberg (CC0), fetched at pinned commits (`KAYKIT-LICENSE.txt`). The level layout is the ASCII
  map at the top of the script; node-name prefixes (`floor_`, `wall_`, `prop_`, `decor_`) drive
  collision, navmesh, camera fading and shadows (`crypt.physics.json`).
- `assets/audio/*.wav` are synthesized by `tools/sounds.py`.
- `assets/models/yard.*`, `textures/`, `materials/` and `shaders/` are the procedural assets of the
  asset pipeline checks (`tools/generate_assets.py`).

Characters stand in their bind pose: there is no animation module yet (backlog).

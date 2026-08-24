# robocode — project notes

Robocode Tank Royale bots. Engine version is pinned to **1.1.0** in `bin/robocode` (`VERSION=`);
bumping it means changing that one line.

## Running things

Always go through `bin/`:

- `bin/robocode start|stop|restart|status` — the game GUI. `start --build` re-downloads the engine.
- `bin/battle A B [--rounds N]` — headless battle, prints a scoreboard. **This is how to verify a
  bot change actually helped.** Two bots = duel, three or more = melee. Driver is `tools/Battle.java`.
- `bin/check [BotName]` — compile bots without launching the GUI. Use this to verify edits; it is
  seconds, and a bot with a compile error simply fails to appear in the GUI's bot list with no
  useful error message.
- `bin/newbot <Name>` — scaffold a bot.

Java comes from Homebrew's `openjdk` (`/opt/homebrew/opt/openjdk`), which is deliberately not on
`PATH`. The scripts locate it themselves — don't assume `java` works in a bare shell. They also
`export PATH`, and must keep doing so: the engine launches the server and every bot as its own
`java` subprocess, so a bare `java` that isn't resolvable means bots silently fail to boot.

## Layout rules

- `bots/<Name>/<Name>.java` + `bots/<Name>/<Name>.json`, and the `public class` must also be
  `<Name>`. The booter matches on the directory name; a mismatch means the bot silently won't boot.
- `bots/lib/`, `samples/`, `engine/`, and `var/` are all downloaded or generated. They are
  gitignored. Never commit a jar.
- `samples/` keeps its own `lib/` alongside the sample bot directories. Don't strip it — each bot
  is compiled with `-cp ../lib/*` relative to its own directory, so every bot root needs one.
- The GUI stores its settings in `~/Library/Application Support/Robocode Tank Royale/gui.properties`.
  `bin/robocode` merges this repo's bot directories into the `bot-directories` key there
  (format: `path,enabled,path,enabled,...`) without dropping any the user added by hand.

## API gotchas

- Angles: degrees, counter-clockwise, 0 = east. **Positive bearing = left**, so `turnLeft(bearing)`
  is what points you at a target, not `turnRight`.
- Two incompatible styles: blocking (`forward`, `turnGunLeft`) and deferred (`setForward`,
  `setTurnGunLeft` + `go()`). Mixing them in one bot produces confusing behavior. `RafeBot` uses
  deferred + `go()`; the sample bots in `samples/` mostly use blocking.
- Fields on the bot object persist across rounds. `run()` is called once per round, so reset
  per-round state at the top of it.
- The radar is mounted on the gun and the gun on the body, so both get dragged along by turns
  unless `setAdjustGunForBodyTurn(true)` / `setAdjustRadarForGunTurn(true)` are set.
- Firing costs energy; a bot that fires below its own energy dies. Guard firepower against
  `getEnergy()`.

## Reference

The engine ships an embedded booter CLI inside the GUI jar, useful for debugging why a bot won't
appear without opening the GUI:

```sh
unzip -p engine/robocode-tankroyale-gui-1.1.0.jar robocode-tankroyale-booter.jar > /tmp/booter.jar
java -jar /tmp/booter.jar dir bots      # list bot directories it recognizes
java -jar /tmp/booter.jar info bots     # parsed JSON info per bot
```


# Classic Robocode (classic/)

A second engine lives alongside Tank Royale, because the live RoboRumble leaderboard runs
on classic Robocode, not Tank Royale. Engine 1.11.1, pinned in `bin/classic` (`VERSION=`).

- `bin/classic start|stop|restart|status` — the classic GUI.
- `bin/build` — compile `classic/src/rafe/*.java` into the engine's `robots/`.
- `bin/duel <bot> <opponent...> [--rounds N]` — headless 1v1s, prints score% (the measure
  RoboRumble ranks on). **Always measure a bot change with this.** Sample bots are useless
  as a signal; use the real ones from `bin/opponents`.
- `bin/opponents` — download the benchmark ladder of real rumble bots.
- `bin/package [Bot]` — build the uploadable jar into `dist/`.

## Engine gotchas that cost real debugging time

- **Versioned robots need a wildcard in the battle roster.** A robot with `robot.version` in
  its `.properties` will NOT match a `selectedRobots` entry of `rafe.Kestrel`; it needs
  `rafe.Kestrel*`. The failure mode is a silent `Can't find 'rafe.Kestrel'` and a battle that
  runs with one robot. `bin/duel` appends the `*` automatically.
- **Robocode 1.11.1 cannot scan jar robots on JDK 22+.** `URLJarCollector.fileCache` is null,
  the NPE aborts the whole repository scan, and then *every* robot disappears, not just the
  jars. `bin/opponents` therefore unpacks opponent jars into `robots/` as loose class files.
  Never drop a `.jar` into `classic/engine/robots/`. (Running the engine on JDK 17 or 21
  would be the other fix, and is worth doing if the RoboRumble client is ever run locally,
  since that client does use jars.)
- **The engine resolves a battle roster before rescanning `robots/`.** A newly compiled bot
  is missing on the run that indexes it and present on the next one. `bin/build` runs a
  throwaway battle after compiling to absorb that.
- **Never delete `robots/robot.database` to force a refresh** — that guarantees a miss on the
  very next run, which is exactly the confusing case above.
- Bots must be **Java 17 bytecode or lower** for RoboRumble; `bin/build` uses `--release 17`.
- Package the nested classes. `Bot$Wave.class` missing from the jar runs fine locally (the
  engine reads `robots/`) and dies instantly on someone else's machine.

## Classic API gotchas

- Angles are **clockwise from north**, the opposite convention from Tank Royale. Work in
  radians with `getHeadingRadians()` / `setTurnRightRadians()`.
- `absoluteBearing` is `atan2(dx, dy)` — x first — because of that convention.
- `onScannedRobot` gives bearing and distance, not coordinates. Project to get a point.
- A robot instance is **recreated every round**. Anything that must learn across a battle has
  to be `static`.

## Kestrel: things learned the hard way

- **Watch for `is not stopping` in battle logs.** A robot that is still computing when a round
  ends cannot be interrupted, and the engine forfeits the battle. It was caused by compounding
  loop bounds, not one runaway loop: a 500-tick prediction whose every tick ran a 200-step wall
  smoothing loop. Keep `predictPosition` and `wallSmoothing` cheap, and re-check them after any
  change to the movement.
- **`bin/bench` reports FAILED for a matchup that produced no results file.** Read the log
  before assuming it is a harness problem; the first time it happened it was our bug.
- **Measure before believing anything.** The noise floor is ±1.3 at 250 rounds and much worse at
  35. Several confidently-reasoned improvements measured negative, including three flatteners
  and an accuracy-adaptive firepower scheme whose underlying energy analysis was simply wrong
  (the break-even hit rate is 1/7, not 1/3, because damage dealt is worth energy too).
- **Fixed segmentation was the ceiling.** Splitting sparse evidence across buckets is what made
  every finer movement decision fail. KNN over continuous features fixed that. If a change that
  should obviously help measures as noise, suspect the model is data-starved rather than the
  idea being wrong.

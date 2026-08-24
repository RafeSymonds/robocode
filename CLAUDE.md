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

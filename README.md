# robocode

My bots for [Robocode Tank Royale](https://robocode.dev) — the game where you write a tank's
brain in Java and watch it fight other people's tanks. You never drive the tank yourself; you
write the code that decides how it moves, aims, and shoots, then let the battle run.

Engine version: **1.1.0**

## Quick start

```sh
bin/robocode start
```

That downloads the game engine on first run, points it at this repo's bots, and opens the GUI.
Then, in the window: **Battle → Start Battle**, pick `RafeBot` plus a few sample bots as
opponents, and hit **Start Battle**.

To iterate on a bot, skip the GUI and let the battle run at full speed with no rendering:

```sh
bin/battle --rounds 50 RafeBot SpinBot
```

```
rank bot                       score survival   damage    1sts
--------------------------------------------------------------
1    RafeBot                    1367      350      758       7
2    Spin Bot                    456      150      237       3
```

## The scripts

| Command | What it does |
| --- | --- |
| `bin/robocode start` | Launch the game GUI (downloads the engine if missing) |
| `bin/robocode start --build` | Re-download the engine first |
| `bin/robocode stop` | Shut down the game and any bots it left running |
| `bin/robocode restart` | Stop, re-download, start |
| `bin/robocode status` | Is it running? |
| `bin/battle A B [...]` | Run a battle headless and print the scoreboard |
| `bin/battle --rounds 50 A B` | More rounds, for a result that isn't luck |
| `bin/check` | Compile every bot — catches errors in seconds, no GUI needed |
| `bin/check RafeBot` | Compile one bot |
| `bin/newbot Sniper` | Scaffold `bots/Sniper/` |

`bin/battle` takes two bots for a duel and three or more for a melee. Names are looked up in
`bots/` first, then `samples/`, so `bin/battle RafeBot Walls` just works.

## Layout

```
bots/           my bots — one directory per bot (this is the part that gets committed)
  RafeBot/
    RafeBot.java
    RafeBot.json    name, author, description; the GUI reads this
  lib/          bot API jar (downloaded)
tools/          Battle.java, the headless battle driver behind bin/battle
samples/        the official sample bots, to fight against (downloaded)
engine/         game GUI, server, and runner jars (downloaded)
var/            logs, pidfile, compiled classes
```

Tank Royale finds a bot by matching names: the directory, the `.java` file, the `.json` file,
and the `public class` all have to be spelled identically. `bin/newbot` handles that for you.

## RafeBot

The starter bot, in `bots/RafeBot/RafeBot.java`. It is deliberately more than a toy — it does
the three things every competitive bot does, each in its own method so you can tune them one at
a time:

- **Radar** (`keepRadarOn`) — holds a lock on one target by sweeping slightly past it each turn,
  instead of spinning blindly and rediscovering enemies.
- **Gun** (`aimAndFire`) — leads the target, predicting where it will be when the bullet lands.
  Firepower scales down with distance so it doesn't waste energy on long shots.
- **Body** (`orbit`) — circles the target rather than charging it, reverses whenever it gets hit,
  and steers away from walls.

Where to go next, roughly in order of payoff. Change one thing, then run `bin/battle` to find
out whether it actually helped:

1. **Dodge better.** Reversing on every hit is predictable. Try reversing at random intervals.
2. **Aim better.** Linear prediction misses bots that circle. Look up *circular targeting*, then
   *guess factor targeting*.
3. **Pick targets better.** In melee it locks the closest bot; lowest-energy is often better,
   since finishing a weak bot removes a shooter from the field.
4. **Spend energy better.** Firing costs energy and hitting earns it back. Fire less when you are
   behind on energy, more when you are ahead.

## Notes

- Angles are in degrees, counter-clockwise, with 0 pointing east. **Positive bearings mean left.**
- Two ways to write a bot: blocking calls (`forward(100)`, `turnGunLeft(90)`), or `setXxx()` calls
  followed by `go()` to commit the turn. Don't mix the two in one bot — `RafeBot` uses `set` + `go`.
- A bot that takes too long on a turn gets skipped, not warned. Keep per-turn work cheap.
- Java comes from Homebrew's `openjdk`, which isn't on `PATH`. The scripts find it themselves.
- Docs: <https://robocode.dev> · API: <https://robocode.dev/api/java/>

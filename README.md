# robocode

Two Robocode engines, because they are two different games:

- **`classic/`** — [classic Robocode](https://robocode.sourceforge.io/). Old engine, but it is
  where the live [RoboRumble leaderboard](https://rumble.robowiki.net/RumbleStats) runs, with
  1,215 bots and rankings that update hourly. This is where `Kestrel` competes.
- **`bots/`** — [Robocode Tank Royale](https://robocode.dev). The modern successor: better
  engine, cleaner API, no live leaderboard yet (its rumble is still being built).

## Quick start

```sh
bin/classic start          # classic Robocode GUI
bin/bench Kestrel          # score Kestrel against the opponent ladder, in parallel

bin/robocode start         # Tank Royale GUI
bin/battle RafeBot SpinBot # headless Tank Royale battle
```

## Kestrel

`classic/src/rafe/Kestrel.java` — a 1v1 duelist. Two ideas do nearly all the work, both built
on **waves**: a bullet's flight time lets you reason about a shot long after it was fired.

**Movement — wave surfing.** You cannot see enemy bullets, but when the enemy's energy drops
they have fired. Kestrel records an expanding circle from where they fired; when one finally
hits, it remembers the angle it was caught at. Thereafter it simulates its own tank going both
ways around each incoming wave and drives toward whichever predicted landing spot it has been
hit at least often. Getting shot is the training signal.

**Targeting — guess factors.** Kestrel fires its own waves every tick, loaded or not, and when
one reaches the enemy it records where they were as a fraction of how far they could possibly
have moved. It aims at the fraction they pick most often. Five statistics buffers run from
unsegmented to range/speed/wall/acceleration, normalised and blended, so the gun is usable in
round one and sharp by round ten.

All learning is `static`, because Robocode rebuilds the robot object every round.

### Measured strength

Against real RoboRumble bots, 400 rounds each:

| Opponent | Score% | | Opponent | Score% |
| --- | --- | --- | --- | --- |
| dft.Freddie | 87.96 | | gh.GrubbmGrb | 73.19 |
| kawigi.mini.Fhqwhgads | 77.89 | | jam.micro.RaikoMicro | 71.95 |
| davidalves.net.DuelistMicro | 76.28 | | mld.Moebius | 70.47 |
| cx.mini.Nimrod | 68.20 | | sheldor.nano.Sabreur | 67.42 |
| pe.SandboxDT | 50.82 | | abc.Shadow | 25.57 |

**66.98 average.** At RoboRumble's own 35 rounds, where the learning has less time to compound,
it averages about **63.7**. Note this ladder is deliberately top-heavy — SandboxDT and Shadow are
all-time top-tier bots — so it is a harsher field than the rumble as a whole.

## The scripts

| Command | What it does |
| --- | --- |
| `bin/classic start\|stop\|restart\|status` | Classic Robocode GUI |
| `bin/bench Kestrel [--rounds N] [--tier T]` | Score against the whole ladder, in parallel |
| `bin/duel Kestrel <opponent...>` | Individual matchups, sequentially |
| `bin/build` | Compile the classic bots |
| `bin/opponents` | Download the benchmark ladder |
| `bin/package [Bot]` | Build the uploadable jar into `dist/` |
| `bin/robocode start\|stop\|restart\|status` | Tank Royale GUI |
| `bin/battle A B [--rounds N]` | Headless Tank Royale battle |
| `bin/check [Bot]` | Compile the Tank Royale bots |
| `bin/newbot Name` | Scaffold a Tank Royale bot |

**Measure every change with `bin/bench`.** A full ladder at 35 rounds takes about seven seconds,
because matchups run concurrently in isolated robot directories. Use 250+ rounds when comparing
two versions: the noise floor at 150 rounds is ±1.3, and single matchups swing 10 points between
identical runs at 35.

## Entering RoboRumble

1. `bin/package Kestrel` → `dist/rafe.Kestrel_1.0.jar`
2. Upload it somewhere with a **direct** download link (a GitHub release on this repo works).
3. Add one line to [RoboRumble/Participants](https://robowiki.net/wiki/RoboRumble/Participants),
   in alphabetical position, no space after the comma:

   ```
   rafe.Kestrel 1.0,https://github.com/RafeSymonds/robocode/releases/download/v1.0/rafe.Kestrel_1.0.jar
   ```

Other people's rumble clients pick it up within hours. A new version replaces the old line — do
not add a second. The `rafe.` prefix is only a namespace: RoboRumble requires *a* package name so
1,215 bots don't collide, and by convention people use their handle. It can be anything.

## Layout

```
classic/src/rafe/       classic bots (committed)
classic/engine/         classic Robocode 1.11.1 (downloaded)
bots/                   Tank Royale bots (committed)
engine/ samples/        Tank Royale engine and sample bots (downloaded)
tools/                  battle driver and results parser
dist/                   packaged upload jars
var/                    logs, benchmark workspaces, compiled classes
```

Engine quirks that cost real debugging time are written up in `CLAUDE.md` — read it before
touching the build, especially the roster-wildcard and jar-scanner notes.

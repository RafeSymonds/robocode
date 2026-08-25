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

**Targeting — nearest neighbours.** Kestrel fires its own waves every tick, loaded or not, and
when one reaches the enemy it records where they were as a fraction of how far they could
possibly have moved, alongside seven numbers describing the situation it fired in. To aim, it
finds the most similar shots it has taken before and picks the angle the largest cluster of
them landed at, measured with a kernel as wide as the enemy is — there is no sense
distinguishing two angles a bullet could not tell apart. A ring buffer of twenty thousand shots
is the memory, so how far back the gun looks is one number rather than a decay rate per
segmentation. Blended statistics buffers still aim for the first forty shots, while the
neighbours warm up.

**Bullet shadows.** Our own bullets are obstacles to theirs. When one of our bullets crosses an
incoming wave, any enemy bullet travelling down that exact angle died in the collision, so that
slice of the wave is provably harmless whatever the statistics say about it. It is the only
certain knowledge a surfer ever gets; everything else is inference from past hits.

All learning is `static`, because Robocode rebuilds the robot object every round.

### Measured strength

Against 18 real RoboRumble bots spanning nano to all-time top ten, 250 rounds each:

| Opponent | Score% | | Opponent | Score% |
| --- | --- | --- | --- | --- |
| dft.Freddie | 94.53 | | gh.GrubbmGrb | 77.66 |
| stelo.MirrorNano | 91.79 | | nat.nano.Ocnirp | 76.08 |
| mld.Moebius | 83.88 | | cx.mini.Nimrod | 73.80 |
| emp.Yngwie | 82.94 | | sheldor.nano.Sabreur | 73.02 |
| davidalves.net.DuelistMicro | 81.40 | | jam.micro.RaikoMicro | 71.34 |
| kawigi.sbf.FloodMini | 80.80 | | rz.GlowBlow | 71.17 |
| kawigi.mini.Fhqwhgads | 77.78 | | simonton.micro.WeeklongObsession | 67.05 |
| | | | pe.SandboxDT | 58.70 |
| | | | voidious.Dookious | 46.04 |
| | | | kc.serpent.Hydra | 44.70 |
| | | | abc.Shadow | 42.43 |

**71.95 average, winning 15 of 18.** The three losses are Dookious, Hydra and Shadow — all
former or current top-ten bots, and all of them were 25–32% not long ago. This ladder is far
harsher than the rumble as a whole.

The rumble scores **35-round** battles, not 250, and a bot that learns behaves differently in a
short match, so everything above is also checked there: **62.91 → 68.98** across this session,
eight runs of the ladder each way.

### What actually made it stronger

Every change was A/B tested over repeated 250-round runs of the whole ladder. A single run has
a standard deviation of about 0.75, so a lone run that looks a point better is not a result:

| Change | Effect |
| --- | --- |
| Nearest-neighbour aiming replacing segmented buffers | **+2.6**, 14 of 18 matchups |
| Consulting far more neighbours for danger (0.35·√n → 1.3·√n) | **+1.0** at 35 rounds |
| Letting the gun forget: fading its fine buffers | **+1.3** — Dookious 24.9 → 39.9 |
| Correcting range at full strength instead of a 0.3 nudge | **+0.8** |
| Fixing an O(n·k) neighbour search that cost us whole turns | 14 skipped turns → 1 |
| KNN danger model replacing fixed segments | **Shadow 25.6 → ~32** |
| Wall segmentation on the gun | **+2.8** |
| Danger weighted by bullet damage | **+2.4** |
| Fixing a hang that forfeited whole battles | a lost battle is worth 0% |
| Bullet shadows | +0.45; Hydra +3.7 with no overlap between runs |
| Virtual guns arbitrating between a fading and a remembering gun | exactly neutral |
| Hit *rate* instead of hit count, from recording waves that missed | **−1.2** |
| Recency weighting on the danger model | flat from 0.97 to 0.999 |
| Flatteners (three designs) | **−0.8 to −2.9** — all reverted |
| Multi-option surfing, including a brake | −1.2, failed on three separate danger models |
| Accuracy-adaptive firepower | **−8** — the energy analysis behind it was wrong |
| Orbit distance, bullet power, bin count, second-wave weight | already optimal |

Two diagnoses did most of the work. The first: against Shadow our bullet damage was close
(1033 vs 1495) but survival was 300 vs 1450, so the gun was never the problem — and a control
run with danger statistics disabled scored 9.64 against the top tier versus 37.25 with them, so
surfing was working. It was the *segmentation* that capped it. Twenty-seven buckets fed only by
hits leave each one starved, and a starved table makes finer decisions actively worse.

The second, once the gun caught up: against the top four, bullet damage is now within 10% of
theirs and the entire remaining gap is survival. Everything the gun learned came from the same
realisation as the movement — that splitting sparse evidence across buckets was the ceiling.

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

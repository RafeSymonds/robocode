import dev.robocode.tankroyale.botapi.Bot;
import dev.robocode.tankroyale.botapi.events.HitBotEvent;
import dev.robocode.tankroyale.botapi.events.HitByBulletEvent;
import dev.robocode.tankroyale.botapi.events.HitWallEvent;
import dev.robocode.tankroyale.botapi.events.ScannedBotEvent;
import dev.robocode.tankroyale.botapi.graphics.Color;

// ------------------------------------------------------------------
// RafeBot
// ------------------------------------------------------------------
// A starting point worth improving, rather than a toy.
//
// It does the three things every competitive bot does:
//   1. RADAR  - keeps a lock on one target instead of sweeping blindly
//   2. GUN    - fires where the target is going, not where it is
//   3. BODY   - orbits the target and reverses whenever it gets hit
//
// Each of those lives in its own method below. Tune them one at a time
// and run a battle after each change so you know what actually helped.
// ------------------------------------------------------------------
public class RafeBot extends Bot {

    /** Turns a radar lock survives before we give up and start sweeping again. */
    private static final int LOCK_TIMEOUT = 4;

    /** How close to a wall we can get before steering back toward the middle. */
    private static final double WALL_MARGIN = 80;

    /** Half a bot's width. Used to keep aim points inside the arena. */
    private static final double BOT_RADIUS = 18;

    /** The bot we are currently hunting, from the most recent scan. */
    private ScannedBotEvent target;

    /** Turn number of that scan, so we can tell a fresh lock from a stale one. */
    private int lastScanTurn;

    /** +1 or -1: which way we circle the target. Flipped whenever we take a hit. */
    private int orbitDirection = 1;

    public static void main(String[] args) {
        new RafeBot().start();
    }

    // Called once at the start of every round.
    @Override
    public void run() {
        setBodyColor(Color.fromRgb(0x21, 0x2C, 0x3A));
        setTurretColor(Color.fromRgb(0xE8, 0x6A, 0x33));
        setRadarColor(Color.fromRgb(0xF2, 0xC4, 0x5F));
        setBulletColor(Color.fromRgb(0xE8, 0x6A, 0x33));
        setScanColor(Color.fromRgb(0xF2, 0xC4, 0x5F));

        // Keep the gun and radar pointing where we aim them instead of being
        // dragged around by the body. Without this a lock breaks every time we turn.
        setAdjustGunForBodyTurn(true);
        setAdjustRadarForGunTurn(true);

        // Fields survive between rounds, so clear the stale lock from the last one.
        target = null;
        lastScanTurn = -LOCK_TIMEOUT - 1;

        while (isRunning()) {
            if (hasFreshLock()) {
                keepRadarOn(target);
                aimAndFire(target);
                orbit(target);
            } else {
                // Nothing in sight: sweep, and keep moving so we are not a free hit.
                setTurnRadarLeft(45);
                setForward(60);
            }
            go(); // commit everything set above and let the turn play out
        }
    }

    // ---------------------------------------------------------------
    // Radar
    // ---------------------------------------------------------------

    private boolean hasFreshLock() {
        return target != null && getTurnNumber() - lastScanTurn <= LOCK_TIMEOUT;
    }

    /**
     * Sweeps the radar slightly past the target instead of stopping on it. A radar
     * that parks on last turn's position scans empty space and drops the lock; one
     * that overshoots crosses the target again every turn and holds on.
     */
    private void keepRadarOn(ScannedBotEvent e) {
        double bearing = radarBearingTo(e.getX(), e.getY());
        double overshoot = bearing >= 0 ? 12 : -12;
        setTurnRadarLeft(bearing + overshoot);
    }

    // ---------------------------------------------------------------
    // Gun
    // ---------------------------------------------------------------

    /**
     * Leads the target, assuming it holds its current heading and speed. This alone
     * beats firing head-on against anything that moves in a straight line.
     */
    private void aimAndFire(ScannedBotEvent e) {
        double distance = distanceTo(e.getX(), e.getY());

        // Heavy bullets hurt more but fly slower and cost more energy, so spend
        // less on far targets we are likely to miss.
        double power = clamp(500 / distance, 0.5, 3.0);
        power = Math.min(power, getEnergy() - 0.5); // never fire ourselves to death
        if (power < 0.1) {
            return;
        }

        // Bullet travel time depends on the distance to the *predicted* point, which
        // depends on the travel time. A few passes is plenty to settle.
        double bulletSpeed = calcBulletSpeed(power);
        double aimX = e.getX();
        double aimY = e.getY();
        for (int i = 0; i < 3; i++) {
            double time = distanceTo(aimX, aimY) / bulletSpeed;
            double heading = Math.toRadians(e.getDirection());
            aimX = clamp(e.getX() + Math.cos(heading) * e.getSpeed() * time,
                    BOT_RADIUS, getArenaWidth() - BOT_RADIUS);
            aimY = clamp(e.getY() + Math.sin(heading) * e.getSpeed() * time,
                    BOT_RADIUS, getArenaHeight() - BOT_RADIUS);
        }

        double gunBearing = gunBearingTo(aimX, aimY);
        setTurnGunLeft(gunBearing);

        // Only pull the trigger once the gun is actually on the prediction.
        if (getGunHeat() == 0 && Math.abs(gunBearing) < 6) {
            setFire(power);
        }
    }

    // ---------------------------------------------------------------
    // Movement
    // ---------------------------------------------------------------

    /**
     * Drives perpendicular to the target so we are always crossing its line of fire.
     * Sitting still, or driving straight at an enemy, is the easiest way to get shot.
     */
    private void orbit(ScannedBotEvent e) {
        double turn = bearingTo(e.getX(), e.getY()) - 90 * orbitDirection;

        // Hugging a wall costs us half our escape routes, so head back toward the
        // middle when we get close to one.
        if (isNearWall()) {
            turn = bearingTo(getArenaWidth() / 2.0, getArenaHeight() / 2.0);
        }

        setTurnLeft(normalizeRelativeAngle(turn));
        setForward(80);
    }

    private boolean isNearWall() {
        return getX() < WALL_MARGIN
                || getY() < WALL_MARGIN
                || getX() > getArenaWidth() - WALL_MARGIN
                || getY() > getArenaHeight() - WALL_MARGIN;
    }

    // ---------------------------------------------------------------
    // Events
    // ---------------------------------------------------------------

    @Override
    public void onScannedBot(ScannedBotEvent e) {
        // In melee, stay on the closest bot. The comparison uses the previous scan's
        // position, which is a turn stale but close enough to pick a target.
        boolean lockIsStale = !hasFreshLock();
        if (lockIsStale || distanceTo(e.getX(), e.getY()) < distanceTo(target.getX(), target.getY())) {
            target = e;
        }
        lastScanTurn = getTurnNumber();
    }

    @Override
    public void onHitByBullet(HitByBulletEvent e) {
        // Someone has our range. Reverse so their next shot leads the wrong way.
        orbitDirection = -orbitDirection;
    }

    @Override
    public void onHitWall(HitWallEvent e) {
        orbitDirection = -orbitDirection;
        setBack(40);
    }

    @Override
    public void onHitBot(HitBotEvent e) {
        // Point blank: a full-power shot is nearly a guaranteed hit.
        setFire(3);
        if (e.isRammed()) {
            setBack(40);
        }
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}

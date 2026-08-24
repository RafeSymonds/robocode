package rafe;

import robocode.AdvancedRobot;
import robocode.BulletHitBulletEvent;
import robocode.HitByBulletEvent;
import robocode.HitRobotEvent;
import robocode.Rules;
import robocode.ScannedRobotEvent;
import robocode.util.Utils;

import java.awt.Color;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;

/**
 * Kestrel - a 1v1 duelist.
 *
 * Two ideas do almost all of the work, and both are built on "waves": the fact that
 * a bullet's flight time lets you reason about a shot long after it was fired.
 *
 *   MOVEMENT (wave surfing). Every time the enemy's energy drops they have fired,
 *   even though we cannot see the bullet. We record an expanding circle from where
 *   they fired. When one of those circles finally hits us, we remember *which angle*
 *   off dead-on it caught us at. Next time a circle is about to arrive, we simulate
 *   our own tank going both ways around it and drive toward whichever predicted
 *   landing spot we have been hit at least often. Getting shot is the training signal.
 *
 *   TARGETING (guess factor). We fire our own waves -- one every tick, whether or not
 *   the gun is loaded -- and when a wave reaches the enemy we record where they were
 *   as a fraction of how far they could possibly have moved. Aim at the fraction they
 *   pick most often. Statistics are kept in four buffers from coarse to fine, so the
 *   gun has a usable answer in round one and a sharp one by round ten.
 *
 * Everything learned is static, so it survives across the rounds of a battle.
 */
public class Kestrel extends AdvancedRobot {

    /** Angular slots spanning "hard left" to "hard right" of an enemy's escape range. */
    private static final int BINS = 47;
    private static final int MIDDLE = (BINS - 1) / 2;

    /** How far ahead we look when steering away from walls. */
    private static final double WALL_STICK = 160;

    /** How much the wave behind the incoming one counts when picking a direction. */
    private static final double SECOND_WAVE_WEIGHT = 0.4;

    /** How much old evidence survives each new hit. 1.0 remembers everything. */
    private static final double DANGER_DECAY = 0.97;

    /** Range we try to hold: close enough to hit, far enough to dodge. */
    private static final double PREFERRED_DISTANCE = 500;

    private static final int DIST_SEGMENTS = 5;
    private static final int VEL_SEGMENTS = 5;
    private static final int ACCEL_SEGMENTS = 3;
    private static final int WALL_SEGMENTS = 3;

    // --- Learned across rounds -------------------------------------------------

    /** Where enemy bullets have actually hit us, by [range][our speed][angle]. */
    private static final double[][][][] dangerStats = new double[3][3][3][BINS];

    /** Where the enemy has been when our waves arrived, coarse to fine. */
    private static final double[] gunFlat = new double[BINS];
    private static final double[][] gunByRange = new double[DIST_SEGMENTS][BINS];
    private static final double[][][] gunByRangeSpeed = new double[DIST_SEGMENTS][VEL_SEGMENTS][BINS];
    private static final double[][][][] gunFine =
            new double[DIST_SEGMENTS][VEL_SEGMENTS][ACCEL_SEGMENTS][BINS];
    private static final double[][][][] gunByWall =
            new double[DIST_SEGMENTS][VEL_SEGMENTS][WALL_SEGMENTS][BINS];

    // --- Per round -------------------------------------------------------------

    private final List<EnemyWave> enemyWaves = new ArrayList<>();
    private final List<GunWave> gunWaves = new ArrayList<>();
    private final List<Integer> pastDirections = new ArrayList<>();
    private final List<Double> pastBearings = new ArrayList<>();

    private Rectangle2D.Double field;
    private Point2D.Double myLocation;
    private Point2D.Double enemyLocation;
    private double enemyEnergy = 100;
    private double lastEnemyLateralSpeed;
    private int enemyDirection = 1;
    private int lastDirection = 1;

    @Override
    public void run() {
        field = new Rectangle2D.Double(18, 18, getBattleFieldWidth() - 36, getBattleFieldHeight() - 36);

        setColors(new Color(0x21, 0x2C, 0x3A), new Color(0xE8, 0x6A, 0x33),
                new Color(0xF2, 0xC4, 0x5F), new Color(0xE8, 0x6A, 0x33), new Color(0xF2, 0xC4, 0x5F));

        // The gun and radar must not be dragged around by the body, or every lock breaks
        // the moment we turn.
        setAdjustGunForRobotTurn(true);
        setAdjustRadarForGunTurn(true);

        // Spin forever; onScannedRobot overrides this the instant it sees something.
        while (true) {
            turnRadarRightRadians(Double.POSITIVE_INFINITY);
        }
    }

    @Override
    public void onScannedRobot(ScannedRobotEvent e) {
        myLocation = new Point2D.Double(getX(), getY());

        double absBearing = e.getBearingRadians() + getHeadingRadians();
        double myLateralSpeed = getVelocity() * Math.sin(e.getBearingRadians());

        // Keep the radar nailed to the enemy: overshooting by 2x makes it sweep back
        // across them every tick instead of drifting off.
        setTurnRadarRightRadians(Utils.normalRelativeAngle(absBearing - getRadarHeadingRadians()) * 2);

        pastDirections.add(0, myLateralSpeed >= 0 ? 1 : -1);
        pastBearings.add(0, absBearing + Math.PI);

        // An energy drop is the enemy firing. We only learn about it a tick late, and
        // the bullet started from where they *were*, so this must run before we update
        // their position.
        double power = enemyEnergy - e.getEnergy();
        if (power > 0.09 && power < 3.01 && pastDirections.size() > 2) {
            EnemyWave wave = new EnemyWave();
            wave.fireTime = getTime() - 1;
            wave.bulletSpeed = bulletSpeed(power);
            wave.distanceTraveled = wave.bulletSpeed;
            wave.direction = pastDirections.get(2);
            wave.directAngle = pastBearings.get(2);
            wave.fireLocation = (Point2D.Double) enemyLocation.clone();
            wave.rangeIndex = moveRangeSegment(wave.fireLocation.distance(myLocation));
            wave.speedIndex = moveSpeedSegment(myLateralSpeed);
            wave.wallIndex = myWallSegment(wave.fireLocation, lastDirection);
            enemyWaves.add(wave);
        }
        enemyEnergy = e.getEnergy();
        enemyLocation = project(myLocation, absBearing, e.getDistance());

        // Which way the enemy is sliding around us. Held through the moment it passes
        // through zero, so a direction reversal doesn't flip on noise.
        double enemyLateralSpeed = e.getVelocity() * Math.sin(e.getHeadingRadians() - absBearing);
        if (Math.abs(enemyLateralSpeed) > 0.1) {
            enemyDirection = enemyLateralSpeed > 0 ? 1 : -1;
        }

        advanceEnemyWaves();
        advanceGunWaves();
        aimAndFire(e, absBearing, enemyLateralSpeed);
        surf();

        lastEnemyLateralSpeed = enemyLateralSpeed;
    }

    // =========================================================================
    // Movement: wave surfing
    // =========================================================================

    private void surf() {
        EnemyWave wave = nearestIncomingWave(null);
        Point2D.Double source;
        int direction;

        if (wave == null) {
            // Nothing in the air. Keep orbiting anyway -- standing still is how you die.
            source = enemyLocation;
            direction = lastDirection;
        } else {
            // Dodging one bullet into the path of the next is a good way to lose. The
            // second wave counts for less because we get another decision before it
            // lands, and because our prediction of where we will be by then is looser.
            EnemyWave next = nearestIncomingWave(wave);
            double left = dangerOf(wave, -1);
            double right = dangerOf(wave, 1);
            if (next != null) {
                left += SECOND_WAVE_WEIGHT * dangerOf(next, -1);
                right += SECOND_WAVE_WEIGHT * dangerOf(next, 1);
            }
            source = wave.fireLocation;
            direction = left < right ? -1 : 1;
            lastDirection = direction;
        }

        drive(wallSmoothing(myLocation, orbitAngle(source, myLocation, direction), direction));
    }

    /** The closest wave still on its way in, ignoring {@code except}. */
    private EnemyWave nearestIncomingWave(EnemyWave except) {
        EnemyWave nearest = null;
        double nearestGap = Double.POSITIVE_INFINITY;
        for (EnemyWave wave : enemyWaves) {
            if (wave == except) {
                continue;
            }
            double gap = myLocation.distance(wave.fireLocation) - wave.distanceTraveled;
            if (gap > wave.bulletSpeed && gap < nearestGap) {
                nearestGap = gap;
                nearest = wave;
            }
        }
        return nearest;
    }

    private void advanceEnemyWaves() {
        for (int i = enemyWaves.size() - 1; i >= 0; i--) {
            EnemyWave wave = enemyWaves.get(i);
            wave.distanceTraveled = (getTime() - wave.fireTime) * wave.bulletSpeed;
            if (wave.distanceTraveled > myLocation.distance(wave.fireLocation) + 50) {
                enemyWaves.remove(i);
            }
        }
    }

    /**
     * How often we have been hit around the spot this wave would catch us going
     * {@code direction}. A tank is 36 units wide, so a bullet aimed a little off still
     * connects; scoring a single angle understates the risk of standing next to a
     * place we keep getting shot.
     */
    private double dangerOf(EnemyWave wave, int direction) {
        Point2D.Double predicted = predictPosition(wave, direction);
        int index = factorIndex(wave, predicted);

        double range = Math.max(predicted.distance(wave.fireLocation), 1);
        int half = (int) Math.ceil(Math.atan(18 / range) / maxEscapeAngle(wave.bulletSpeed) * MIDDLE);

        double[] bins = dangerStats[wave.rangeIndex][wave.speedIndex][wave.wallIndex];
        double total = 0;
        for (int i = index - half; i <= index + half; i++) {
            total += bins[(int) limit(0, i, BINS - 1)];
        }
        return total;
    }

    /**
     * Simulates our own tank orbiting the wave until the wave catches it, using the
     * real movement rules. The prediction has to steer exactly the way {@link #surf}
     * steers, or we would be dodging based on a position we never actually reach.
     */
    private Point2D.Double predictPosition(EnemyWave wave, int direction) {
        Point2D.Double position = (Point2D.Double) myLocation.clone();
        double velocity = getVelocity();
        double heading = getHeadingRadians();
        int ticks = 0;

        while (true) {
            double desired = wallSmoothing(position, orbitAngle(wave.fireLocation, position, direction), direction);

            double turn = Utils.normalRelativeAngle(desired - heading);
            double gear = 1;
            if (Math.cos(turn) < 0) { // easier to get there in reverse
                turn = Utils.normalRelativeAngle(turn + Math.PI);
                gear = -1;
            }

            double maxTurn = Math.PI / 720d * (40d - 3d * Math.abs(velocity));
            heading = Utils.normalRelativeAngle(heading + limit(-maxTurn, turn, maxTurn));

            // Robocode brakes twice as hard as it accelerates.
            velocity += (velocity * gear < 0 ? 2 * gear : gear);
            velocity = limit(-8, velocity, 8);

            position = project(position, heading, velocity);
            ticks++;

            if (position.distance(wave.fireLocation)
                    < wave.distanceTraveled + (ticks + 1) * wave.bulletSpeed) {
                return position;
            }
            if (ticks > 500) {
                return position;
            }
        }
    }

    /**
     * Perpendicular to the incoming wave, tilted slightly in or out to hold range.
     * Straight perpendicular is the best dodge but drifts to whatever distance the
     * enemy chooses; the tilt keeps that from becoming point blank.
     */
    private double orbitAngle(Point2D.Double source, Point2D.Double from, int direction) {
        double lean = limit(-0.3, (PREFERRED_DISTANCE - from.distance(source)) / PREFERRED_DISTANCE, 0.3);
        return absoluteBearing(source, from) + direction * (Math.PI / 2) * (1 - lean);
    }

    private double wallSmoothing(Point2D.Double from, double angle, int direction) {
        for (int i = 0; i < 200 && !field.contains(project(from, angle, WALL_STICK)); i++) {
            angle += direction * 0.05;
        }
        return angle;
    }

    /** Drives toward an absolute angle, reversing rather than turning more than 90 degrees. */
    private void drive(double angle) {
        double turn = Utils.normalRelativeAngle(angle - getHeadingRadians());
        if (Math.abs(turn) > Math.PI / 2) {
            setTurnRightRadians(Utils.normalRelativeAngle(turn + Math.PI));
            setBack(100);
        } else {
            setTurnRightRadians(turn);
            setAhead(100);
        }
    }

    @Override
    public void onHitByBullet(HitByBulletEvent e) {
        EnemyWave wave = matchWave(e.getBullet().getX(), e.getBullet().getY(), e.getBullet().getPower());
        if (wave != null) {
            logHit(wave, new Point2D.Double(e.getBullet().getX(), e.getBullet().getY()),
                    damageOf(e.getBullet().getPower()));
            enemyWaves.remove(wave);
        }
    }

    /** Robocode's damage formula. A power 3 hit costs us 16; a power 0.5 hit costs 2. */
    private static double damageOf(double power) {
        return 4 * power + 2 * Math.max(power - 1, 0);
    }

    @Override
    public void onHitRobot(HitRobotEvent e) {
        // Point blank a shot cannot miss, and a rammer trading hits at this range
        // comes off worse: 3 power costs us 3 and takes 16 off them.
        if (getGunHeat() == 0 && getEnergy() > 3.1) {
            setFire(3);
        }
        // A collision costs the enemy energy too, and our fire detector reads any
        // energy drop as a shot. Re-baseline here, or the next scan invents a wave
        // that never existed and poisons the movement statistics with it.
        enemyEnergy = e.getEnergy();
    }

    @Override
    public void onBulletHitBullet(BulletHitBulletEvent e) {
        // We blocked it, but it still tells us exactly where that shot was aimed.
        EnemyWave wave = matchWave(e.getHitBullet().getX(), e.getHitBullet().getY(), e.getHitBullet().getPower());
        if (wave != null) {
            logHit(wave, new Point2D.Double(e.getHitBullet().getX(), e.getHitBullet().getY()),
                    damageOf(e.getHitBullet().getPower()));
            enemyWaves.remove(wave);
        }
    }

    /** Finds which of our tracked waves a real bullet belongs to, by radius and speed. */
    private EnemyWave matchWave(double x, double y, double power) {
        Point2D.Double at = new Point2D.Double(x, y);
        EnemyWave best = null;
        double bestError = 50;
        for (EnemyWave wave : enemyWaves) {
            double error = Math.abs(wave.distanceTraveled - at.distance(wave.fireLocation));
            if (error < bestError && Math.abs(bulletSpeed(power) - wave.bulletSpeed) < 0.001) {
                bestError = error;
                best = wave;
            }
        }
        return best;
    }

    private void logHit(EnemyWave wave, Point2D.Double at, double weight) {
        int index = factorIndex(wave, at);
        double[] bins = dangerStats[wave.rangeIndex][wave.speedIndex][wave.wallIndex];
        for (int i = 0; i < BINS; i++) {
            // Smear it: a bullet that hit here would very nearly have hit next door too,
            // and fade what came before, because a strong opponent keeps changing its gun
            // and danger learned in round three can describe a bot that no longer exists.
            bins[i] = bins[i] * DANGER_DECAY + weight / ((i - index) * (i - index) + 1);
        }
    }

    private static int factorIndex(EnemyWave wave, Point2D.Double at) {
        double offset = Utils.normalRelativeAngle(absoluteBearing(wave.fireLocation, at) - wave.directAngle);
        double factor = offset / maxEscapeAngle(wave.bulletSpeed) * wave.direction;
        return (int) limit(0, factor * MIDDLE + MIDDLE, BINS - 1);
    }

    // =========================================================================
    // Targeting: guess factor gun
    // =========================================================================

    private void aimAndFire(ScannedRobotEvent e, double absBearing, double enemyLateralSpeed) {
        double power = choosePower(e);
        int range = rangeSegment(e.getDistance());
        int speed = speedSegment(enemyLateralSpeed);
        int accel = accelSegment(enemyLateralSpeed, lastEnemyLateralSpeed);
        int wall = wallSegment(e.getDistance(), absBearing, enemyDirection);

        // Launch a wave every tick, loaded or not. Shots we never took still teach the
        // gun where this enemy likes to be.
        GunWave wave = new GunWave();
        wave.fireLocation = (Point2D.Double) myLocation.clone();
        wave.fireTime = getTime();
        wave.bulletSpeed = bulletSpeed(power);
        wave.maxEscape = maxEscapeAngle(wave.bulletSpeed);
        wave.directAngle = absBearing;
        wave.direction = enemyDirection;
        wave.range = range;
        wave.speed = speed;
        wave.accel = accel;
        wave.wall = wall;
        gunWaves.add(wave);

        double factor = (double) (bestBin(range, speed, accel, wall) - MIDDLE) / MIDDLE;
        double aim = absBearing + factor * wave.maxEscape * enemyDirection;

        double gunTurn = Utils.normalRelativeAngle(aim - getGunHeadingRadians());
        setTurnGunRightRadians(gunTurn);

        // Only shoot once the gun can finish swinging this tick; otherwise the bullet
        // leaves while we are still tracking and simply misses.
        if (getGunHeat() == 0 && getEnergy() > power + 0.1
                && Math.abs(gunTurn) < Rules.GUN_TURN_RATE_RADIANS) {
            setFire(power);
        }
    }

    private void advanceGunWaves() {
        for (int i = gunWaves.size() - 1; i >= 0; i--) {
            GunWave wave = gunWaves.get(i);
            if ((getTime() - wave.fireTime) * wave.bulletSpeed >= wave.fireLocation.distance(enemyLocation)) {
                record(wave, enemyLocation);
                gunWaves.remove(i);
            }
        }
    }

    private void record(GunWave wave, Point2D.Double at) {
        double offset = Utils.normalRelativeAngle(absoluteBearing(wave.fireLocation, at) - wave.directAngle);
        double factor = limit(-1, offset / wave.maxEscape * wave.direction, 1);
        int index = (int) Math.round(factor * MIDDLE + MIDDLE);

        smear(gunFlat, index);
        smear(gunByRange[wave.range], index);
        smear(gunByRangeSpeed[wave.range][wave.speed], index);
        smear(gunFine[wave.range][wave.speed][wave.accel], index);
        smear(gunByWall[wave.range][wave.speed][wave.wall], index);
    }

    private static void smear(double[] bins, int index) {
        for (int i = 0; i < BINS; i++) {
            bins[i] += 1.0 / ((i - index) * (i - index) + 1);
        }
    }

    /**
     * Blends the four buffers. Each is normalised first, so a nearly empty fine-grained
     * buffer cannot outvote a well-populated coarse one, and heavier weights on the
     * specific buffers let them take over once they have seen enough.
     */
    private int bestBin(int range, int speed, int accel, int wall) {
        double[] combined = new double[BINS];
        blend(combined, gunFlat, 1);
        blend(combined, gunByRange[range], 2);
        blend(combined, gunByRangeSpeed[range][speed], 4);
        blend(combined, gunFine[range][speed][accel], 8);
        blend(combined, gunByWall[range][speed][wall], 8);

        int best = MIDDLE; // head-on until we have learned anything
        for (int i = 0; i < BINS; i++) {
            if (combined[i] > combined[best]) {
                best = i;
            }
        }
        return best;
    }

    private static void blend(double[] into, double[] buffer, double weight) {
        double total = 0;
        for (double value : buffer) {
            total += value;
        }
        if (total <= 0) {
            return;
        }
        for (int i = 0; i < BINS; i++) {
            into[i] += weight * buffer[i] / total;
        }
    }

    private double choosePower(ScannedRobotEvent e) {
        double power;
        if (e.getDistance() < 150) {
            power = 3.0;
        } else if (e.getDistance() < 350) {
            power = 2.5;
        } else {
            power = 2.0;
        }
        // Running ourselves to zero energy is a loss even when the shots land.
        if (getEnergy() < 40) {
            power = Math.min(power, getEnergy() / 12);
        }
        // Never spend 3 energy finishing a bot that has 2 left.
        power = Math.min(power, e.getEnergy() / 4 + 0.1);
        return limit(0.1, power, 3.0);
    }

    // =========================================================================
    // Segments
    // =========================================================================

    private static int rangeSegment(double distance) {
        return (int) limit(0, distance / 200, DIST_SEGMENTS - 1);
    }

    private static int speedSegment(double lateralSpeed) {
        return (int) limit(0, Math.abs(lateralSpeed) * VEL_SEGMENTS / 8.5, VEL_SEGMENTS - 1);
    }

    /**
     * How much room the enemy has left to keep sliding the way they are going, as a
     * share of a quarter circle around us. A bot with a wall coming up cannot keep
     * running, which is exactly when their guess factor stops being uniform.
     */
    private int wallSegment(double distance, double absBearing, int direction) {
        double angle = 0;
        while (angle < Math.PI / 2
                && field.contains(project(myLocation, absBearing + direction * angle, distance))) {
            angle += 0.05;
        }
        return (int) limit(0, angle / (Math.PI / 2) * WALL_SEGMENTS, WALL_SEGMENTS - 1);
    }

    private static int accelSegment(double now, double before) {
        double delta = Math.abs(now) - Math.abs(before);
        if (delta > 0.1) {
            return 2;
        }
        return delta < -0.1 ? 0 : 1;
    }

    private static int moveRangeSegment(double distance) {
        return (int) limit(0, distance / 300, 2);
    }

    private static int moveSpeedSegment(double lateralSpeed) {
        return (int) limit(0, Math.abs(lateralSpeed) / 3, 2);
    }

    /**
     * How far we can keep orbiting this wave's source before a wall stops us. Being
     * boxed in changes which angles the enemy can catch us at, so danger learned with
     * open field behind us does not transfer to a corner.
     */
    private int myWallSegment(Point2D.Double source, int direction) {
        double distance = myLocation.distance(source);
        double bearing = absoluteBearing(source, myLocation);
        double angle = 0;
        while (angle < Math.PI / 2
                && field.contains(project(source, bearing + direction * angle, distance))) {
            angle += 0.05;
        }
        return (int) limit(0, angle / (Math.PI / 2) * 3, 2);
    }

    // =========================================================================
    // Geometry
    // =========================================================================

    private static double bulletSpeed(double power) {
        return 20 - 3 * power;
    }

    private static double maxEscapeAngle(double speed) {
        return Math.asin(8.0 / speed);
    }

    private static Point2D.Double project(Point2D.Double from, double angle, double length) {
        return new Point2D.Double(from.x + Math.sin(angle) * length, from.y + Math.cos(angle) * length);
    }

    private static double absoluteBearing(Point2D.Double from, Point2D.Double to) {
        return Math.atan2(to.x - from.x, to.y - from.y);
    }

    private static double limit(double min, double value, double max) {
        return Math.max(min, Math.min(value, max));
    }

    // =========================================================================

    private static final class EnemyWave {
        Point2D.Double fireLocation;
        long fireTime;
        double bulletSpeed;
        double directAngle;
        double distanceTraveled;
        int direction;
        int rangeIndex;
        int speedIndex;
        int wallIndex;
    }

    private static final class GunWave {
        Point2D.Double fireLocation;
        long fireTime;
        double bulletSpeed;
        double directAngle;
        double maxEscape;
        int direction;
        int range;
        int speed;
        int accel;
        int wall;
    }
}

import dev.robocode.tankroyale.runner.BattleResults;
import dev.robocode.tankroyale.runner.BattleRunner;
import dev.robocode.tankroyale.runner.BattleSetup;
import dev.robocode.tankroyale.runner.BotEntry;
import dev.robocode.tankroyale.runner.BotResult;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Runs a battle with no GUI and prints the scoreboard, so bots can be compared
 * in seconds instead of watched. Driven by bin/battle.
 */
public class Battle {

    public static void main(String[] args) {
        int rounds = 10;
        List<BotEntry> bots = new ArrayList<>();

        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--rounds")) {
                rounds = Integer.parseInt(args[++i]);
            } else {
                bots.add(BotEntry.of(args[i]));
            }
        }

        if (bots.size() < 2) {
            System.err.println("battle: need at least two bots");
            System.exit(1);
        }

        int numberOfRounds = rounds;
        Consumer<BattleSetup.Builder> tune = builder -> {
            builder.setNumberOfRounds(numberOfRounds);
            builder.setDefaultTurnsPerSecond(-1); // -1 means "as fast as the CPU allows"
            builder.setMinNumberOfParticipants(2);
        };
        BattleSetup setup = bots.size() == 2
                ? BattleSetup.Companion.oneVsOne(tune)
                : BattleSetup.Companion.melee(tune);

        System.out.printf("Running %d rounds with %d bots...%n%n", rounds, bots.size());

        BattleResults results;
        try (BattleRunner runner = BattleRunner.create(
                (Consumer<BattleRunner.Builder>) builder -> builder.embeddedServer().suppressServerOutput())) {
            results = runner.runBattle(setup, bots);
        }

        System.out.printf("%-4s %-22s %8s %8s %8s %7s%n",
                "rank", "bot", "score", "survival", "damage", "1sts");
        System.out.println("-".repeat(62));
        for (BotResult r : results.getResults()) {
            System.out.printf("%-4d %-22s %8d %8d %8d %7d%n",
                    r.getRank(), r.getName(), r.getTotalScore(),
                    r.getSurvival(), r.getBulletDamage(), r.getFirstPlaces());
        }
    }
}

package ai.shuvrajeetkamila.mumbai.game;

/**
 * What the player keeps between sessions.
 *
 * <p>Until this existed nothing was saved at all: money, completed jobs,
 * the time of day and where you were standing were all constructed fresh
 * with the {@code Engine} and thrown away with it. That is not only "you
 * lose your money when you quit" - Android destroys and recreates an
 * activity routinely, on a rotation or under memory pressure, so a fare
 * earned in the morning could vanish because the phone turned sideways.
 *
 * <p>Deliberately a plain value object with a hand-rolled text format.
 * Core must not depend on Android, and the format has to survive a field
 * being added later, so it is a flat list of {@code key=value} lines and
 * anything unrecognised is ignored.
 */
public final class Progress {

    /** Bumped only if an old file must be actively rejected. */
    public static final int VERSION = 1;

    public int money;
    public int faresDone;
    public int deliveriesDone;
    public int racesWon;
    public int timesBusted;
    public double distanceM;
    /** Best time per race route, seconds. Index is the route seed. */
    public double bestRaceS;
    /** Where the player was, in world metres. NaN if never saved. */
    public double x = Double.NaN;
    public double z = Double.NaN;
    /** In-game hour, 0..24. */
    public double hour = 8.0;

    public int jobsDone() { return faresDone + deliveriesDone + racesWon; }

    /** Records a finished job of this kind. */
    public void completed(Missions.Kind kind, int reward, double seconds) {
        money += reward;
        if (kind == null) { return; }
        switch (kind) {
            case TAXI: faresDone++; break;
            case DELIVERY: deliveriesDone++; break;
            case RACE:
                racesWon++;
                if (bestRaceS <= 0 || seconds < bestRaceS) { bestRaceS = seconds; }
                break;
            default: break;
        }
    }

    @Override
    public String toString() {
        StringBuilder b = new StringBuilder(220);
        b.append("version=").append(VERSION).append('\n');
        b.append("money=").append(money).append('\n');
        b.append("fares=").append(faresDone).append('\n');
        b.append("deliveries=").append(deliveriesDone).append('\n');
        b.append("races=").append(racesWon).append('\n');
        b.append("busted=").append(timesBusted).append('\n');
        b.append("distance=").append(distanceM).append('\n');
        b.append("bestRace=").append(bestRaceS).append('\n');
        b.append("x=").append(x).append('\n');
        b.append("z=").append(z).append('\n');
        b.append("hour=").append(hour).append('\n');
        return b.toString();
    }

    /** Never throws. A corrupt save is a new game, not a crash. */
    public static Progress parse(String text) {
        Progress p = new Progress();
        if (text == null || text.isEmpty()) { return p; }
        for (String line : text.split("\n")) {
            int eq = line.indexOf('=');
            if (eq <= 0) { continue; }
            String k = line.substring(0, eq).trim();
            String v = line.substring(eq + 1).trim();
            try {
                switch (k) {
                    case "money": p.money = Integer.parseInt(v); break;
                    case "fares": p.faresDone = Integer.parseInt(v); break;
                    case "deliveries": p.deliveriesDone = Integer.parseInt(v); break;
                    case "races": p.racesWon = Integer.parseInt(v); break;
                    case "busted": p.timesBusted = Integer.parseInt(v); break;
                    case "distance": p.distanceM = Double.parseDouble(v); break;
                    case "bestRace": p.bestRaceS = Double.parseDouble(v); break;
                    case "x": p.x = Double.parseDouble(v); break;
                    case "z": p.z = Double.parseDouble(v); break;
                    case "hour": p.hour = Double.parseDouble(v); break;
                    default: break;
                }
            } catch (NumberFormatException ignored) {
                // One bad line must not cost the rest of the save.
            }
        }
        if (!(p.hour >= 0 && p.hour < 24)) { p.hour = 8.0; }
        if (p.money < 0) { p.money = 0; }
        return p;
    }

    /** Where a save lives. Implemented over SharedPreferences on Android. */
    public interface Store {
        String read();

        void write(String text);
    }

    /** A store that forgets, for tests and for a device with no storage. */
    public static final class Memory implements Store {
        private String text = "";

        @Override public String read() { return text; }

        @Override public void write(String t) { text = t; }
    }
}

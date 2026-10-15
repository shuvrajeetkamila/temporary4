package ai.shuvrajeetkamila.mumbai.game;

/**
 * A reason to do the jobs in order.
 *
 * <p>Fares, deliveries and races all worked and all paid, and there was
 * no reason to do any of them twice. Money with nothing to spend it on
 * is a score, and a score is not progression.
 *
 * <p>So: a ladder of chapters. Each one asks for a particular kind of
 * work a particular number of times, pays a bonus when it is finished,
 * and opens the next. Because the chapters ask for different kinds,
 * finishing the ladder means driving a taxi, then a tempo, then
 * something fast, then all three - which is the whole of the game's
 * vehicle roster, found and driven in sequence, rather than whichever
 * one you happened to park next to.
 *
 * <p>The people in it are unnamed roles rather than characters - a
 * dispatcher, a yard foreman - because inventing residents of real
 * neighbourhoods is a way of getting them wrong, and the brief asks
 * for Dharavi and the chawls to be treated with respect. The work is
 * ordinary work.
 */
public final class Career {

    /** One rung of the ladder. */
    public static final class Chapter {
        public final String title;
        public final String contact;
        public final String brief;
        public final Missions.Kind kind;
        /** How many of that kind finish the chapter. */
        public final int target;
        /** Bonus paid on completion, rupees. */
        public final int bonus;

        Chapter(String title, String contact, String brief,
                Missions.Kind kind, int target, int bonus) {
            this.title = title;
            this.contact = contact;
            this.brief = brief;
            this.kind = kind;
            this.target = target;
            this.bonus = bonus;
        }

        /** True when any finished job counts, whatever it was. */
        public boolean anyKind() { return kind == null; }
    }

    /**
     * The ladder.
     *
     * <p>Deliberately short and deliberately escalating: three fares is
     * about ten minutes, and by the last chapter the player has had to
     * find and drive every kind of working vehicle in the city.
     */
    public static final Chapter[] CHAPTERS = {
        new Chapter("Night Shift", "Fort taxi stand",
                "Run three fares. The stand pays a retainer at the end"
                        + " of the shift.",
                Missions.Kind.TAXI, 3, 500),
        new Chapter("Goods Inward", "Sewri yard foreman",
                "Three deliveries. Find a tempo or an auto - the stand"
                        + " taxis will not carry crates.",
                Missions.Kind.DELIVERY, 3, 700),
        new Chapter("Late Traffic", "Link Road regulars",
                "Win two races. Something quick, and not a bus.",
                Missions.Kind.RACE, 2, 900),
        new Chapter("Double Shift", "Fort taxi stand",
                "Five more fares. The retainer goes up with the hours.",
                Missions.Kind.TAXI, 5, 1200),
        new Chapter("Port Run", "Sewri yard foreman",
                "Five deliveries, and the yard will put your name down"
                        + " for the long contracts.",
                Missions.Kind.DELIVERY, 5, 1500),
        new Chapter("Anything Going", null,
                "Eight jobs of any kind. Whatever is on the street.",
                null, 8, 2500),
        new Chapter("The Long Contract", null,
                "Twelve more. At this point it is a living.",
                null, 12, 4000),
    };

    /** How far up the ladder, 0-based. Equal to the length when done. */
    public int chapter;

    /** Jobs of the right kind finished in the current chapter. */
    public int done;

    /** Transient line for the HUD when a chapter completes. */
    public String banner = "";
    public double bannerTimer;

    public boolean finished() { return chapter >= CHAPTERS.length; }

    /** The chapter being worked on, or null when the ladder is done. */
    public Chapter current() {
        return finished() ? null : CHAPTERS[chapter];
    }

    /** How many are still needed, or 0 when done. */
    public int remaining() {
        Chapter c = current();
        return c == null ? 0 : Math.max(0, c.target - done);
    }

    /**
     * One line for the HUD: what to do next.
     *
     * <p>Named contact first, because "Sewri yard foreman: 2 more
     * deliveries" tells you both who wants it and what it is, and a
     * bare counter tells you neither.
     */
    public String objective() {
        Chapter c = current();
        if (c == null) { return "All contracts finished"; }
        String what;
        if (c.anyKind()) {
            what = remaining() + (remaining() == 1 ? " job" : " jobs");
        } else {
            switch (c.kind) {
                case TAXI:
                    what = remaining()
                            + (remaining() == 1 ? " fare" : " fares");
                    break;
                case DELIVERY:
                    what = remaining()
                            + (remaining() == 1 ? " delivery" : " deliveries");
                    break;
                default:
                    what = remaining()
                            + (remaining() == 1 ? " race" : " races");
                    break;
            }
        }
        return (c.contact == null ? c.title : c.contact) + ": " + what;
    }

    /**
     * Count a finished job.
     *
     * @return the bonus to pay, or 0 if the chapter is not finished yet
     */
    public int record(Missions.Kind kind) {
        Chapter c = current();
        if (c == null) { return 0; }
        if (!c.anyKind() && c.kind != kind) { return 0; }
        done++;
        if (done < c.target) { return 0; }
        chapter++;
        done = 0;
        banner = c.title + " complete   +Rs " + c.bonus;
        bannerTimer = 5.0;
        return c.bonus;
    }

    public void update(double dt) {
        if (bannerTimer > 0) {
            bannerTimer -= dt;
            if (bannerTimer <= 0) { banner = ""; }
        }
    }

    /** Clamp a restored save onto the ladder as it exists now. */
    public void restore(int chapterIndex, int doneInChapter) {
        chapter = Math.max(0, Math.min(CHAPTERS.length, chapterIndex));
        Chapter c = current();
        int cap = c == null ? 0 : c.target - 1;
        done = Math.max(0, Math.min(cap, doneInChapter));
    }
}

package ai.shuvrajeetkamila.mumbai.game;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ladder that makes the jobs worth doing in order.
 *
 * <p>Fares, deliveries and races all worked and all paid, and there was
 * no reason to do any of them twice. Money with nothing to spend it on
 * is a score.
 */
final class CareerTest {

    @Test
    @DisplayName("only the kind the chapter asks for counts")
    void theWrongWorkDoesNotCount() {
        Career c = new Career();
        Career.Chapter first = c.current();
        assertEquals(Missions.Kind.TAXI, first.kind,
                "the ladder should open on something you can start in"
                        + " the commonest vehicle in the city");
        assertEquals(0, c.record(Missions.Kind.DELIVERY));
        assertEquals(0, c.record(Missions.Kind.RACE));
        assertEquals(0, c.done,
                "deliveries and races counted towards a fare contract");
        for (int i = 0; i < first.target - 1; i++) {
            assertEquals(0, c.record(Missions.Kind.TAXI));
        }
        assertEquals(first.bonus, c.record(Missions.Kind.TAXI),
                "the last fare of the chapter did not pay the bonus");
        assertEquals(1, c.chapter, "the chapter did not advance");
        assertEquals(0, c.done, "the counter did not reset");
    }

    @Test
    @DisplayName("the ladder makes you drive everything")
    void everyKindIsRequiredSomewhere() {
        boolean taxi = false, delivery = false, race = false, any = false;
        for (Career.Chapter ch : Career.CHAPTERS) {
            if (ch.anyKind()) { any = true; continue; }
            switch (ch.kind) {
                case TAXI: taxi = true; break;
                case DELIVERY: delivery = true; break;
                default: race = true; break;
            }
        }
        assertTrue(taxi && delivery && race,
                "the ladder can be finished without driving one of the"
                        + " three kinds of working vehicle");
        assertTrue(any,
                "no open chapter, so the end of the ladder forces a"
                        + " particular vehicle rather than letting the"
                        + " player work");
    }

    @Test
    @DisplayName("it escalates and then ends")
    void theLadderIsOrdered() {
        int lastBonus = 0;
        for (Career.Chapter ch : Career.CHAPTERS) {
            assertTrue(ch.target > 0, "a chapter asks for no work");
            assertTrue(ch.bonus > lastBonus,
                    "chapter '" + ch.title + "' pays " + ch.bonus
                            + ", no more than the one before it");
            lastBonus = ch.bonus;
            assertTrue(!ch.title.isEmpty() && !ch.brief.isEmpty(),
                    "a chapter with nothing to say");
        }

        Career c = new Career();
        int paid = 0;
        for (int i = 0; i < 400 && !c.finished(); i++) {
            Career.Chapter ch = c.current();
            paid += c.record(ch.anyKind() ? Missions.Kind.TAXI : ch.kind);
        }
        assertTrue(c.finished(), "the ladder cannot be finished");
        assertTrue(paid > 10000,
                "finishing every contract pays only Rs " + paid);
        assertEquals(0, c.record(Missions.Kind.TAXI),
                "a finished ladder is still paying bonuses");
        assertTrue(c.objective().length() > 0,
                "nothing to show once it is finished");
    }

    @Test
    @DisplayName("the objective says who wants what")
    void theObjectiveIsUseful() {
        Career c = new Career();
        String o = c.objective();
        assertTrue(o.contains("fare"),
                "the opening objective does not say what to do: " + o);
        assertTrue(o.contains(":"),
                "the objective does not say who is asking: " + o);
        c.record(Missions.Kind.TAXI);
        assertTrue(c.objective().contains(
                        String.valueOf(c.current().target - 1)),
                "the objective does not count down");
        // Singular when one is left, because "1 fares" is sloppy.
        Career d = new Career();
        for (int i = 0; i < d.current().target - 1; i++) {
            d.record(Missions.Kind.TAXI);
        }
        assertTrue(d.objective().contains("1 fare")
                        && !d.objective().contains("1 fares"),
                "bad grammar on the last one: " + d.objective());
    }

    @Test
    @DisplayName("a save is restored onto the ladder as it exists now")
    void restoreIsClamped() {
        Career c = new Career();
        c.restore(2, 1);
        assertEquals(2, c.chapter);
        assertEquals(1, c.done);

        // A save from a longer ladder, or a corrupt one, must not put
        // the player somewhere that does not exist.
        c.restore(9999, 9999);
        assertTrue(c.finished(), "an over-long chapter index was kept");
        c.restore(-4, -4);
        assertEquals(0, c.chapter);
        assertEquals(0, c.done);
        // Never restored already-complete, which would skip a chapter
        // without paying for it.
        c.restore(0, Career.CHAPTERS[0].target + 5);
        assertTrue(c.done < Career.CHAPTERS[0].target,
                "restored into a chapter that was already finished");
    }

    @Test
    @DisplayName("the ladder survives being saved")
    void itIsPersisted() {
        Progress p = new Progress();
        p.chapter = 3;
        p.chapterDone = 2;
        Progress back = Progress.parse(p.toString());
        assertEquals(3, back.chapter, "the chapter was not saved");
        assertEquals(2, back.chapterDone, "the progress was not saved");
        // An older save predates the ladder and must start at the top.
        Progress old = Progress.parse("version=1\nmoney=800\n");
        assertEquals(0, old.chapter);
        assertEquals(0, old.chapterDone);
    }
}

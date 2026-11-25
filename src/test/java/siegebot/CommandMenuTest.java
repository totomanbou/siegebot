package siegebot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** {@link CommandMenu} のページの計算と、{@link BotManager#uniqueName}（専用のボットの名前）。Bukkit なしで確かめる。 */
class CommandMenuTest {

    private static final int PAGE = CommandMenu.BOT_PAGE_SIZE;

    // ---- ページの計算 ----

    @Test
    void pageSizesAreEightAndEighteen() {
        assertEquals(8, CommandMenu.BOT_PAGE_SIZE);
        assertEquals(18, CommandMenu.DELEGATE_PAGE_SIZE);
    }

    @Test
    void pageCountForVariousTotals() {
        assertEquals(1, CommandMenu.pageCount(0, PAGE));
        assertEquals(1, CommandMenu.pageCount(1, PAGE));
        assertEquals(1, CommandMenu.pageCount(2, PAGE));
        assertEquals(1, CommandMenu.pageCount(8, PAGE));
        assertEquals(2, CommandMenu.pageCount(9, PAGE));
        assertEquals(2, CommandMenu.pageCount(16, PAGE));
        assertEquals(3, CommandMenu.pageCount(17, PAGE));
        assertEquals(4, CommandMenu.pageCount(30, PAGE));
        assertEquals(1, CommandMenu.pageCount(-3, PAGE));
        assertEquals(1, CommandMenu.pageCount(10, 0));
    }

    @Test
    void clampPageKeepsThePageInsideTheRange() {
        assertEquals(0, CommandMenu.clampPage(0, 0, PAGE));
        assertEquals(0, CommandMenu.clampPage(5, 0, PAGE));
        assertEquals(0, CommandMenu.clampPage(-1, 30, PAGE));
        assertEquals(0, CommandMenu.clampPage(0, 8, PAGE));
        assertEquals(0, CommandMenu.clampPage(1, 8, PAGE)); // 8 体なら 1 ページだけ
        assertEquals(1, CommandMenu.clampPage(1, 9, PAGE));
        assertEquals(1, CommandMenu.clampPage(9, 16, PAGE));
        assertEquals(2, CommandMenu.clampPage(2, 17, PAGE));
        assertEquals(3, CommandMenu.clampPage(100, 30, PAGE));
    }

    @Test
    void pageStartAndEndSplitTheListWithoutGapsOrOverlaps() {
        for (int total : new int[] {0, 1, 2, 8, 9, 16, 17, 30}) {
            int expectedStart = 0;
            int pages = CommandMenu.pageCount(total, PAGE);
            for (int page = 0; page < pages; page++) {
                int start = CommandMenu.pageStart(page, total, PAGE);
                int end = CommandMenu.pageEnd(page, total, PAGE);
                assertEquals(expectedStart, start, "total=" + total + " page=" + page);
                assertTrue(end - start <= PAGE, "total=" + total + " page=" + page);
                assertTrue(end >= start && end <= total, "total=" + total + " page=" + page);
                expectedStart = end;
            }
            assertEquals(total, expectedStart, "total=" + total);
        }
    }

    @Test
    void pageStartAndEndForSpecificCases() {
        assertEquals(0, CommandMenu.pageStart(0, 0, PAGE));
        assertEquals(0, CommandMenu.pageEnd(0, 0, PAGE));
        assertEquals(0, CommandMenu.pageStart(0, 9, PAGE));
        assertEquals(8, CommandMenu.pageEnd(0, 9, PAGE));
        assertEquals(8, CommandMenu.pageStart(1, 9, PAGE));
        assertEquals(9, CommandMenu.pageEnd(1, 9, PAGE));
        assertEquals(16, CommandMenu.pageStart(2, 17, PAGE));
        assertEquals(17, CommandMenu.pageEnd(2, 17, PAGE));
        // 範囲外のページは最後のページに収まる
        assertEquals(24, CommandMenu.pageStart(99, 30, PAGE));
        assertEquals(30, CommandMenu.pageEnd(99, 30, PAGE));
        assertEquals(0, CommandMenu.pageStart(-4, 30, PAGE));
        assertEquals(8, CommandMenu.pageEnd(-4, 30, PAGE));
    }

    // ---- 専用のボットの名前 ----

    private static void assertValidNames(String player, String suffix, int count) {
        Set<String> used = new HashSet<>();
        for (int i = 0; i < count; i++) {
            String name = BotManager.uniqueName(player, suffix, used::contains);
            assertTrue(name.length() <= 16, "length " + name.length() + ": " + name);
            assertFalse(used.contains(name), "duplicate: " + name);
            used.add(name);
        }
        assertEquals(count, used.size());
    }

    @Test
    void uniqueNameGivesNumberedNamesForOneToFifteenBots() {
        Set<String> used = new HashSet<>();
        for (int i = 1; i <= 15; i++) {
            String name = BotManager.uniqueName("Steve", "の兵", used::contains);
            used.add(name);
            assertEquals(i == 1 ? "Steveの兵" : "Steve" + i + "の兵", name);
        }
    }

    @Test
    void uniqueNameStaysWithinSixteenCharactersForLongPlayerNames() {
        String sixteen = "ABCDEFGHIJKLMNOP"; // 16 文字（Minecraft の名前の最大）
        assertValidNames(sixteen, "の兵", 30);
        assertValidNames(sixteen, "", 30);
        assertValidNames(sixteen, "の槍兵", 30);
        assertValidNames("a", "の兵", 30);
        assertValidNames("", "の兵", 30);
        assertValidNames(null, "の兵", 5);
    }

    @Test
    void uniqueNameStaysWithinSixteenCharactersForExtremeSuffixes() {
        assertValidNames("Steve", "0123456789abcdef", 30); // 16 文字の接尾辞
        assertValidNames("Steve", "0123456789abcdefXYZ", 30); // 16 文字を超える接尾辞
        assertValidNames("ABCDEFGHIJKLMNOP", "0123456789abcde", 100); // 接尾辞 15 文字（名前に使える幅が 1）
        assertValidNames("Steve", null, 30);
    }

    @Test
    void uniqueNameKeepsTwoDigitNumbersInsideTheLimit() {
        Set<String> used = new HashSet<>();
        String name = "";
        for (int i = 1; i <= 12; i++) {
            name = BotManager.uniqueName("ABCDEFGHIJKLMNOP", "の兵", used::contains);
            used.add(name);
        }
        assertTrue(name.length() <= 16, name);
        assertTrue(name.contains("12"), name);
        assertTrue(name.endsWith("の兵"), name);
    }
}

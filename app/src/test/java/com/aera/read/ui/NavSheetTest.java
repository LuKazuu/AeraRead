package com.aera.read.ui;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Pure logic test for the contents filter: one row per reachable page. */
public class NavSheetTest {

    @Test
    public void keepsTheFirstEntryOfEveryPage() {
        List<NavSheet.Entry> entries = NavSheet.buildEntries(
                Arrays.asList("Cover", "Insert", "Title Page", "Chapter 1", "Chapter 2"),
                Arrays.asList(0, 0, 0, 4, 9));
        assertEquals(3, entries.size());
        assertEquals("Cover", entries.get(0).title);
        assertEquals(0, entries.get(0).page);
        assertEquals("Chapter 1", entries.get(1).title);
        assertEquals(4, entries.get(1).page);
        assertEquals(9, entries.get(2).page);
    }

    @Test
    public void dropsBlankTitles() {
        List<NavSheet.Entry> entries = NavSheet.buildEntries(
                Arrays.asList("Chapter 1", "   ", "Chapter 2"),
                Arrays.asList(2, 3, 4));
        assertEquals(2, entries.size());
        assertEquals(2, entries.get(0).page);
        assertEquals(4, entries.get(1).page);
    }

    @Test
    public void handlesEmptyInput() {
        assertEquals(0, NavSheet.buildEntries(Collections.emptyList(),
                Collections.emptyList()).size());
        assertEquals(0, NavSheet.buildEntries(Arrays.asList("a", "b"),
                Collections.singletonList(1)).size() - 1);
    }
}

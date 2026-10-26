package com.aera.read.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;

import androidx.test.core.app.ApplicationProvider;

import com.aera.read.TestSupport;
import com.aera.read.data.ReaderPrefs;
import com.aera.read.data.ReaderTheme;
import com.aera.read.epub.EpubBook;
import com.aera.read.epub.EpubPaginator;
import com.aera.read.epub.EpubParser;
import com.aera.read.epub.PagedBook;
import com.aera.read.epub.TextStyles;
import com.aera.read.io.FileSource;
import com.aera.read.io.ZipArchive;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
public class PageViewTest {

    private Application app;

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
    }

    private PagedBook paginate(String sample) throws Exception {
        ReaderPrefs prefs = new ReaderPrefs(app);
        TextStyles styles = TextStyles.create(app, prefs);
        EpubBook epub = new EpubParser(
                ZipArchive.open(new FileSource(TestSupport.resourceFile(sample))))
                .parse(sample);
        epub.zip = null;
        return EpubPaginator.paginate(epub, styles, 700, 1100);
    }

    private PagedBook paginate() throws Exception {
        return paginate("meditations.epub");
    }

    private Bitmap render(PageView view, ReaderTheme theme, int w, int h) {
        view.measure(android.view.View.MeasureSpec.makeMeasureSpec(w,
                        android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(h,
                        android.view.View.MeasureSpec.EXACTLY));
        view.layout(0, 0, w, h);
        Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(bitmap));
        return bitmap;
    }

    private static boolean hasBrightPixel(Bitmap bmp, int threshold) {
        for (int y = 0; y < bmp.getHeight(); y += 3) {
            for (int x = 0; x < bmp.getWidth(); x += 3) {
                int c = bmp.getPixel(x, y);
                if (Color.red(c) > threshold && Color.green(c) > threshold
                        && Color.blue(c) > threshold) {
                    return true;
                }
            }
        }
        return false;
    }

    @Test
    public void textColorFollowsTheme() throws Exception {
        PagedBook paged = paginate();
        PageView view = new PageView(app);
        view.setPadding(40, 40, 40, 40);

        view.setData(paged, paged.page(6), ReaderTheme.NIGHT, null);
        Bitmap night = render(view, ReaderTheme.NIGHT, 800, 1200);
        assertEquals("background is the night color", ReaderTheme.NIGHT.bg, night.getPixel(2, 2));
        assertTrue("light glyphs are visible on the night background",
                hasBrightPixel(night, 150));

        view.setData(paged, paged.page(6), ReaderTheme.LIGHT, null);
        Bitmap paper = render(view, ReaderTheme.LIGHT, 800, 1200);
        assertEquals(ReaderTheme.LIGHT.bg, paper.getPixel(2, 2));
        assertNotEquals("pixel colors differ between themes",
                night.getPixel(400, 600), paper.getPixel(400, 600));
    }

    @Test
    public void bottomMarginStaysEmptyBecauseTheBarShowsThePage() throws Exception {
        PagedBook paged = paginate();
        PageView view = new PageView(app);
        view.setPadding(40, 60, 40, 46);
        view.setData(paged, paged.page(6), ReaderTheme.LIGHT, null);
        Bitmap bmp = render(view, ReaderTheme.LIGHT, 800, 1200);
        boolean footerInk = false;
        for (int x = 0; x < 800; x++) {
            if (bmp.getPixel(x, 1180) != ReaderTheme.LIGHT.bg) footerInk = true;
        }
        assertFalse("page number is drawn in the bottom bar, not on the page", footerInk);
    }

    @Test
    public void drawsIllustrationBlocksWithoutCrashing() throws Exception {
        PagedBook paged = paginate("alice.epub");
        PageView view = new PageView(app);
        view.setPadding(40, 60, 40, 46);
        int rendered = 0;
        for (int i = 0; i < Math.min(60, paged.totalPages); i++) {
            PagedBook.Page page = paged.page(i);
            boolean imagePage = false;
            for (PagedBook.Segment seg : page.segments) imagePage |= seg.isImage();
            if (!imagePage) continue;
            view.setData(paged, page, ReaderTheme.LIGHT, null);
            Bitmap bmp = render(view, ReaderTheme.LIGHT, 800, 1200);
            assertEquals(ReaderTheme.LIGHT.bg, bmp.getPixel(2, 2));
            rendered++;
        }
        assertTrue("illustration pages rendered", rendered > 0);
    }
}

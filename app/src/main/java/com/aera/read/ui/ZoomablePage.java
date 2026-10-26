package com.aera.read.ui;

/** Common zoom contract shared by paginated/scrollable EPUB and PDF page views. */
interface ZoomablePage {
    boolean isZoomed();

    void resetZoom();
}

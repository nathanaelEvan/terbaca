package app.terbaca.core.render.scheduler

/**
 * Layer hierarchy per PRD §4.4 and §4.5 for tile priority ordering.
 */
enum class TileLayerType(val rank: Int) {
    // Base before detail: one small render guarantees the page is never blank (F-REN-2), while a
    // heavy page's detail tiles can take seconds to all arrive.
    VISIBLE_BASE(0),     // Priority 1: base layers of visible pages
    VISIBLE_DETAIL(1),   // Priority 2: visible tiles at current target level
    // Adjacent bases before prefetch: on a fling the next page must have its base before it scrolls
    // in, and the prefetch ring can queue dozens of tiles ahead of it.
    ADJACENT_BASE(2),    // Priority 3: adjacent pages' base layers
    PREFETCH_DETAIL(3),  // Priority 4: prefetch ring in scroll/fling direction
    THUMBNAIL(4);        // Priority 5: thumbnails
}

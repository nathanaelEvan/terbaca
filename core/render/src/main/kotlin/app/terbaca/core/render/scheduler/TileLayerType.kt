package app.terbaca.core.render.scheduler

/**
 * Layer hierarchy per PRD §4.4 and §4.5 for tile priority ordering.
 */
enum class TileLayerType(val rank: Int) {
    VISIBLE_DETAIL(0),   // Priority 1: visible tiles at current target level
    VISIBLE_BASE(1),     // Priority 2: base layers of visible pages
    PREFETCH_DETAIL(2),  // Priority 3: prefetch ring in scroll/fling direction
    ADJACENT_BASE(3),    // Priority 4: adjacent pages' base layers
    THUMBNAIL(4);        // Priority 5: thumbnails
}

package app.terbaca.benchmark

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By

const val TARGET_PACKAGE = "app.terbaca"
const val EXTRA_CORPUS_FILE = "app.terbaca.extra.CORPUS_FILE"

private const val PINCH_SPEED_PX_PER_SEC = 2_500
private const val PAN_STEPS = 40 // ~5 ms per step → ~200 ms drag
private const val FLING_STEPS = 6 // ~30 ms swipe → fling with momentum
private const val SETTLE_MS = 600L

/**
 * Pinch in, pan and fling in all directions, pinch out. Uses raw swipes rather than
 * UiObject2.fling/scroll + waitForIdle: those wait for accessibility scroll events the
 * Compose canvas never sends, and a continuously animating surface never reports idle.
 */
fun MacrobenchmarkScope.exerciseReader() {
    val w = device.displayWidth
    val h = device.displayHeight
    val cx = w / 2
    val cy = h / 2
    val dx = w / 4
    val dy = h / 4

    readerRoot()?.pinchOpen(0.6f, PINCH_SPEED_PX_PER_SEC)
    Thread.sleep(SETTLE_MS)

    device.swipe(cx + dx, cy, cx - dx, cy, PAN_STEPS)
    device.swipe(cx - dx, cy, cx + dx, cy, PAN_STEPS)
    device.swipe(cx, cy + dy, cx, cy - dy, PAN_STEPS)
    device.swipe(cx, cy - dy, cx, cy + dy, PAN_STEPS)
    Thread.sleep(SETTLE_MS)

    device.swipe(cx, cy + dy, cx, cy - dy, FLING_STEPS)
    Thread.sleep(SETTLE_MS)
    device.swipe(cx, cy - dy, cx, cy + dy, FLING_STEPS)
    Thread.sleep(SETTLE_MS)

    readerRoot()?.pinchClose(0.6f, PINCH_SPEED_PX_PER_SEC)
    Thread.sleep(SETTLE_MS)
}

/**
 * The window's root view. A bare By.pkg can match a small clickable such as the page indicator
 * pill, and a pinch on a tiny object is a tap: it opened the jump-to-page dialog mid-benchmark.
 */
internal fun MacrobenchmarkScope.readerRoot() = device.findObject(By.pkg(TARGET_PACKAGE).depth(0))

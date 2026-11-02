package app.terbaca.core.pdfium

import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class PdfiumRenderPriorityTest {

    @Test
    fun renderExecutor_runsAtConfiguredPriority() {
        val executor = createRenderExecutor(priority = PdfiumRenderService.RENDER_THREAD_PRIORITY)
        val observedPriority = AtomicInteger()
        val latch = CountDownLatch(1)

        executor.execute {
            observedPriority.set(Process.getThreadPriority(Process.myTid()))
            latch.countDown()
        }

        assertTrue("Timed out waiting for task on render executor", latch.await(2, TimeUnit.SECONDS))
        assertEquals(Process.THREAD_PRIORITY_DISPLAY, observedPriority.get())
        executor.shutdown()
    }
}

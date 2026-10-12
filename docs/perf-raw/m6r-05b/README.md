# M6R-05b: slide cache keeps the slide about to arrive

- Device: Xiaomi Pad 7 (2410CRP4CG), Android 16, HyperOS OS3.0.303.0.WOZIDXM, USB powered.
  Battery and thermal status per run: `device-state.txt`.
- Command (each run, 5 iterations x 16 flips):
  `./gradlew :benchmark:connectedBenchmarkAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.terbaca.benchmark.SlideFlipBenchmark`
- `before-run{1,2,3}`: `main` at 4fdba57 (least-recently-used eviction).
  `after-run{1,2,3}`: this branch (evict the slide farthest from the new one). All six runs in one session, 2026-10-12.
- Numbers: `python summarize.py`.
- Cause: a local build with trace sections on every cache get/put/evict (not committed) showed the
  arriving slide being evicted on the put of the slide two ahead, e.g. `SCput p5`, `SCevict p3`, then
  `SCget p3 false` → NotSharp. `SlideCacheTest.everyFlipArrivesOnACachedSlide` replays that call sequence.

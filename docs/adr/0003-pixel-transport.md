# ADR 0003: Pixel Transport Architecture and Driver Fallback Strategy

- **Status:** proposed
- **Date:** 2026-10-02
- **Deciders:** Antigravity (executing agent)
- **Consulted:** None (awaiting reviewer feedback)
- **Informed:** Terbaca project contributors

## Context and Problem Statement

As established in [PRD §4.2](../PRD.md#42-threading-and-process-model-critical) and [ADR 0002](0002-ipc-mechanism.md), PDFium runs in a dedicated `:render` service process to protect the application from native crashes and process-global PDFium threading constraints.

The isolated render process produces rendered PDF tiles that must be transported to the UI process and submitted to the GPU. On a 144 Hz display (such as the Xiaomi Pad 7 target device), each frame has a strict **6.94 ms** budget ([PRD §7](../PRD.md#7-performance-budgets-and-acceptance-gates)). Uploading bitmap pixel textures to the GPU on the UI thread during a frame causes jank and dropped frames. Furthermore, standard Binder transactions are subject to a shared 1 MB process-wide buffer limit.

We need an IPC pixel transport mechanism that delivers GPU-ready textures to the UI process with zero UI-thread upload latency, while maintaining a robust fallback for Android devices whose GPU/Gralloc drivers exhibit quirks with CPU-writeable hardware buffers.

## Decision Drivers

- **Zero UI-Thread GPU Upload:** No CPU-to-GPU pixel copying or texture uploading may occur on the UI thread during a frame ([PRD §4.3](../PRD.md#43-pixel-transport)).
- **Binder Transaction Safety:** Pixel data must not be sent inline inside Binder transaction parcels (capped at 1 MB process-wide).
- **Driver Robustness & Graceful Fallback:** Support diverse Gralloc/GPU driver implementations across Snapdragon (Adreno) and Dimensity (Mali) hardware.
- **Resource & Leak Safety:** Deterministic closure of underlying file descriptors and graphic buffers in both service and client processes across rapid pinch/pan interactions.
- **Color Format Fidelity:** Native alignment with Android's `RGBA_8888` display pipeline without expensive CPU byte-swapping passes.

## Considered Options

1. **Primary: `HardwareBuffer` (`RGBA_8888`, `USAGE_CPU_WRITE_OFTEN | USAGE_GPU_SAMPLED_IMAGE`) + `Bitmap.wrapHardwareBuffer`**
2. **Fallback: `SharedMemory` (ashmem) + Background Thread `HARDWARE` Bitmap Copy**
3. **Inline `byte[]` in Binder Parcel**
4. **Producer-Consumer Queues via `Surface` / `ImageReader`**

## Decision Outcome

Chosen option: **Dual-tier architecture: Option 1 as Primary with automated graceful fallback to Option 2**, satisfying [PRD §4.3](../PRD.md#43-pixel-transport).

### Architectural Details

1. **Primary Path (`HardwareBuffer`):**
   - **Allocation:** `:render` process creates an `android.hardware.HardwareBuffer` with format `RGBA_8888` and usage flags `USAGE_CPU_WRITE_OFTEN | USAGE_GPU_SAMPLED_IMAGE`.
   - **Native Lock & Direct Rasterization:** Native C++ acquires the `AHardwareBuffer` pointer via `AHardwareBuffer_fromHardwareBuffer` and locks the buffer memory via `AHardwareBuffer_lock(..., AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN, ...)`.
   - **Color Format & Annotations:** PDFium wraps the locked pointer using `FPDFBitmap_CreateEx(width, height, FPDFBitmap_BGRA, lockedPtr, stride)`. Background is cleared to opaque white (`0xFFFFFFFF`) via `FPDFBitmap_FillRect`. Rendering uses flags `FPDF_REVERSE_BYTE_ORDER | FPDF_ANNOT`. The `FPDF_REVERSE_BYTE_ORDER` flag instructs PDFium to write pixels directly in `RGBA` order, perfectly matching Android's `AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM` without any CPU swizzling pass.
   - **Unlock & Binder Transport:** The buffer is unlocked with `AHardwareBuffer_unlock`. Because `HardwareBuffer` implements `Parcelable`, AIDL passes the graphic buffer file descriptor across the Binder IPC boundary with negligible parcel footprint (< 100 bytes).
   - **Zero-Copy Wrapping:** In the UI process, `Bitmap.wrapHardwareBuffer(hwBuffer, null)` creates an immutable `Bitmap.Config.HARDWARE` instance referencing the graphic buffer directly. It is immediately ready for Canvas/RenderNode drawing without CPU-to-GPU uploads.

2. **Fallback Path (`SharedMemory`):**
   - If `HardwareBuffer.create` throws or `AHardwareBuffer_lock` fails (e.g. driver refuses CPU-write access on certain chipsets or memory pressure occurs), the render service automatically transitions to the fallback path.
   - `:render` creates an `android.os.SharedMemory` buffer of size `tileWidth * tileHeight * 4`.
   - The memory is mapped as read-write, cleared to white, rendered via PDFium's `FPDFBitmap_CreateEx` with `FPDF_REVERSE_BYTE_ORDER`, unmapped, and marked read-only via `OsConstants.PROT_READ`.
   - In the UI process, the client maps the read-only `SharedMemory`, populates a software bitmap, and copies it to a `Bitmap.Config.HARDWARE` bitmap on a background thread (`Dispatchers.Default` / `Dispatchers.IO`) per PRD §4.3:
     > *"Fallback if (2) proves unreliable on the device's driver: render into SharedMemory, then in the UI process copy to a HARDWARE bitmap on a background thread. In either design, no GPU texture upload may happen on the UI thread during a frame."*
   - Once converted, the `SharedMemory` is closed and unmapped.

3. **Buffer and File Descriptor Lifecycle Management:**
   - **Service Process:** Native buffers are written to the Binder reply parcel in `onTransact`. Immediately after `super.onTransact` completes, any service-held `HardwareBuffer` or `SharedMemory` instances are closed via `ThreadLocal` cleanup guards, avoiding graphic buffer and file descriptor leaks in the background service.
   - **UI Process:** Received `HardwareBuffer` handles are closed immediately after calling `Bitmap.wrapHardwareBuffer` in a `finally` block (the underlying `Bitmap` maintains an internal native reference until recycled). `SharedMemory` buffers are unmapped and closed inside `finally` blocks upon completing background GPU promotion.

## Pros and Cons of the Options

### Option 1: `HardwareBuffer` (Primary)

- Good, because zero CPU-to-GPU copy on UI thread; texture is resident in GPU memory upon arrival.
- Good, because negligible Binder payload (file descriptor dup via kernel).
- Good, because `Bitmap.wrapHardwareBuffer` provides seamless integration with Compose Canvas and Android drawing pipelines.
- Bad, because requires device GPU/Gralloc driver support for `USAGE_CPU_WRITE_OFTEN`.

### Option 2: `SharedMemory` (Fallback)

- Good, because kernel ashmem / memfd works uniformly across all Android Linux kernels regardless of GPU driver quirks.
- Good, because protects UI thread by offloading `HARDWARE` bitmap conversion to background coroutine dispatchers.
- Bad, because involves an additional memory copy compared to zero-copy HardwareBuffer.

### Option 3: Inline `byte[]` in Binder Parcel

- Good, because simple to implement.
- Bad, because Binder transaction buffer is capped at 1 MB shared across the entire process. A single 512×512 32-bit tile is 1 MB (`512 * 512 * 4 = 1,048,576` bytes), guaranteeing immediate `TransactionTooLargeException` crashes.

### Option 4: `Surface` / `ImageReader` Queues

- Good, because native Vulkan/OpenGL swapchains use this pipeline.
- Bad, because complex multi-buffer queue coordination and synchronization fences for discrete pyramid tiles.
- Bad, because poorly suited for non-sequential tile caching and pyramid level assembly.

## Links

- [PRD.md §4.2 Threading and process model](../PRD.md#42-threading-and-process-model-critical)
- [PRD.md §4.3 Pixel transport](../PRD.md#43-pixel-transport)
- [PRD.md §7 Performance Budgets and Acceptance Gates](../PRD.md#7-performance-budgets-and-acceptance-gates)
- [PRD.md §8 Milestones and Task Breakdown](../PRD.md#8-milestones-and-task-breakdown)
- [ADR 0002: Inter-Process Communication Mechanism](0002-ipc-mechanism.md)

# ADR 0002: Inter-Process Communication Mechanism for Isolated Render Service

- **Status:** proposed
- **Date:** 2026-10-02
- **Deciders:** Antigravity (executing agent)
- **Consulted:** None (awaiting reviewer feedback)
- **Informed:** Terbaca project contributors

## Context and Problem Statement

As mandated by [PRD §4.2](../PRD.md#42-threading-and-process-model-critical), PDFium must execute in a dedicated, separate service process (`android:process=":render"`). The primary motivations are fault isolation (a native crash in PDFium handling a corrupt or malicious PDF must not terminate the UI process) and threading isolation (PDFium is not thread-safe and process-global).

The UI process and the isolated render process must exchange document open requests, page layout metrics, cancellation signals, and rendered image pixels. We need to decide on the IPC mechanism between the UI process and `:render`.

## Decision Drivers

- **Process Isolation & Crash Resilience:** Clean separation with immediate crash detection (`DeathRecipient`) so the UI process can restart the service, restore state, and re-sharpen tiles seamlessly (PRD §4.2).
- **Zero-Copy Pixel & Buffer Transport:** Support direct parceling of `android.hardware.HardwareBuffer` (PRD §4.3) and `ParcelFileDescriptor` without intermediate userspace socket serialization.
- **Type Safety & Maintainability:** Explicit, strongly typed contract between UI and render service, preventing message ID drift and manual byte packing.
- **Performance:** Low latency dispatching for tile rendering requests, page geometry inspection, and progressive render cancellations.

## Considered Options

1. **AIDL (Android Interface Definition Language) over Binder**
2. **Android `Messenger` (Handler-based) over Binder**
3. **Local Unix Domain Sockets (UDS) / Pipes**
4. **Custom `ContentProvider` / `SharedMemory` interface**

## Decision Outcome

Chosen option: **Option 1: AIDL over Binder**, because it provides compile-time interface verification, native handling for `ParcelFileDescriptor` and `HardwareBuffer`, and deterministic death notifications with minimal boilerplate.

### Architectural Details

1. **Interface Contract:**
   - Defined in `core/pdfium/src/main/aidl/app/terbaca/core/pdfium/IPdfiumRenderService.aidl`.
   - Exposes `openDocument(in ParcelFileDescriptor pfd, String password): long`, `getPageCount(long docHandle): int`, `getPageSize(long docHandle, int pageIndex): float[]`, and `closeDocument(long docHandle): void`.
   - Subsequent milestones (M1-04 and M1-05) will add tile rendering (`renderTile`) returning `HardwareBuffer` directly across the Binder proxy.

2. **Threading & Concurrency:**
   - Inside `PdfiumRenderService`, all Binder incoming transactions are routed to a dedicated single-threaded executor (`PdfiumRenderThread`), enforcing strict serial execution for PDFium calls without race conditions.

3. **Crash Recovery & Death Recipient:**
   - The UI client (`PdfiumRemotePageRenderer`) registers an `IBinder.DeathRecipient` upon connection.
   - If the render process crashes, `binderDied()` fires immediately. The client marks `isServiceConnected = false`, clears active handles, and triggers reconnection logic, satisfying the crash resilience exit criteria of M1.

### Positive Consequences

- `HardwareBuffer` is `Parcelable`; AIDL passes hardware buffer handles across Binder natively via kernel fd passing, satisfying PRD §4.3 with zero user-space memory copies.
- File descriptors (`ParcelFileDescriptor`) are dup'd across processes atomically by Binder.
- Standard Android architecture: no custom native socket daemons or JNI IPC bindings required.

### Negative Consequences

- Binder transaction buffer is capped at 1 MB per process across all concurrent transactions.
  - *Mitigation:* Bulk image pixels are never passed inline inside Binder parcels; instead, `HardwareBuffer` handles (which only transfer metadata and file descriptors across Binder) are passed, consuming less than 100 bytes per transaction.

## Pros and Cons of the Options

### Option 1: AIDL over Binder

- Good, because compile-time type checked and automatically generates Java/Kotlin client stubs.
- Good, because natively supports `Parcelable` objects like `HardwareBuffer` and `ParcelFileDescriptor`.
- Good, because built-in `linkToDeath` allows deterministic recovery when `:render` crashes.
- Bad, because requires `buildFeatures { aidl = true }` in Gradle.

### Option 2: Android Messenger

- Good, because simple `Message` dispatch based on Android `Handler`.
- Bad, because messages rely on integer `what` codes and untyped `Bundle` payloads, making API contracts brittle and prone to runtime bugs.
- Bad, because synchronous responses require awkward `replyTo` callbacks or manual latch synchronization.

### Option 3: Unix Domain Sockets / Pipes

- Good, because independent of Android framework Binder driver.
- Bad, because requires manual framing, socket lifecycle management, custom `sendmsg(SCM_RIGHTS)` for passing fds, and JNI/C++ IPC glue.
- Bad, because does not integrate with Android Service lifecycle (`BIND_AUTO_CREATE`, system process management).

## Links

- [PRD.md §4.2 Threading and process model](../PRD.md#42-threading-and-process-model-critical)
- [PRD.md §4.3 Pixel transport](../PRD.md#43-pixel-transport)

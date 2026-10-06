#include <jni.h>
#include <android/log.h>
#include <android/hardware_buffer.h>
#include <android/hardware_buffer_jni.h>
#include <unistd.h>
#include <fcntl.h>
#include <pthread.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <cerrno>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include "fpdfview.h"
#include "fpdf_doc.h"
#include "fpdf_progressive.h"

enum RenderTileStatus {
    RENDER_TILE_SUCCESS = 0,
    RENDER_TILE_ERR_INVALID_PARAM = 1,
    RENDER_TILE_ERR_BUFFER_TOO_SMALL = 2,
    RENDER_TILE_ERR_UNSUPPORTED_FORMAT = 3,
    RENDER_TILE_ERR_LOCK_FAILED = 4,
    RENDER_TILE_ERR_PAGE_NOT_FOUND = 5,
    RENDER_TILE_ERR_PDFIUM = 6,
    RENDER_TILE_ERR_CANCELLED = 7
};


#define TAG "TerbacaPdfiumNative"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

// RAII lock guard for pthread_mutex_t (built directly into Android Bionic libc)
class PthreadGuard {
public:
    explicit PthreadGuard(pthread_mutex_t& mutex) : mutex_(mutex) {
        pthread_mutex_lock(&mutex_);
    }
    ~PthreadGuard() {
        pthread_mutex_unlock(&mutex_);
    }
    PthreadGuard(const PthreadGuard&) = delete;
    PthreadGuard& operator=(const PthreadGuard&) = delete;
private:
    pthread_mutex_t& mutex_;
};

// Global mutex ensuring thread safety across all PDFium interactions (PRD §4.2)
static pthread_mutex_t s_pdfium_mutex = PTHREAD_MUTEX_INITIALIZER;
static bool s_library_initialized = false;

struct FdFileContext {
    int fd;
    size_t file_len;
};

// Custom FPDF_FILEACCESS callback backed by pread on the file descriptor (PRD F-OPEN-3)
int FdGetBlock(void* param, unsigned long position, unsigned char* pBuf, unsigned long size) {
    if (!param || !pBuf) {
        return 0;
    }

    auto* ctx = static_cast<FdFileContext*>(param);
    if (position >= ctx->file_len) {
        return 0;
    }

    size_t bytes_to_read = size;
    if (position + bytes_to_read > ctx->file_len) {
        bytes_to_read = ctx->file_len - position;
    }

    size_t total_read = 0;
    while (total_read < bytes_to_read) {
        ssize_t n = pread(
            ctx->fd,
            pBuf + total_read,
            bytes_to_read - total_read,
            static_cast<off_t>(position + total_read)
        );

        if (n < 0) {
            if (errno == EINTR) {
                continue;
            }
            LOGE("pread failed at position %lu: %s (errno %d)", position, strerror(errno), errno);
            return 0;
        }

        if (n == 0) {
            break; // Unexpected end of file
        }

        total_read += static_cast<size_t>(n);
    }

    return (total_read == bytes_to_read) ? 1 : 0;
}

struct NativeDocument {
    FPDF_DOCUMENT doc = nullptr;
    FPDF_FILEACCESS file_access{};
    FdFileContext ctx{};
    int dup_fd = -1;
};

struct JniPauseHandler : public IFSDK_PAUSE {
    JNIEnv* env = nullptr;
    jobject token = nullptr;
    jmethodID isCancelledMethod = nullptr;
    bool cancelled = false;

    static FPDF_BOOL NeedToPause(IFSDK_PAUSE* pThis) {
        if (!pThis) return 0;
        auto* self = static_cast<JniPauseHandler*>(pThis);
        if (self->cancelled) return 1;
        if (self->token && self->isCancelledMethod && self->env) {
            jboolean isCancelled = self->env->CallBooleanMethod(self->token, self->isCancelledMethod);
            if (self->env->ExceptionCheck()) {
                self->env->ExceptionClear();
                return 0;
            }
            if (isCancelled) {
                self->cancelled = true;
                return 1;
            }
        }
        return 0;
    }
};

static int RenderPageTile(
    FPDF_DOCUMENT doc,
    int pageIndex,
    void* buffer,
    int tileWidth,
    int tileHeight,
    int stride,
    int fullWidth,
    int fullHeight,
    int startX,
    int startY,
    jobject cancellationSignal,
    JNIEnv* env
) {
    if (!doc || !buffer || tileWidth <= 0 || tileHeight <= 0 || fullWidth <= 0 || fullHeight <= 0 ||
        tileWidth > 4096 || tileHeight > 4096 || fullWidth > 65536 || fullHeight > 65536) {
        LOGE("Invalid parameters for RenderPageTile: doc=%p buffer=%p tile=%dx%d full=%dx%d",
             doc, buffer, tileWidth, tileHeight, fullWidth, fullHeight);
        return RENDER_TILE_ERR_INVALID_PARAM;
    }

    FPDF_PAGE page = FPDF_LoadPage(doc, pageIndex);
    if (!page) {
        LOGE("Failed to load page %d for tile rendering", pageIndex);
        return RENDER_TILE_ERR_PAGE_NOT_FOUND;
    }

    FPDF_BITMAP bitmap = FPDFBitmap_CreateEx(tileWidth, tileHeight, FPDFBitmap_BGRA, buffer, stride);
    if (!bitmap) {
        LOGE("FPDFBitmap_CreateEx failed for %dx%d stride %d", tileWidth, tileHeight, stride);
        FPDF_ClosePage(page);
        return RENDER_TILE_ERR_PDFIUM;
    }

    // Initialize background to opaque white (0xFFFFFFFF in ARGB format)
    FPDFBitmap_FillRect(bitmap, 0, 0, tileWidth, tileHeight, 0xFFFFFFFF);

    JniPauseHandler pauseHandler{};
    pauseHandler.version = 1;
    pauseHandler.NeedToPauseNow = JniPauseHandler::NeedToPause;
    pauseHandler.user = nullptr;
    pauseHandler.env = env;
    pauseHandler.token = cancellationSignal;
    pauseHandler.cancelled = false;

    if (cancellationSignal != nullptr && env != nullptr) {
        jclass tokenCls = env->GetObjectClass(cancellationSignal);
        pauseHandler.isCancelledMethod = env->GetMethodID(tokenCls, "get", "()Z");
        if (!pauseHandler.isCancelledMethod) {
            env->ExceptionClear();
            LOGW("Could not find get() method on cancellationSignal");
        } else {
            jboolean isCancelled = env->CallBooleanMethod(cancellationSignal, pauseHandler.isCancelledMethod);
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
            } else if (isCancelled) {
                pauseHandler.cancelled = true;
                FPDFBitmap_Destroy(bitmap);
                FPDF_ClosePage(page);
                return RENDER_TILE_ERR_CANCELLED;
            }
        }
    }

    int render_status = FPDF_RenderPageBitmap_Start(
        bitmap,
        page,
        -startX,
        -startY,
        fullWidth,
        fullHeight,
        0, // rotate
        FPDF_REVERSE_BYTE_ORDER | FPDF_ANNOT,
        &pauseHandler
    );

    while (render_status == FPDF_RENDER_TOBECONTINUED) {
        if (pauseHandler.cancelled) {
            break;
        }
        render_status = FPDF_RenderPage_Continue(page, &pauseHandler);
    }

    FPDF_RenderPage_Close(page);
    FPDFBitmap_Destroy(bitmap);
    FPDF_ClosePage(page);

    if (pauseHandler.cancelled) {
        LOGI("Tile render cancelled for page %d", pageIndex);
        return RENDER_TILE_ERR_CANCELLED;
    }

    if (render_status != FPDF_RENDER_DONE) {
        LOGE("Progressive tile render failed with status %d", render_status);
        return RENDER_TILE_ERR_PDFIUM;
    }

    return RENDER_TILE_SUCCESS;
}

} // namespace


extern "C" {

JNIEXPORT void JNICALL
Java_app_terbaca_core_pdfium_PdfiumNative_nativeInitLibrary(
        JNIEnv* /* env */,
        jclass /* clazz */) {
    PthreadGuard lock(s_pdfium_mutex);
    if (!s_library_initialized) {
        FPDF_LIBRARY_CONFIG config;
        config.version = 2;
        config.m_pUserFontPaths = nullptr;
        config.m_pIsolate = nullptr;
        config.m_v8EmbedderSlot = 0;
        FPDF_InitLibraryWithConfig(&config);
        s_library_initialized = true;
        LOGI("PDFium library initialized successfully");
    }
}

JNIEXPORT void JNICALL
Java_app_terbaca_core_pdfium_PdfiumNative_nativeDestroyLibrary(
        JNIEnv* /* env */,
        jclass /* clazz */) {
    PthreadGuard lock(s_pdfium_mutex);
    if (s_library_initialized) {
        FPDF_DestroyLibrary();
        s_library_initialized = false;
        LOGI("PDFium library destroyed");
    }
}

JNIEXPORT jlong JNICALL
Java_app_terbaca_core_pdfium_PdfiumNative_nativeOpenDocument(
        JNIEnv* env,
        jclass /* clazz */,
        jint fd,
        jbyteArray passwordBytes,
        jintArray outError) {
    auto set_error = [&](jint err_code) {
        if (outError != nullptr) {
            env->SetIntArrayRegion(outError, 0, 1, &err_code);
        }
    };

    if (fd < 0) {
        LOGE("Invalid file descriptor: %d", fd);
        set_error(2); // FPDF_ERR_FILE
        return 0;
    }

    int dup_fd = dup(fd);
    if (dup_fd < 0) {
        LOGE("Failed to dup file descriptor %d: %s", fd, strerror(errno));
        set_error(2); // FPDF_ERR_FILE
        return 0;
    }
    fcntl(dup_fd, F_SETFD, FD_CLOEXEC);

    struct stat st{};
    if (fstat(dup_fd, &st) != 0) {
        LOGE("fstat failed on dup fd %d: %s", dup_fd, strerror(errno));
        close(dup_fd);
        set_error(2); // FPDF_ERR_FILE
        return 0;
    }

    if (st.st_size <= 0) {
        LOGE("File descriptor %d is empty (size=%ld)", dup_fd, static_cast<long>(st.st_size));
        close(dup_fd);
        set_error(3); // FPDF_ERR_FORMAT
        return 0;
    }

    auto* doc_handle = new NativeDocument();
    doc_handle->dup_fd = dup_fd;
    doc_handle->ctx.fd = dup_fd;
    doc_handle->ctx.file_len = static_cast<size_t>(st.st_size);

    doc_handle->file_access.m_FileLen = static_cast<unsigned long>(st.st_size);
    doc_handle->file_access.m_GetBlock = FdGetBlock;
    doc_handle->file_access.m_Param = &doc_handle->ctx;

    const char* c_password = nullptr;
    jbyte* password_elements = nullptr;
    if (passwordBytes != nullptr) {
        password_elements = env->GetByteArrayElements(passwordBytes, nullptr);
        c_password = reinterpret_cast<const char*>(password_elements);
    }

    unsigned long last_err = 0;
    {
        PthreadGuard lock(s_pdfium_mutex);
        doc_handle->doc = FPDF_LoadCustomDocument(&doc_handle->file_access, c_password);
        if (!doc_handle->doc) {
            last_err = FPDF_GetLastError();
        }
    }

    if (password_elements != nullptr) {
        env->ReleaseByteArrayElements(passwordBytes, password_elements, JNI_ABORT);
    }

    if (!doc_handle->doc) {
        LOGE("FPDF_LoadCustomDocument failed with error code: %lu", last_err);
        close(doc_handle->dup_fd);
        delete doc_handle;
        set_error(static_cast<jint>(last_err));
        return 0;
    }

    set_error(0); // FPDF_ERR_SUCCESS
    LOGI("PDFium document opened: fd=%d, size=%zu bytes, handle=%p",
         dup_fd, doc_handle->ctx.file_len, doc_handle);
    return reinterpret_cast<jlong>(doc_handle);
}

JNIEXPORT jint JNICALL
Java_app_terbaca_core_pdfium_PdfiumNative_nativeGetPageCount(
        JNIEnv* /* env */,
        jclass /* clazz */,
        jlong docPtr) {
    if (docPtr == 0) {
        return 0;
    }
    auto* doc_handle = reinterpret_cast<NativeDocument*>(docPtr);
    PthreadGuard lock(s_pdfium_mutex);
    return FPDF_GetPageCount(doc_handle->doc);
}

JNIEXPORT jboolean JNICALL
Java_app_terbaca_core_pdfium_PdfiumNative_nativeGetPageSize(
        JNIEnv* env,
        jclass /* clazz */,
        jlong docPtr,
        jint pageIndex,
        jfloatArray outSize) {
    if (docPtr == 0 || outSize == nullptr) {
        return JNI_FALSE;
    }
    auto* doc_handle = reinterpret_cast<NativeDocument*>(docPtr);

    double width = 0.0;
    double height = 0.0;
    int res = 0;
    {
        PthreadGuard lock(s_pdfium_mutex);
        res = FPDF_GetPageSizeByIndex(doc_handle->doc, pageIndex, &width, &height);
    }

    if (res == 0) {
        return JNI_FALSE;
    }

    jfloat size_buf[2] = {static_cast<jfloat>(width), static_cast<jfloat>(height)};
    env->SetFloatArrayRegion(outSize, 0, 2, size_buf);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_app_terbaca_core_pdfium_PdfiumNative_nativeCloseDocument(
        JNIEnv* /* env */,
        jclass /* clazz */,
        jlong docPtr) {
    if (docPtr == 0) {
        return;
    }
    auto* doc_handle = reinterpret_cast<NativeDocument*>(docPtr);
    {
        PthreadGuard lock(s_pdfium_mutex);
        if (doc_handle->doc) {
            FPDF_CloseDocument(doc_handle->doc);
            doc_handle->doc = nullptr;
        }
    }
    if (doc_handle->dup_fd >= 0) {
        close(doc_handle->dup_fd);
        doc_handle->dup_fd = -1;
    }
    delete doc_handle;
    LOGI("PDFium document closed: %p", doc_handle);
}

JNIEXPORT jint JNICALL
Java_app_terbaca_core_pdfium_PdfiumNative_nativeGetLastError(
        JNIEnv* /* env */,
        jclass /* clazz */) {
    PthreadGuard lock(s_pdfium_mutex);
    return static_cast<jint>(FPDF_GetLastError());
}

JNIEXPORT jint JNICALL
Java_app_terbaca_core_pdfium_PdfiumNative_nativeRenderTileToHardwareBuffer(
        JNIEnv* env,
        jclass /* clazz */,
        jlong docPtr,
        jint pageIndex,
        jobject hardwareBufferObj,
        jint fullWidth,
        jint fullHeight,
        jint startX,
        jint startY,
        jint tileWidth,
        jint tileHeight,
        jobject cancellationSignal) {
    if (docPtr == 0 || hardwareBufferObj == nullptr) {
        LOGE("Invalid parameters for nativeRenderTileToHardwareBuffer: docPtr=%ld buffer=%p",
             static_cast<long>(docPtr), hardwareBufferObj);
        return RENDER_TILE_ERR_INVALID_PARAM;
    }

    if (tileWidth <= 0 || tileHeight <= 0 || tileWidth > 4096 || tileHeight > 4096 ||
        fullWidth <= 0 || fullHeight <= 0 || fullWidth > 65536 || fullHeight > 65536) {
        LOGE("Invalid dimensions for nativeRenderTileToHardwareBuffer: tile=%dx%d full=%dx%d",
             tileWidth, tileHeight, fullWidth, fullHeight);
        return RENDER_TILE_ERR_INVALID_PARAM;
    }

    auto* doc_handle = reinterpret_cast<NativeDocument*>(docPtr);

    AHardwareBuffer* hw_buffer = AHardwareBuffer_fromHardwareBuffer(env, hardwareBufferObj);
    if (!hw_buffer) {
        LOGE("AHardwareBuffer_fromHardwareBuffer returned null");
        return RENDER_TILE_ERR_LOCK_FAILED;
    }

    AHardwareBuffer_Desc desc{};
    AHardwareBuffer_describe(hw_buffer, &desc);

    if (desc.format != AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM) {
        LOGE("HardwareBuffer format is not R8G8B8A8_UNORM (format=%u)", desc.format);
        return RENDER_TILE_ERR_UNSUPPORTED_FORMAT;
    }

    if (static_cast<int>(desc.width) < tileWidth || static_cast<int>(desc.height) < tileHeight) {
        LOGE("HardwareBuffer dimensions %ux%u smaller than tile %dx%d",
             desc.width, desc.height, tileWidth, tileHeight);
        return RENDER_TILE_ERR_BUFFER_TOO_SMALL;
    }

    void* virtualAddress = nullptr;
    int lock_res = AHardwareBuffer_lock(
        hw_buffer,
        AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN,
        -1,
        nullptr,
        &virtualAddress
    );

    if (lock_res != 0 || !virtualAddress) {
        LOGE("AHardwareBuffer_lock failed with code: %d", lock_res);
        return RENDER_TILE_ERR_LOCK_FAILED;
    }

    int status = RENDER_TILE_SUCCESS;
    {
        PthreadGuard lock(s_pdfium_mutex);
        status = RenderPageTile(
            doc_handle->doc,
            pageIndex,
            virtualAddress,
            tileWidth,
            tileHeight,
            static_cast<int>(desc.stride * 4),
            fullWidth,
            fullHeight,
            startX,
            startY,
            cancellationSignal,
            env
        );
    }

    int unlock_res = AHardwareBuffer_unlock(hw_buffer, nullptr);
    if (unlock_res != 0) {
        LOGW("AHardwareBuffer_unlock failed with code: %d", unlock_res);
    }

    return status;
}

JNIEXPORT jint JNICALL
Java_app_terbaca_core_pdfium_PdfiumNative_nativeRenderTileToDirectBuffer(
        JNIEnv* env,
        jclass /* clazz */,
        jlong docPtr,
        jint pageIndex,
        jobject directBuffer,
        jint fullWidth,
        jint fullHeight,
        jint startX,
        jint startY,
        jint tileWidth,
        jint tileHeight,
        jint stride,
        jobject cancellationSignal) {
    if (docPtr == 0 || directBuffer == nullptr) {
        LOGE("Invalid parameters for nativeRenderTileToDirectBuffer: docPtr=%ld buffer=%p",
             static_cast<long>(docPtr), directBuffer);
        return RENDER_TILE_ERR_INVALID_PARAM;
    }

    if (tileWidth <= 0 || tileHeight <= 0 || tileWidth > 4096 || tileHeight > 4096 ||
        fullWidth <= 0 || fullHeight <= 0 || fullWidth > 65536 || fullHeight > 65536) {
        LOGE("Invalid dimensions for nativeRenderTileToDirectBuffer: tile=%dx%d full=%dx%d",
             tileWidth, tileHeight, fullWidth, fullHeight);
        return RENDER_TILE_ERR_INVALID_PARAM;
    }

    int actual_stride = stride > 0 ? stride : (tileWidth * 4);
    if (actual_stride < tileWidth * 4) {
        LOGE("stride %d is less than tileWidth * 4 (%d)", actual_stride, tileWidth * 4);
        return RENDER_TILE_ERR_INVALID_PARAM;
    }

    jlong capacity = env->GetDirectBufferCapacity(directBuffer);
    int64_t required_capacity = static_cast<int64_t>(actual_stride) * static_cast<int64_t>(tileHeight);
    if (capacity < required_capacity) {
        LOGE("directBuffer capacity %lld is smaller than required %lld",
             static_cast<long long>(capacity), static_cast<long long>(required_capacity));
        return RENDER_TILE_ERR_BUFFER_TOO_SMALL;
    }

    auto* doc_handle = reinterpret_cast<NativeDocument*>(docPtr);
    void* bufferAddress = env->GetDirectBufferAddress(directBuffer);
    if (!bufferAddress) {
        LOGE("GetDirectBufferAddress returned null");
        return RENDER_TILE_ERR_INVALID_PARAM;
    }

    int status = RENDER_TILE_SUCCESS;
    {
        PthreadGuard lock(s_pdfium_mutex);
        status = RenderPageTile(
            doc_handle->doc,
            pageIndex,
            bufferAddress,
            tileWidth,
            tileHeight,
            actual_stride,
            fullWidth,
            fullHeight,
            startX,
            startY,
            cancellationSignal,
            env
        );
    }

    return status;
}

JNIEXPORT jstring JNICALL
Java_app_terbaca_core_pdfium_PdfiumNative_nativeGetDocumentTitle(
        JNIEnv* env,
        jclass /* clazz */,
        jlong docPtr) {
    if (docPtr == 0) return nullptr;
    auto* doc_handle = reinterpret_cast<NativeDocument*>(docPtr);
    if (!doc_handle || !doc_handle->doc) return nullptr;

    PthreadGuard lock(s_pdfium_mutex);
    unsigned long len = FPDF_GetMetaText(doc_handle->doc, "Title", nullptr, 0);
    if (len <= 2) return nullptr;

    auto* buf = static_cast<unsigned char*>(malloc(len));
    if (!buf) return nullptr;

    unsigned long actual_len = FPDF_GetMetaText(doc_handle->doc, "Title", buf, len);
    if (actual_len <= 2) {
        free(buf);
        return nullptr;
    }

    jsize char_count = static_cast<jsize>((actual_len - 2) / 2);
    jstring result = env->NewString(reinterpret_cast<const jchar*>(buf), char_count);
    free(buf);
    return result;
}

} // extern "C"


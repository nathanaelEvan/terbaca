#include <jni.h>
#include <android/log.h>
#include <android/hardware_buffer.h>
#include <android/hardware_buffer_jni.h>
#include <unistd.h>
#include <fcntl.h>
#include <pthread.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <algorithm>
#include <cerrno>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include "fpdfview.h"
#include "fpdf_doc.h"
#include "fpdf_edit.h"
#include "fpdf_progressive.h"
#include "fpdf_text.h"

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

    unsigned char* buf = nullptr;
    jsize char_count = 0;

    {
        PthreadGuard lock(s_pdfium_mutex);
        unsigned long len = FPDF_GetMetaText(doc_handle->doc, "Title", nullptr, 0);
        if (len <= 2) return nullptr;

        buf = static_cast<unsigned char*>(malloc(len));
        if (!buf) return nullptr;

        unsigned long actual_len = FPDF_GetMetaText(doc_handle->doc, "Title", buf, len);
        unsigned long valid_len = std::min(actual_len, len);
        if (valid_len <= 2) {
            free(buf);
            return nullptr;
        }
        char_count = static_cast<jsize>((valid_len - 2) / 2);
    }

    jstring result = env->NewString(reinterpret_cast<const jchar*>(buf), char_count);
    free(buf);
    return result;
}

JNIEXPORT jstring JNICALL
Java_app_terbaca_core_pdfium_PdfiumNative_nativeGetPageText(
        JNIEnv* env,
        jclass /* clazz */,
        jlong docPtr,
        jint pageIndex) {
    if (docPtr == 0 || pageIndex < 0) return nullptr;
    auto* doc_handle = reinterpret_cast<NativeDocument*>(docPtr);
    if (!doc_handle || !doc_handle->doc) return nullptr;

    jstring result = nullptr;
    {
        PthreadGuard lock(s_pdfium_mutex);
        FPDF_PAGE page = FPDF_LoadPage(doc_handle->doc, pageIndex);
        if (!page) return nullptr;

        FPDF_TEXTPAGE text_page = FPDFText_LoadPage(page);
        if (!text_page) {
            FPDF_ClosePage(page);
            return nullptr;
        }

        int char_count = FPDFText_CountChars(text_page);
        if (char_count > 0) {
            auto* buffer = static_cast<unsigned short*>(malloc((static_cast<size_t>(char_count) + 1) * sizeof(unsigned short)));
            if (buffer) {
                int chars_written = FPDFText_GetText(text_page, 0, char_count, buffer);
                int len = (chars_written > 1) ? (chars_written - 1) : 0;
                if (len > 0) {
                    result = env->NewString(reinterpret_cast<const jchar*>(buffer), len);
                } else {
                    result = env->NewStringUTF("");
                }
                free(buffer);
            } else {
                result = env->NewStringUTF("");
            }
        } else {
            result = env->NewStringUTF("");
        }

        FPDFText_ClosePage(text_page);
        FPDF_ClosePage(page);
    }
    return result;
}

JNIEXPORT jfloatArray JNICALL
Java_app_terbaca_core_pdfium_PdfiumNative_nativeGetPageCharBoxes(
        JNIEnv* env,
        jclass /* clazz */,
        jlong docPtr,
        jint pageIndex) {
    if (docPtr == 0 || pageIndex < 0) return nullptr;
    auto* doc_handle = reinterpret_cast<NativeDocument*>(docPtr);
    if (!doc_handle || !doc_handle->doc) return nullptr;

    jfloatArray result = nullptr;
    {
        PthreadGuard lock(s_pdfium_mutex);
        FPDF_PAGE page = FPDF_LoadPage(doc_handle->doc, pageIndex);
        if (!page) return nullptr;

        FPDF_TEXTPAGE text_page = FPDFText_LoadPage(page);
        if (!text_page) {
            FPDF_ClosePage(page);
            return nullptr;
        }

        int char_count = FPDFText_CountChars(text_page);
        if (char_count > 0) {
            double page_height = FPDF_GetPageHeight(page);
            size_t total_floats = static_cast<size_t>(char_count) * 4;
            auto* boxes = static_cast<float*>(malloc(total_floats * sizeof(float)));
            if (boxes) {
                for (int i = 0; i < char_count; ++i) {
                    double left = 0, right = 0, bottom = 0, top = 0;
                    size_t base = static_cast<size_t>(i) * 4;
                    if (FPDFText_GetCharBox(text_page, i, &left, &right, &bottom, &top)) {
                        float b_left = static_cast<float>(left < right ? left : right);
                        float b_right = static_cast<float>(left > right ? left : right);
                        float b_top = static_cast<float>(page_height - (bottom > top ? bottom : top));
                        float b_bottom = static_cast<float>(page_height - (bottom < top ? bottom : top));
                        if (b_top > b_bottom) { float tmp = b_top; b_top = b_bottom; b_bottom = tmp; }
                        if (b_left > b_right) { float tmp = b_left; b_left = b_right; b_right = tmp; }
                        boxes[base] = b_left;
                        boxes[base + 1] = b_top;
                        boxes[base + 2] = b_right;
                        boxes[base + 3] = b_bottom;
                    } else {
                        boxes[base] = 0.0f;
                        boxes[base + 1] = 0.0f;
                        boxes[base + 2] = 0.0f;
                        boxes[base + 3] = 0.0f;
                    }
                }
                result = env->NewFloatArray(static_cast<jsize>(total_floats));
                if (result) {
                    env->SetFloatArrayRegion(result, 0, static_cast<jsize>(total_floats), boxes);
                }
                free(boxes);
            }
        }

        FPDFText_ClosePage(text_page);
        FPDF_ClosePage(page);
    }
    return result;
}

JNIEXPORT jfloatArray JNICALL
Java_app_terbaca_core_pdfium_PdfiumNative_nativeGetImageBounds(
        JNIEnv* env,
        jclass /* clazz */,
        jlong docPtr,
        jint pageIndex) {
    if (docPtr == 0 || pageIndex < 0) return nullptr;
    auto* doc_handle = reinterpret_cast<NativeDocument*>(docPtr);
    if (!doc_handle || !doc_handle->doc) return nullptr;

    jfloatArray result = nullptr;
    {
        PthreadGuard lock(s_pdfium_mutex);
        FPDF_PAGE page = FPDF_LoadPage(doc_handle->doc, pageIndex);
        if (!page) return nullptr;

        int obj_count = FPDFPage_CountObjects(page);
        if (obj_count > 0) {
            double page_height = FPDF_GetPageHeight(page);
            auto* temp_bounds = static_cast<float*>(malloc(static_cast<size_t>(obj_count) * 4 * sizeof(float)));
            if (temp_bounds) {
                int img_count = 0;
                for (int i = 0; i < obj_count; ++i) {
                    FPDF_PAGEOBJECT obj = FPDFPage_GetObject(page, i);
                    if (!obj) continue;
                    if (FPDFPageObj_GetType(obj) == FPDF_PAGEOBJ_IMAGE) {
                        float left = 0, bottom = 0, right = 0, top = 0;
                        if (FPDFPageObj_GetBounds(obj, &left, &bottom, &right, &top)) {
                            float b_left = left < right ? left : right;
                            float b_right = left > right ? left : right;
                            float b_top = static_cast<float>(page_height - (bottom > top ? bottom : top));
                            float b_bottom = static_cast<float>(page_height - (bottom < top ? bottom : top));
                            if (b_top > b_bottom) { float tmp = b_top; b_top = b_bottom; b_bottom = tmp; }
                            if (b_left > b_right) { float tmp = b_left; b_left = b_right; b_right = tmp; }
                            int base = img_count * 4;
                            temp_bounds[base] = b_left;
                            temp_bounds[base + 1] = b_top;
                            temp_bounds[base + 2] = b_right;
                            temp_bounds[base + 3] = b_bottom;
                            img_count++;
                        }
                    }
                }
                if (img_count > 0) {
                    result = env->NewFloatArray(img_count * 4);
                    if (result) {
                        env->SetFloatArrayRegion(result, 0, img_count * 4, temp_bounds);
                    }
                }
                free(temp_bounds);
            }
        }
        FPDF_ClosePage(page);
    }
    return result;
}

JNIEXPORT jobjectArray JNICALL
Java_app_terbaca_core_pdfium_PdfiumNative_nativeGetPageLinks(
        JNIEnv* env,
        jclass /* clazz */,
        jlong docPtr,
        jint pageIndex) {
    if (docPtr == 0 || pageIndex < 0) return nullptr;
    auto* doc_handle = reinterpret_cast<NativeDocument*>(docPtr);
    if (!doc_handle || !doc_handle->doc) return nullptr;

    jclass parcelClass = env->FindClass("app/terbaca/core/pdfium/PageLinkParcel");
    if (!parcelClass) return nullptr;
    jmethodID parcelInit = env->GetMethodID(parcelClass, "<init>", "()V");
    jfieldID fLeft = env->GetFieldID(parcelClass, "left", "F");
    jfieldID fTop = env->GetFieldID(parcelClass, "top", "F");
    jfieldID fRight = env->GetFieldID(parcelClass, "right", "F");
    jfieldID fBottom = env->GetFieldID(parcelClass, "bottom", "F");
    jfieldID fPage = env->GetFieldID(parcelClass, "targetPageIndex", "I");
    jfieldID fX = env->GetFieldID(parcelClass, "targetX", "F");
    jfieldID fY = env->GetFieldID(parcelClass, "targetY", "F");
    jfieldID fUri = env->GetFieldID(parcelClass, "uri", "Ljava/lang/String;");

    jclass listClass = env->FindClass("java/util/ArrayList");
    if (!listClass) return nullptr;
    jmethodID listInit = env->GetMethodID(listClass, "<init>", "()V");
    jmethodID listAdd = env->GetMethodID(listClass, "add", "(Ljava/lang/Object;)Z");
    jmethodID listSize = env->GetMethodID(listClass, "size", "()I");
    jmethodID listToArray = env->GetMethodID(listClass, "toArray", "([Ljava/lang/Object;)[Ljava/lang/Object;");

    jobject list = env->NewObject(listClass, listInit);
    if (!list) return nullptr;

    {
        PthreadGuard lock(s_pdfium_mutex);
        FPDF_PAGE page = FPDF_LoadPage(doc_handle->doc, pageIndex);
        if (page) {
            double page_height = FPDF_GetPageHeight(page);
            int start_pos = 0;
            FPDF_LINK link_annot = nullptr;

            while (FPDFLink_Enumerate(page, &start_pos, &link_annot)) {
                if (!link_annot) continue;

                FS_RECTF rect{};
                if (!FPDFLink_GetAnnotRect(link_annot, &rect)) continue;

                float b_left = rect.left < rect.right ? rect.left : rect.right;
                float b_right = rect.left > rect.right ? rect.left : rect.right;
                float b_top = static_cast<float>(page_height - (rect.bottom > rect.top ? rect.bottom : rect.top));
                float b_bottom = static_cast<float>(page_height - (rect.bottom < rect.top ? rect.bottom : rect.top));
                if (b_top > b_bottom) { float tmp = b_top; b_top = b_bottom; b_bottom = tmp; }
                if (b_left > b_right) { float tmp = b_left; b_left = b_right; b_right = tmp; }

                int target_page = -1;
                float target_x = 0.0f / 0.0f; // NaN
                float target_y = 0.0f / 0.0f; // NaN
                jstring juri = nullptr;

                FPDF_DEST dest = FPDFLink_GetDest(doc_handle->doc, link_annot);
                if (!dest) {
                    FPDF_ACTION action = FPDFLink_GetAction(link_annot);
                    if (action) {
                        unsigned long action_type = FPDFAction_GetType(action);
                        if (action_type == PDFACTION_GOTO) {
                            dest = FPDFAction_GetDest(doc_handle->doc, action);
                        } else if (action_type == PDFACTION_URI) {
                            unsigned long uri_len = FPDFAction_GetURIPath(doc_handle->doc, action, nullptr, 0);
                            if (uri_len > 0) {
                                auto* uri_buf = static_cast<char*>(malloc(uri_len));
                                if (uri_buf) {
                                    FPDFAction_GetURIPath(doc_handle->doc, action, uri_buf, uri_len);
                                    uri_buf[uri_len - 1] = ' ';
                                    // URIs are 7-bit ASCII (PDF 32000 12.6.4.7); other bytes would be
                                    // invalid modified UTF-8 and abort NewStringUTF.
                                    for (unsigned long k = 0; k + 1 < uri_len; ++k) {
                                        if (static_cast<unsigned char>(uri_buf[k]) > 0x7F) uri_buf[k] = '?';
                                    }
                                    juri = env->NewStringUTF(uri_buf);
                                    free(uri_buf);
                                }
                            }
                        }
                    }
                }

                if (dest) {
                    int page_idx = FPDFDest_GetDestPageIndex(doc_handle->doc, dest);
                    if (page_idx >= 0) {
                        target_page = page_idx;
                        FPDF_BOOL hasX = 0, hasY = 0, hasZoom = 0;
                        FS_FLOAT x = 0, y = 0, zoom = 0;
                        if (FPDFDest_GetLocationInPage(dest, &hasX, &hasY, &hasZoom, &x, &y, &zoom)) {
                            if (hasX) target_x = x;
                            if (hasY) {
                                double dest_w = 0, dest_h = 0;
                                FPDF_GetPageSizeByIndex(doc_handle->doc, target_page, &dest_w, &dest_h);
                                target_y = static_cast<float>(dest_h - y);
                            }
                        }
                    }
                }

                jobject obj = env->NewObject(parcelClass, parcelInit);
                if (obj) {
                    env->SetFloatField(obj, fLeft, b_left);
                    env->SetFloatField(obj, fTop, b_top);
                    env->SetFloatField(obj, fRight, b_right);
                    env->SetFloatField(obj, fBottom, b_bottom);
                    env->SetIntField(obj, fPage, target_page);
                    env->SetFloatField(obj, fX, target_x);
                    env->SetFloatField(obj, fY, target_y);
                    if (juri) {
                        env->SetObjectField(obj, fUri, juri);
                        env->DeleteLocalRef(juri);
                    }
                    env->CallBooleanMethod(list, listAdd, obj);
                    env->DeleteLocalRef(obj);
                }
            }
            FPDF_ClosePage(page);
        }
    }

    jint count = env->CallIntMethod(list, listSize);
    jobjectArray emptyArray = env->NewObjectArray(count, parcelClass, nullptr);
    jobjectArray resultArray = static_cast<jobjectArray>(env->CallObjectMethod(list, listToArray, emptyArray));
    return resultArray;
}

static void TraverseBookmarksHelper(
        JNIEnv* env,
        FPDF_DOCUMENT doc,
        FPDF_BOOKMARK current,
        int depth,
        int* count,
        jobject list,
        jmethodID listAdd,
        jclass parcelClass,
        jmethodID parcelInit,
        jfieldID fTitle,
        jfieldID fPage,
        jfieldID fX,
        jfieldID fY,
        jfieldID fDepth) {
    if (depth > 16 || *count >= 2000) return;
    // The limit is checked per sibling too: a malformed /Next chain can loop back on itself.
    while (current && *count < 2000) {
        (*count)++;
        jobject obj = env->NewObject(parcelClass, parcelInit);
        if (obj) {
            unsigned long len = FPDFBookmark_GetTitle(current, nullptr, 0);
            if (len > 2) {
                auto* buf = static_cast<unsigned char*>(malloc(len));
                if (buf) {
                    FPDFBookmark_GetTitle(current, buf, len);
                    jsize char_count = static_cast<jsize>((len - 2) / 2);
                    jstring jtitle = env->NewString(reinterpret_cast<const jchar*>(buf), char_count);
                    env->SetObjectField(obj, fTitle, jtitle);
                    env->DeleteLocalRef(jtitle);
                    free(buf);
                }
            } else {
                jstring emptyStr = env->NewStringUTF("");
                env->SetObjectField(obj, fTitle, emptyStr);
                env->DeleteLocalRef(emptyStr);
            }

            int target_page = -1;
            float dest_x = 0.0f / 0.0f; // NaN
            float dest_y = 0.0f / 0.0f; // NaN

            FPDF_DEST dest = FPDFBookmark_GetDest(doc, current);
            if (!dest) {
                FPDF_ACTION action = FPDFBookmark_GetAction(current);
                if (action && FPDFAction_GetType(action) == PDFACTION_GOTO) {
                    dest = FPDFAction_GetDest(doc, action);
                }
            }

            if (dest) {
                int page_idx = FPDFDest_GetDestPageIndex(doc, dest);
                if (page_idx >= 0) {
                    target_page = page_idx;
                    FPDF_BOOL hasX = 0, hasY = 0, hasZoom = 0;
                    FS_FLOAT x = 0, y = 0, zoom = 0;
                    if (FPDFDest_GetLocationInPage(dest, &hasX, &hasY, &hasZoom, &x, &y, &zoom)) {
                        if (hasX) dest_x = x;
                        if (hasY) {
                            double page_w = 0, page_h = 0;
                            FPDF_GetPageSizeByIndex(doc, page_idx, &page_w, &page_h);
                            dest_y = static_cast<float>(page_h - y);
                        }
                    }
                }
            }

            env->SetIntField(obj, fPage, target_page);
            env->SetFloatField(obj, fX, dest_x);
            env->SetFloatField(obj, fY, dest_y);
            env->SetIntField(obj, fDepth, depth);

            env->CallBooleanMethod(list, listAdd, obj);
            env->DeleteLocalRef(obj);
        }

        FPDF_BOOKMARK first_child = FPDFBookmark_GetFirstChild(doc, current);
        if (first_child) {
            TraverseBookmarksHelper(env, doc, first_child, depth + 1, count, list, listAdd,
                                    parcelClass, parcelInit, fTitle, fPage, fX, fY, fDepth);
        }

        current = FPDFBookmark_GetNextSibling(doc, current);
    }
}

JNIEXPORT jobjectArray JNICALL
Java_app_terbaca_core_pdfium_PdfiumNative_nativeGetOutline(
        JNIEnv* env,
        jclass /* clazz */,
        jlong docPtr) {
    if (docPtr == 0) return nullptr;
    auto* doc_handle = reinterpret_cast<NativeDocument*>(docPtr);
    if (!doc_handle || !doc_handle->doc) return nullptr;

    jclass parcelClass = env->FindClass("app/terbaca/core/pdfium/OutlineItemParcel");
    if (!parcelClass) return nullptr;
    jmethodID parcelInit = env->GetMethodID(parcelClass, "<init>", "()V");
    jfieldID fTitle = env->GetFieldID(parcelClass, "title", "Ljava/lang/String;");
    jfieldID fPage = env->GetFieldID(parcelClass, "pageIndex", "I");
    jfieldID fX = env->GetFieldID(parcelClass, "destX", "F");
    jfieldID fY = env->GetFieldID(parcelClass, "destY", "F");
    jfieldID fDepth = env->GetFieldID(parcelClass, "depth", "I");

    jclass listClass = env->FindClass("java/util/ArrayList");
    if (!listClass) return nullptr;
    jmethodID listInit = env->GetMethodID(listClass, "<init>", "()V");
    jmethodID listAdd = env->GetMethodID(listClass, "add", "(Ljava/lang/Object;)Z");
    jmethodID listSize = env->GetMethodID(listClass, "size", "()I");
    jmethodID listToArray = env->GetMethodID(listClass, "toArray", "([Ljava/lang/Object;)[Ljava/lang/Object;");

    jobject list = env->NewObject(listClass, listInit);
    if (!list) return nullptr;

    {
        PthreadGuard lock(s_pdfium_mutex);
        FPDF_BOOKMARK first_root = FPDFBookmark_GetFirstChild(doc_handle->doc, nullptr);
        if (first_root) {
            int count = 0;
            TraverseBookmarksHelper(env, doc_handle->doc, first_root, 0, &count, list, listAdd,
                                    parcelClass, parcelInit, fTitle, fPage, fX, fY, fDepth);
        }
    }

    jint count = env->CallIntMethod(list, listSize);
    if (count <= 0) return nullptr;

    jobjectArray emptyArray = env->NewObjectArray(count, parcelClass, nullptr);
    jobjectArray resultArray = static_cast<jobjectArray>(env->CallObjectMethod(list, listToArray, emptyArray));
    return resultArray;
}

} // extern "C"


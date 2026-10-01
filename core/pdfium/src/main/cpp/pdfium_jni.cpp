#include <jni.h>
#include <android/log.h>
#include <unistd.h>
#include <fcntl.h>
#include <pthread.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <cerrno>
#include <cstring>
#include "fpdfview.h"

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
        jstring password) {
    if (fd < 0) {
        LOGE("Invalid file descriptor: %d", fd);
        return 0;
    }

    int dup_fd = dup(fd);
    if (dup_fd < 0) {
        LOGE("Failed to dup file descriptor %d: %s", fd, strerror(errno));
        return 0;
    }
    fcntl(dup_fd, F_SETFD, FD_CLOEXEC);

    struct stat st{};
    if (fstat(dup_fd, &st) != 0) {
        LOGE("fstat failed on dup fd %d: %s", dup_fd, strerror(errno));
        close(dup_fd);
        return 0;
    }

    if (st.st_size <= 0) {
        LOGE("File descriptor %d is empty (size=%ld)", dup_fd, static_cast<long>(st.st_size));
        close(dup_fd);
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
    if (password != nullptr) {
        c_password = env->GetStringUTFChars(password, nullptr);
    }

    {
        PthreadGuard lock(s_pdfium_mutex);
        doc_handle->doc = FPDF_LoadCustomDocument(&doc_handle->file_access, c_password);
    }

    if (c_password != nullptr) {
        env->ReleaseStringUTFChars(password, c_password);
    }

    if (!doc_handle->doc) {
        unsigned long last_err = FPDF_GetLastError();
        LOGE("FPDF_LoadCustomDocument failed with error code: %lu", last_err);
        close(doc_handle->dup_fd);
        delete doc_handle;
        return 0;
    }

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

} // extern "C"

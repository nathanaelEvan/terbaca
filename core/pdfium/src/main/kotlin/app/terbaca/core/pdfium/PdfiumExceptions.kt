package app.terbaca.core.pdfium

open class PdfiumException(
    message: String,
    val errorCode: Int = ERROR_UNKNOWN,
    cause: Throwable? = null
) : Exception(message, cause) {
    companion object {
        const val ERROR_SUCCESS = 0
        const val ERROR_UNKNOWN = 1
        const val ERROR_FILE = 2
        const val ERROR_FORMAT = 3
        const val ERROR_PASSWORD = 4
        const val ERROR_SECURITY = 5
        const val ERROR_PAGE = 6
        const val ERROR_HANDLE = 7

        fun fromErrorCode(code: Int, customMessage: String? = null): PdfiumException {
            val message = customMessage ?: when (code) {
                ERROR_UNKNOWN -> "Unknown PDFium error"
                ERROR_FILE -> "File not found or could not be opened"
                ERROR_FORMAT -> "File is not a valid PDF or is corrupted"
                ERROR_PASSWORD -> "PDF is password protected or password was incorrect"
                ERROR_SECURITY -> "Unsupported PDF security or encryption scheme"
                ERROR_PAGE -> "Page not found or content error"
                ERROR_HANDLE -> "Unknown or stale document handle"
                else -> "PDFium error (code $code)"
            }
            return when (code) {
                ERROR_PASSWORD -> PdfPasswordException(message)
                ERROR_FORMAT -> PdfCorruptException(message)
                ERROR_FILE -> PdfFileException(message)
                ERROR_SECURITY -> PdfSecurityException(message)
                ERROR_PAGE -> PdfPageNotFoundException(message)
                ERROR_HANDLE -> PdfInvalidHandleException(message)
                else -> PdfiumException(message, code)
            }
        }
    }
}

class PdfPasswordException(message: String) : PdfiumException(message, ERROR_PASSWORD)
class PdfCorruptException(message: String) : PdfiumException(message, ERROR_FORMAT)
class PdfFileException(message: String) : PdfiumException(message, ERROR_FILE)
class PdfSecurityException(message: String) : PdfiumException(message, ERROR_SECURITY)
class PdfPageNotFoundException(message: String) : PdfiumException(message, ERROR_PAGE)
class PdfInvalidHandleException(message: String) : PdfiumException(message, ERROR_HANDLE)

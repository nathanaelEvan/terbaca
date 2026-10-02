package app.terbaca.core.pdfium;

import android.hardware.HardwareBuffer;
import android.os.SharedMemory;

parcelable RenderResult {
    int errorCode;
    String errorMessage;
    long handle;
    int pageCount;
    float[] pageSize;
    HardwareBuffer hardwareBuffer;
    SharedMemory sharedMemory;
    int bufferWidth;
    int bufferHeight;
    int bufferStride;
}


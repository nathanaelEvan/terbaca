package app.terbaca.core.pdfium;

parcelable RenderResult {
    int errorCode;
    String errorMessage;
    long handle;
    int pageCount;
    float[] pageSize;
}

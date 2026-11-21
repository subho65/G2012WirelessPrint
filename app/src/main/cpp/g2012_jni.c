#include <jni.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <android/log.h>
#include <gutenprint/gutenprint.h>

#define LOG_TAG "G2012Driver"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static int g_stp_initialized = 0;

typedef struct {
    const unsigned char *rgb_data;
    int width;
    int height;
} BitmapRep;

static void img_init(stp_image_t *image) { (void)image; }
static void img_reset(stp_image_t *image) { (void)image; }
static void img_conclude(stp_image_t *image) { (void)image; }
static const char *img_appname(stp_image_t *image) { (void)image; return "G2012WirelessPrint"; }

static int img_width(stp_image_t *image) {
    return ((BitmapRep *)image->rep)->width;
}

static int img_height(stp_image_t *image) {
    return ((BitmapRep *)image->rep)->height;
}

static stp_image_status_t img_get_row(stp_image_t *image, unsigned char *data, size_t byte_limit, int row) {
    BitmapRep *rep = (BitmapRep *)image->rep;
    if (row < 0 || row >= rep->height) {
        return STP_IMAGE_STATUS_ABORT;
    }
    size_t row_bytes = (size_t)rep->width * 3;
    if (row_bytes > byte_limit) {
        row_bytes = byte_limit;
    }
    memcpy(data, rep->rgb_data + ((size_t)row * rep->width * 3), row_bytes);
    return STP_IMAGE_STATUS_OK;
}

static void file_outfunc(void *data, const char *buffer, size_t bytes) {
    FILE *fp = (FILE *)data;
    if (fp && bytes > 0) {
        fwrite(buffer, 1, bytes, fp);
    }
}

static void log_errfunc(void *data, const char *buffer, size_t bytes) {
    (void)data;
    if (bytes > 0) {
        LOGE("Gutenprint: %.*s", (int)bytes, buffer);
    }
}

JNIEXPORT jstring JNICALL
Java_com_g2012_wirelessprint_GutenprintDriver_nativeInit(JNIEnv *env, jclass clazz, jstring xmlDirPath) {
    (void)clazz;
    if (g_stp_initialized) {
        return (*env)->NewStringUTF(env, "OK");
    }
    const char *path = (*env)->GetStringUTFChars(env, xmlDirPath, NULL);
    if (!path) {
        return (*env)->NewStringUTF(env, "ERROR: Null XML path");
    }
    setenv("STP_DATA_PATH", path, 1);
    LOGI("Initializing Gutenprint with STP_DATA_PATH=%s", path);
    (*env)->ReleaseStringUTFChars(env, xmlDirPath, path);

    stp_init();
    g_stp_initialized = 1;

    const stp_printer_t *p = stp_get_printer_by_driver("bjc-G2000-series");
    if (!p) {
        p = stp_get_printer_by_driver("bjc-G1000-series");
    }
    if (!p) {
        return (*env)->NewStringUTF(env, "ERROR: Canon G2000 driver not found in XML data");
    }
    LOGI("Found Canon driver: %s", stp_printer_get_long_name(p));
    return (*env)->NewStringUTF(env, "OK");
}

JNIEXPORT jstring JNICALL
Java_com_g2012_wirelessprint_GutenprintDriver_nativeRenderPageToFile(
    JNIEnv *env,
    jclass clazz,
    jbyteArray rgbBytes,
    jint width,
    jint height,
    jboolean isColor,
    jstring outputPath
) {
    (void)clazz;
    if (!g_stp_initialized) {
        return (*env)->NewStringUTF(env, "ERROR: Driver not initialized");
    }

    const char *out_path = (*env)->GetStringUTFChars(env, outputPath, NULL);
    FILE *fp = fopen(out_path, "wb");
    if (!fp) {
        (*env)->ReleaseStringUTFChars(env, outputPath, out_path);
        return (*env)->NewStringUTF(env, "ERROR: Cannot open output file");
    }

    jbyte *raw_rgb = (*env)->GetByteArrayElements(env, rgbBytes, NULL);
    if (!raw_rgb) {
        fclose(fp);
        (*env)->ReleaseStringUTFChars(env, outputPath, out_path);
        return (*env)->NewStringUTF(env, "ERROR: Failed to read RGB buffer");
    }

    const stp_printer_t *printer = stp_get_printer_by_driver("bjc-G2000-series");
    if (!printer) {
        printer = stp_get_printer_by_driver("bjc-G1000-series");
    }
    if (!printer) {
        (*env)->ReleaseByteArrayElements(env, rgbBytes, raw_rgb, JNI_ABORT);
        fclose(fp);
        (*env)->ReleaseStringUTFChars(env, outputPath, out_path);
        return (*env)->NewStringUTF(env, "ERROR: Canon G2000 driver missing");
    }

    stp_vars_t *v = stp_vars_create();
    stp_set_printer_defaults(v, printer);
    stp_set_driver(v, stp_printer_get_driver(printer));
    stp_set_outfunc(v, file_outfunc);
    stp_set_outdata(v, fp);
    stp_set_errfunc(v, log_errfunc);
    stp_set_errdata(v, NULL);

    stp_set_string_parameter(v, "PageSize", "A4");
    stp_set_string_parameter(v, "MediaType", "Plain");
    stp_set_string_parameter(v, "InputImageType", "RGB");
    stp_set_string_parameter(v, "ChannelBitDepth", "8");
    stp_set_string_parameter(v, "PrintingMode", isColor ? "Color" : "BW");
    stp_set_string_parameter(v, "JobMode", "Job");

    stp_dimension_t left = 0, right = 0, bottom = 0, top = 0;
    stp_get_imageable_area(v, &left, &right, &bottom, &top);
    stp_set_left(v, left);
    stp_set_top(v, top);
    stp_set_width(v, right - left);
    stp_set_height(v, bottom - top);

    BitmapRep rep;
    rep.rgb_data = (const unsigned char *)raw_rgb;
    rep.width = width;
    rep.height = height;

    stp_image_t img = {
        .init = img_init,
        .reset = img_reset,
        .width = img_width,
        .height = img_height,
        .get_appname = img_appname,
        .get_row = img_get_row,
        .conclude = img_conclude,
        .rep = &rep
    };

    int ok = 1;
    if (!stp_verify(v)) {
        LOGE("stp_verify failed, proceeding with defaults");
    }
    if (!stp_start_job(v, &img)) {
        ok = 0;
    } else {
        if (!stp_print(v, &img)) {
            ok = 0;
        }
        stp_end_job(v, &img);
    }

    stp_vars_destroy(v);
    (*env)->ReleaseByteArrayElements(env, rgbBytes, raw_rgb, JNI_ABORT);
    fflush(fp);
    long out_size = ftell(fp);
    fclose(fp);
    (*env)->ReleaseStringUTFChars(env, outputPath, out_path);

    if (!ok || out_size <= 0) {
        return (*env)->NewStringUTF(env, "ERROR: Gutenprint failed to render Canon raster data");
    }
    char msg[128];
    snprintf(msg, sizeof(msg), "OK:%ld", out_size);
    return (*env)->NewStringUTF(env, msg);
}

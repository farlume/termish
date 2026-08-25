#ifndef TERMISH_SCREEN_PLAYER_H
#define TERMISH_SCREEN_PLAYER_H

#import <AVFoundation/AVFoundation.h>
#import <CoreMedia/CoreMedia.h>
#import <VideoToolbox/VideoToolbox.h>

/*
 * CoreMedia 的 sample/format 都是 CF 对象。把创建、附件设置和释放集中在
 * 这个薄桥接层，避免 Kotlin/Native 每帧拼多级 C 指针或误用 ARC 生命周期。
 */
typedef void *TermishScreenFormatRef;

static inline TermishScreenFormatRef termish_screen_format_create(
        const unsigned char *sps,
        size_t sps_size,
        const unsigned char *pps,
        size_t pps_size,
        int *status_out) {
    const uint8_t *parameter_sets[2] = {sps, pps};
    const size_t parameter_set_sizes[2] = {sps_size, pps_size};
    CMFormatDescriptionRef format = NULL;
    OSStatus status = CMVideoFormatDescriptionCreateFromH264ParameterSets(
        kCFAllocatorDefault,
        2,
        parameter_sets,
        parameter_set_sizes,
        4,
        &format
    );
    if (status_out != NULL) {
        *status_out = (int)status;
    }
    return status == noErr ? (TermishScreenFormatRef)format : NULL;
}

static inline void termish_screen_format_release(TermishScreenFormatRef format) {
    if (format != NULL) {
        CFRelease((CMFormatDescriptionRef)format);
    }
}

/*
 * 返回 0 表示已提交；非 0 为 CoreMedia OSStatus。
 * avcc_data 必须是 4-byte big-endian NAL length-prefixed access unit。
 */
static inline int termish_screen_enqueue(
        AVSampleBufferDisplayLayer *layer,
        TermishScreenFormatRef format,
        const unsigned char *avcc_data,
        size_t avcc_size,
        int is_sync) {
    if (layer == nil || format == NULL || avcc_data == NULL || avcc_size == 0) {
        return -1;
    }

    CMBlockBufferRef block = NULL;
    OSStatus status = CMBlockBufferCreateWithMemoryBlock(
        kCFAllocatorDefault,
        NULL,
        avcc_size,
        kCFAllocatorDefault,
        NULL,
        0,
        avcc_size,
        0,
        &block
    );
    if (status != noErr || block == NULL) {
        return status == noErr ? -2 : (int)status;
    }

    status = CMBlockBufferReplaceDataBytes(avcc_data, block, 0, avcc_size);
    if (status != noErr) {
        CFRelease(block);
        return (int)status;
    }

    CMSampleBufferRef sample = NULL;
    const size_t sample_size = avcc_size;
    status = CMSampleBufferCreateReady(
        kCFAllocatorDefault,
        block,
        (CMFormatDescriptionRef)format,
        1,
        0,
        NULL,
        1,
        &sample_size,
        &sample
    );
    if (status != noErr || sample == NULL) {
        CFRelease(block);
        return status == noErr ? -3 : (int)status;
    }

    CFArrayRef attachments = CMSampleBufferGetSampleAttachmentsArray(sample, true);
    if (attachments != NULL && CFArrayGetCount(attachments) > 0) {
        CFMutableDictionaryRef attachment =
            (CFMutableDictionaryRef)CFArrayGetValueAtIndex(attachments, 0);
        CFDictionarySetValue(
            attachment,
            kCMSampleAttachmentKey_DisplayImmediately,
            kCFBooleanTrue
        );
        if (!is_sync) {
            CFDictionarySetValue(
                attachment,
                kCMSampleAttachmentKey_NotSync,
                kCFBooleanTrue
            );
        }
    }

    [layer enqueueSampleBuffer:sample];
    CFRelease(sample);
    CFRelease(block);
    return 0;
}

/* -1=失败，0=等待首帧，1=正在解码/渲染。 */
static inline int termish_screen_layer_state(AVSampleBufferDisplayLayer *layer) {
    if (layer == nil) {
        return -1;
    }
    if (layer.status == AVQueuedSampleBufferRenderingStatusFailed) {
        return -1;
    }
    return layer.status == AVQueuedSampleBufferRenderingStatusRendering ? 1 : 0;
}

static inline long termish_screen_layer_error_code(AVSampleBufferDisplayLayer *layer) {
    return layer.error == nil ? 0L : (long)layer.error.code;
}

static inline int termish_screen_h264_hardware_supported(void) {
    return VTIsHardwareDecodeSupported(kCMVideoCodecType_H264) ? 1 : 0;
}

#endif

// SPDX-License-Identifier: MIT
// Native capture child. The authenticated Python relay owns its lifetime and
// power assertions; stdout contains Annex-B H.264 only, diagnostics go to stderr.
#import <Foundation/Foundation.h>
#import <ScreenCaptureKit/ScreenCaptureKit.h>
#import <VideoToolbox/VideoToolbox.h>
#import <CoreGraphics/CoreGraphics.h>
#import <IOSurface/IOSurface.h>
#include <arpa/inet.h>
#include <errno.h>
#include <signal.h>
#include <stdatomic.h>
#include <time.h>
#include <unistd.h>

static volatile sig_atomic_t stopped;
static atomic_bool displayChanged;
static void stopCapture(int sig) { (void)sig; stopped = 1; }
static void displayChange(CGDirectDisplayID display, CGDisplayChangeSummaryFlags flags, void *context) {
    (void)display; (void)flags; (void)context;
    atomic_store(&displayChanged, true);
}
static double now(void) {
    struct timespec value;
    clock_gettime(CLOCK_MONOTONIC, &value);
    return value.tv_sec + value.tv_nsec / 1e9;
}
static BOOL output(const void *data, size_t length) {
    const uint8_t *bytes = data;
    while (length) {
        ssize_t written = write(STDOUT_FILENO, bytes, length);
        if (written < 0 && errno == EINTR) continue;
        if (written <= 0) { stopped = 1; return NO; }
        bytes += written; length -= (size_t)written;
    }
    return YES;
}
static BOOL nal(const void *data, size_t length) {
    static const uint8_t prefix[] = {0, 0, 0, 1};
    return output(prefix, sizeof(prefix)) && output(data, length);
}

@interface TermishEncoder : NSObject
@property(nonatomic) VTCompressionSessionRef encoder;
@property(nonatomic) CVPixelBufferRef latest;
@property(nonatomic) dispatch_queue_t queue;
@property(nonatomic) dispatch_source_t timer;
@property(nonatomic) uint64_t sequence;
@property(nonatomic) int fps;
@property(atomic) BOOL encoded;
@property(atomic) BOOL failed;
- (BOOL)prepareWidth:(int)width height:(int)height bitrate:(int)bitrate;
- (void)receive:(CVPixelBufferRef)buffer;
- (void)finish;
@end

static void compressed(void *context, void *source, OSStatus status,
                       VTEncodeInfoFlags flags, CMSampleBufferRef sample) {
    (void)source; (void)flags;
    TermishEncoder *owner = (__bridge TermishEncoder *)context;
    if (status || !sample || !CMSampleBufferDataIsReady(sample)) {
        owner.failed = true; return;
    }
    @autoreleasepool { @synchronized(owner) {
        // AUD delimits complete access units for the relay's existing parser.
        static const uint8_t aud[] = {9, 0xf0};
        if (!nal(aud, sizeof(aud))) return;
        CFArrayRef attachments = CMSampleBufferGetSampleAttachmentsArray(sample, false);
        BOOL keyframe = !attachments || !CFArrayGetCount(attachments) ||
            !CFDictionaryContainsKey(CFArrayGetValueAtIndex(attachments, 0), kCMSampleAttachmentKey_NotSync);
        CMFormatDescriptionRef format = CMSampleBufferGetFormatDescription(sample);
        int header = 0;
        size_t count = 0;
        if (CMVideoFormatDescriptionGetH264ParameterSetAtIndex(format, 0, NULL, NULL, &count, &header)) {
            owner.failed = true; return;
        }
        if (keyframe) {
            for (size_t index = 0; index < count; index++) {
                const uint8_t *bytes = NULL; size_t length = 0;
                if (CMVideoFormatDescriptionGetH264ParameterSetAtIndex(format, index, &bytes, &length, NULL, NULL) ||
                    !nal(bytes, length)) { owner.failed = true; return; }
            }
        }
        CMBlockBufferRef block = CMSampleBufferGetDataBuffer(sample);
        size_t length = CMBlockBufferGetDataLength(block);
        if (header != 4 || length > 4 * 1024 * 1024) { owner.failed = true; return; }
        NSMutableData *copy = [NSMutableData dataWithLength:length];
        if (CMBlockBufferCopyDataBytes(block, 0, length, copy.mutableBytes)) { owner.failed = true; return; }
        const uint8_t *bytes = copy.bytes;
        for (size_t offset = 0; offset < length;) {
            if (length - offset < 4) { owner.failed = true; return; }
            uint32_t size; memcpy(&size, bytes + offset, 4); size = ntohl(size); offset += 4;
            if (!size || size > length - offset || !nal(bytes + offset, size)) { owner.failed = true; return; }
            offset += size;
        }
        owner.encoded = true;
    } }
}

@implementation TermishEncoder
- (BOOL)prepareWidth:(int)width height:(int)height bitrate:(int)bitrate {
    self.queue = dispatch_queue_create("dev.termish.capture.frames", DISPATCH_QUEUE_SERIAL);
    VTCompressionSessionRef session = NULL;
    OSStatus status = VTCompressionSessionCreate(NULL, width, height, kCMVideoCodecType_H264,
        NULL, NULL, NULL, compressed, (__bridge void *)self, &session);
    if (status) { fprintf(stderr, "native encoder create failed: %d\n", (int)status); return NO; }
    self.encoder = session;
    VTSessionSetProperty(session, kVTCompressionPropertyKey_RealTime, kCFBooleanTrue);
    VTSessionSetProperty(session, kVTCompressionPropertyKey_AllowFrameReordering, kCFBooleanFalse);
    VTSessionSetProperty(session, kVTCompressionPropertyKey_ProfileLevel, kVTProfileLevel_H264_High_AutoLevel);
    VTSessionSetProperty(session, kVTCompressionPropertyKey_AverageBitRate, (__bridge CFTypeRef)@(bitrate));
    VTSessionSetProperty(session, kVTCompressionPropertyKey_ExpectedFrameRate, (__bridge CFTypeRef)@(self.fps));
    VTSessionSetProperty(session, kVTCompressionPropertyKey_MaxKeyFrameInterval, (__bridge CFTypeRef)@(MAX(15, self.fps / 2)));
    if (VTCompressionSessionPrepareToEncodeFrames(session)) return NO;
    self.timer = dispatch_source_create(DISPATCH_SOURCE_TYPE_TIMER, 0, 0, self.queue);
    dispatch_source_set_timer(self.timer, DISPATCH_TIME_NOW, NSEC_PER_SEC / self.fps, NSEC_PER_MSEC);
    // Repeat the latest frame when an unchanged desktop/lock screen supplies
    // no updates. Transport/render watchdogs must not mistake idle for failure.
    __weak TermishEncoder *weak = self;
    dispatch_source_set_event_handler(self.timer, ^{
        TermishEncoder *owner = weak;
        if (!owner || !owner.latest || stopped) return;
        OSStatus result = VTCompressionSessionEncodeFrame(owner.encoder, owner.latest,
            CMTimeMake((int64_t)owner.sequence++, owner.fps), CMTimeMake(1, owner.fps), NULL, NULL, NULL);
        if (result) owner.failed = true;
    });
    dispatch_resume(self.timer);
    return YES;
}
- (void)receive:(CVPixelBufferRef)buffer {
    if (!buffer) return;
    if (self.latest) CVPixelBufferRelease(self.latest);
    self.latest = CVPixelBufferRetain(buffer);
}
- (void)finish {
    if (self.timer) dispatch_source_cancel(self.timer);
    dispatch_sync(self.queue, ^{
        if (self.encoder) {
            VTCompressionSessionCompleteFrames(self.encoder, kCMTimeInvalid);
            VTCompressionSessionInvalidate(self.encoder);
            CFRelease(self.encoder); self.encoder = NULL;
        }
        if (self.latest) { CVPixelBufferRelease(self.latest); self.latest = NULL; }
    });
}
@end

API_AVAILABLE(macos(12.3))
@interface TermishStream : NSObject <SCStreamOutput, SCStreamDelegate>
@property(nonatomic, strong) SCStream *stream;
@property(nonatomic, strong) TermishEncoder *encoder;
@property(atomic) BOOL failed;
@end
@implementation TermishStream
- (void)stream:(SCStream *)stream didOutputSampleBuffer:(CMSampleBufferRef)sample ofType:(SCStreamOutputType)type {
    (void)stream;
    if (type != SCStreamOutputTypeScreen || !CMSampleBufferIsValid(sample)) return;
    CFArrayRef attachments = CMSampleBufferGetSampleAttachmentsArray(sample, false);
    if (attachments && CFArrayGetCount(attachments)) {
        NSDictionary *info = (__bridge NSDictionary *)CFArrayGetValueAtIndex(attachments, 0);
        NSNumber *status = info[SCStreamFrameInfoStatus];
        if (status && status.integerValue != SCFrameStatusComplete) return;
    }
    [self.encoder receive:CMSampleBufferGetImageBuffer(sample)];
}
- (void)stream:(SCStream *)stream didStopWithError:(NSError *)error {
    (void)stream;
    // Do not log NSError userInfo: only the domain/code is needed for diagnosis.
    fprintf(stderr, "ScreenCaptureKit stopped: %s %ld\n", error.domain.UTF8String, (long)error.code);
    self.failed = YES;
}
@end

#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wdeprecated-declarations"
static CGDisplayStreamRef startCoreGraphics(CGDirectDisplayID display, int width, int height, TermishEncoder *encoder) {
    NSDictionary *properties = @{(__bridge NSString *)kCGDisplayStreamShowCursor: @YES};
    CGDisplayStreamRef stream = CGDisplayStreamCreateWithDispatchQueue(display, width, height,
        kCVPixelFormatType_32BGRA, (__bridge CFDictionaryRef)properties, encoder.queue,
        ^(CGDisplayStreamFrameStatus status, uint64_t time, IOSurfaceRef surface, CGDisplayStreamUpdateRef update) {
            (void)time; (void)update;
            if (status == kCGDisplayStreamFrameStatusStopped) { encoder.failed = true; return; }
            if (status != kCGDisplayStreamFrameStatusFrameComplete || !surface) return;
            CVPixelBufferRef buffer = NULL;
            if (!CVPixelBufferCreateWithIOSurface(NULL, surface, NULL, &buffer)) {
                [encoder receive:buffer]; CVPixelBufferRelease(buffer);
            }
        });
    if (stream && CGDisplayStreamStart(stream)) { CFRelease(stream); stream = NULL; }
    return stream;
}
#pragma clang diagnostic pop

// Invoked only as the relay's child after THA1 authentication. Exit 75 means
// display/stream unavailable (retry); 77 means capture consent is missing.
int termish_capture_main(int fps, int requestedWidth, int bitrate, bool diagnostic) {
    @autoreleasepool {
        signal(SIGTERM, stopCapture); signal(SIGINT, stopCapture); signal(SIGPIPE, SIG_IGN);
        CGDirectDisplayID displays[16]; uint32_t count = 0;
        CGDirectDisplayID display = 0;
        size_t sourceWidth = 320, sourceHeight = 180;
        if (!diagnostic) {
            if (CGGetActiveDisplayList(16, displays, &count) || !count) return 75;
            display = CGMainDisplayID();
            if (!CGDisplayIsActive(display)) display = displays[0];
            CGDisplayModeRef mode = CGDisplayCopyDisplayMode(display);
            if (!mode) return 75;
            sourceWidth = CGDisplayModeGetPixelWidth(mode); sourceHeight = CGDisplayModeGetPixelHeight(mode);
            CGDisplayModeRelease(mode);
        }
        if (!sourceWidth || !sourceHeight) return 75;
        int width = (requestedWidth ? requestedWidth : (int)sourceWidth) & ~1;
        int height = MAX(2, (int)(width * (double)sourceHeight / sourceWidth) & ~1);
        TermishEncoder *encoder = [TermishEncoder new]; encoder.fps = fps;
        if (![encoder prepareWidth:width height:height bitrate:bitrate]) return 70;
        __block id native = nil;
        __block BOOL sckFailed = NO;
        __block BOOL triedFallback = NO;
        if (diagnostic) {
            CVPixelBufferRef pattern = NULL;
            if (CVPixelBufferCreate(NULL, width, height, kCVPixelFormatType_32BGRA, NULL, &pattern)) return 70;
            CVPixelBufferLockBaseAddress(pattern, 0);
            memset(CVPixelBufferGetBaseAddress(pattern), 0x80, CVPixelBufferGetBytesPerRow(pattern) * height);
            CVPixelBufferUnlockBaseAddress(pattern, 0);
            dispatch_sync(encoder.queue, ^{ [encoder receive:pattern]; });
            CVPixelBufferRelease(pattern);
        } else if (@available(macOS 12.3, *)) {
            [SCShareableContent getShareableContentExcludingDesktopWindows:NO onScreenWindowsOnly:NO
                completionHandler:^(SCShareableContent *content, NSError *error) {
                dispatch_async(dispatch_get_main_queue(), ^{
                if (stopped || triedFallback) return;
                if (error) {
                    fprintf(stderr, "ScreenCaptureKit content unavailable: %s %ld\n", error.domain.UTF8String, (long)error.code);
                    sckFailed = YES; return;
                }
                SCDisplay *selected = nil;
                for (SCDisplay *candidate in content.displays) if (candidate.displayID == display) selected = candidate;
                if (!selected) { sckFailed = YES; return; }
                SCStreamConfiguration *config = [SCStreamConfiguration new];
                config.width = width; config.height = height; config.pixelFormat = kCVPixelFormatType_32BGRA;
                config.minimumFrameInterval = CMTimeMake(1, fps); config.queueDepth = 3; config.showsCursor = YES;
                TermishStream *owner = [TermishStream new]; owner.encoder = encoder;
                SCContentFilter *filter = [[SCContentFilter alloc] initWithDisplay:selected excludingWindows:@[]];
                owner.stream = [[SCStream alloc] initWithFilter:filter configuration:config delegate:owner];
                native = owner;
                NSError *failure = nil;
                if (![owner.stream addStreamOutput:owner type:SCStreamOutputTypeScreen sampleHandlerQueue:encoder.queue error:&failure]) {
                    sckFailed = YES; return;
                }
                [owner.stream startCaptureWithCompletionHandler:^(NSError *failure) {
                    if (failure) dispatch_async(dispatch_get_main_queue(), ^{ sckFailed = YES; });
                    else fprintf(stderr, "native capture backend=ScreenCaptureKit\n");
                }];
                });
            }];
        } else { sckFailed = YES; }
        if (!diagnostic) CGDisplayRegisterReconfigurationCallback(displayChange, NULL);
        CGDisplayStreamRef fallback = NULL;
        double started = now();
        while (!stopped && !encoder.failed && !atomic_load(&displayChanged)) {
            CFRunLoopRunInMode(kCFRunLoopDefaultMode, 0.1, false);
            if (diagnostic && now() - started > 2) break;
            if (@available(macOS 12.3, *)) {
                if (native && ((TermishStream *)native).failed) {
                    if (encoder.encoded) break;
                    sckFailed = YES;
                }
            }
            if (!diagnostic && !triedFallback && (sckFailed || (!encoder.encoded && now() - started > 3))) {
                triedFallback = YES;
                if (@available(macOS 12.3, *)) {
                    if (native) [((TermishStream *)native).stream stopCaptureWithCompletionHandler:^(NSError *error) { (void)error; }];
                }
                fprintf(stderr, "native capture backend=CoreGraphics\n");
                fallback = startCoreGraphics(display, width, height, encoder);
                if (!fallback) break;
            }
            if (!encoder.encoded && now() - started > 8) break;
            if (triedFallback && sckFailed && !fallback) break;
        }
        stopped = 1;
        if (!diagnostic) CGDisplayRemoveReconfigurationCallback(displayChange, NULL);
        if (@available(macOS 12.3, *)) {
            if (native) [((TermishStream *)native).stream stopCaptureWithCompletionHandler:^(NSError *error) { (void)error; }];
        }
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wdeprecated-declarations"
        if (fallback) { CGDisplayStreamStop(fallback); CFRelease(fallback); }
#pragma clang diagnostic pop
        [encoder finish];
        if (diagnostic) return encoder.encoded && !encoder.failed ? 0 : 70;
        if (@available(macOS 10.15, *)) {
            // Classification after attempting real capture, never an extra gate.
            if (!encoder.encoded && !CGPreflightScreenCaptureAccess()) return 77;
        }
        return 75;
    }
}

// MIT. Native menu only; capture, input and session lifecycle remain in Rust.
#import <AppKit/AppKit.h>
#include <stdbool.h>
#include <stdint.h>
#include <string.h>

typedef void (*TermishMenuCallback)(uint32_t);
static const CGFloat TermishIconSize = 18;

@interface TermishMenu : NSObject <NSApplicationDelegate>
@property(nonatomic, strong) NSStatusItem *item;
@property(nonatomic, strong) NSString *logPath;
@property(nonatomic, strong) NSString *directoryPath;
@property(nonatomic, assign) TermishMenuCallback callback;
@property(nonatomic, assign) BOOL terminationPending;
@property(nonatomic, assign) NSInteger iconState;
- (void)selected:(NSMenuItem *)sender;
@end
static TermishMenu *owner;

static NSImage *icon(BOOL connected, BOOL paused) {
    NSImage *image = [NSImage imageWithSize:NSMakeSize(TermishIconSize, TermishIconSize)
                                 flipped:NO drawingHandler:^BOOL(NSRect rect) {
        (void)rect;
        [NSColor.blackColor setStroke];
        // Monochrome version of the existing rounded >_ Termish mark.
        NSBezierPath *border = [NSBezierPath bezierPathWithRoundedRect:NSMakeRect(1,1,16,16)
                                                            xRadius:4.3 yRadius:4.3];
        border.lineWidth = 1;
        [border stroke];
        NSBezierPath *mark = [NSBezierPath bezierPath];
        mark.lineWidth = 1.5;
        mark.lineCapStyle = NSLineCapStyleRound;
        mark.lineJoinStyle = NSLineJoinStyleRound;
        if (paused) {
            [mark moveToPoint:NSMakePoint(6.5,5)]; [mark lineToPoint:NSMakePoint(6.5,13)];
            [mark moveToPoint:NSMakePoint(11.5,5)]; [mark lineToPoint:NSMakePoint(11.5,13)];
        } else {
            [mark moveToPoint:NSMakePoint(6,13.3)]; [mark lineToPoint:NSMakePoint(12,10)];
            [mark lineToPoint:NSMakePoint(6,6.7)];
            [mark moveToPoint:NSMakePoint(7,5.3)]; [mark lineToPoint:NSMakePoint(12.3,5.3)];
        }
        [mark stroke];
        if (connected) {
            [NSColor.blackColor setFill];
            [[NSBezierPath bezierPathWithOvalInRect:NSMakeRect(14,14,4,4)] fill];
        }
        return YES;
    }];
    image.template = YES;
    return image;
}

@implementation TermishMenu
- (void)selected:(NSMenuItem *)sender {
    NSURL *url = nil;
    switch (sender.tag) {
        case 100:
            url = [NSURL URLWithString:@"x-apple.systempreferences:com.apple.preference.security?Privacy_ScreenCapture"];
            break;
        case 101:
            url = [NSURL URLWithString:@"x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility"];
            break;
        case 102: url = [NSURL fileURLWithPath:self.logPath]; break;
        case 103: url = [NSURL fileURLWithPath:self.directoryPath isDirectory:YES]; break;
        default:
            if (self.callback && sender.tag >= 1 && sender.tag <= 4) self.callback((uint32_t)sender.tag);
            return;
    }
    if (url) [NSWorkspace.sharedWorkspace openURL:url];
}
- (NSApplicationTerminateReply)applicationShouldTerminate:(NSApplication *)sender {
    (void)sender;
    self.terminationPending = YES;
    if (self.callback) self.callback(4);
    return NSTerminateLater; // Rust first stops capture and releases held input.
}
@end

bool termish_menu_chinese(void) {
    @autoreleasepool {
        return [NSLocale.preferredLanguages.firstObject hasPrefix:@"zh"];
    }
}
bool termish_menu_create(const char *paths, TermishMenuCallback callback) {
    @autoreleasepool {
        if (![NSThread isMainThread] || owner) return false;
        NSData *data = [NSData dataWithBytes:paths length:strlen(paths)];
        NSDictionary *settings = [NSJSONSerialization JSONObjectWithData:data options:0 error:NULL];
        if (![settings isKindOfClass:NSDictionary.class]) return false;
        [NSApplication sharedApplication];
        [NSApp setActivationPolicy:NSApplicationActivationPolicyAccessory];
        owner = [TermishMenu new];
        owner.callback = callback;
        owner.logPath = settings[@"log"];
        owner.directoryPath = settings[@"directory"];
        owner.iconState = -1;
        owner.item = [NSStatusBar.systemStatusBar statusItemWithLength:NSSquareStatusItemLength];
        owner.item.button.image = icon(NO, NO);
        NSApp.delegate = owner;
        [NSApp finishLaunching];
        return owner.item != nil;
    }
}
void termish_menu_update(const char *model) {
    @autoreleasepool {
        if (!owner || ![NSThread isMainThread]) return;
        NSData *data = [NSData dataWithBytes:model length:strlen(model)];
        NSDictionary *state = [NSJSONSerialization JSONObjectWithData:data options:0 error:NULL];
        if (![state isKindOfClass:NSDictionary.class]) return;
        NSMenu *menu = [NSMenu new];
        menu.autoenablesItems = NO;
        for (NSDictionary *value in state[@"items"]) {
            if ([value[@"separator"] boolValue]) { [menu addItem:NSMenuItem.separatorItem]; continue; }
            NSMenuItem *item = [[NSMenuItem alloc] initWithTitle:value[@"title"]
                                                       action:@selector(selected:) keyEquivalent:@""];
            item.tag = [value[@"action"] integerValue];
            item.enabled = [value[@"enabled"] boolValue];
            item.target = owner;
            [menu addItem:item];
        }
        owner.item.menu = menu;
        owner.item.button.toolTip = state[@"tooltip"];
        [owner.item.button setAccessibilityLabel:state[@"tooltip"]];
        BOOL connected = [state[@"connected"] boolValue];
        BOOL paused = [state[@"paused"] boolValue];
        NSInteger iconState = paused ? 2 : (connected ? 1 : 0);
        if (iconState != owner.iconState) {
            owner.iconState = iconState;
            owner.item.button.image = icon(connected, paused);
        }
    }
}
void termish_menu_poll(void) {
    @autoreleasepool {
        if (!owner || ![NSThread isMainThread]) return;
        NSEvent *event;
        while ((event = [NSApp nextEventMatchingMask:NSEventMaskAny untilDate:NSDate.distantPast
                                             inMode:NSDefaultRunLoopMode dequeue:YES])) {
            [NSApp sendEvent:event];
        }
    }
}
void termish_menu_destroy(void) {
    @autoreleasepool {
        if (!owner) return;
        BOOL reply = owner.terminationPending;
        [NSStatusBar.systemStatusBar removeStatusItem:owner.item];
        NSApp.delegate = nil;
        owner = nil;
        if (reply) [NSApp replyToApplicationShouldTerminate:YES];
    }
}

#ifdef TERMISH_MENU_TEST
// Test build only: use AppKit's real menu target/action dispatch without requiring
// permission to synthesize system keyboard or mouse events.
bool termish_menu_test_action(uint32_t action) {
    for (NSMenuItem *item in owner.item.menu.itemArray) {
        if (item.tag == action && item.enabled) {
            [owner.item.menu performActionForItemAtIndex:[owner.item.menu indexOfItem:item]];
            return true;
        }
    }
    return false;
}
#endif

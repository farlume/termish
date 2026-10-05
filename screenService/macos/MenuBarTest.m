// MIT. Isolated AppKit smoke test; never captures a desktop or injects input.
#define TERMISH_MENU_TEST
#include "MenuBar.m"
#import <CoreGraphics/CoreGraphics.h>
#include <assert.h>

static uint32_t received;
static void requested(uint32_t action) { received = action; }

int main(void) {
    @autoreleasepool {
        CFDictionaryRef session = CGSessionCopyCurrentDictionary();
        if (!session) return 77; // No WindowServer session on a headless CI host.
        CFRelease(session);
        assert(termish_menu_create("{\"log\":\"/tmp/termish-menu-test.log\",\"directory\":\"/tmp\"}", requested));
        termish_menu_update("{\"tooltip\":\"Termish menu test\",\"connected\":true,\"paused\":false,\"items\":["
            "{\"title\":\"Disconnect\",\"action\":2,\"enabled\":true},"
            "{\"title\":\"Pause\",\"action\":1,\"enabled\":true},"
            "{\"separator\":true},"
            "{\"title\":\"Restart\",\"action\":3,\"enabled\":true},"
            "{\"title\":\"Quit\",\"action\":4,\"enabled\":true}]}");
        assert(owner.item.button.image.isTemplate);
        assert(owner.item.button.image.size.width == TermishIconSize);
        assert(owner.item.menu.numberOfItems == 5);
        for (uint32_t action = 1; action <= 4; action++) {
            received = 0;
            assert(termish_menu_test_action(action));
            assert(received == action);
        }
        termish_menu_update("{\"tooltip\":\"Termish paused\",\"connected\":false,\"paused\":true,\"items\":["
            "{\"title\":\"断开连接\",\"action\":2,\"enabled\":false},"
            "{\"title\":\"恢复远程访问\",\"action\":1,\"enabled\":true}]}");
        assert(owner.iconState == 2);
        assert([owner.item.menu.itemArray[1].title isEqualToString:@"恢复远程访问"]);
        assert(!termish_menu_test_action(2));
        assert(termish_menu_test_action(1));
        assert(received == 1);
        termish_menu_poll();
        termish_menu_destroy();
        assert(!owner);
    }
    return 0;
}

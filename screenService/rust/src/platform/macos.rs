use crate::protocol::Control;
use std::{
    ffi::c_void,
    ptr,
    sync::{
        atomic::{AtomicBool, Ordering},
        OnceLock,
    },
};

#[repr(C)]
#[derive(Clone, Copy, Default)]
struct Point {
    x: f64,
    y: f64,
}
#[repr(C)]
struct Size {
    width: f64,
    height: f64,
}
#[repr(C)]
struct Rect {
    origin: Point,
    size: Size,
}
type Ref = *const c_void;

#[link(name = "CoreGraphics", kind = "framework")]
extern "C" {
    fn CGMainDisplayID() -> u32;
    fn CGDisplayBounds(display: u32) -> Rect;
    fn CGDisplayIsAsleep(display: u32) -> bool;
    fn CGSessionCopyCurrentDictionary() -> Ref;
    fn CGEventSourceCreate(state: i32) -> Ref;
    fn CGEventCreate(source: Ref) -> Ref;
    fn CGEventGetLocation(event: Ref) -> Point;
    fn CGEventCreateMouseEvent(source: Ref, kind: u32, point: Point, button: u32) -> Ref;
    fn CGEventCreateKeyboardEvent(source: Ref, key: u16, down: bool) -> Ref;
    fn CGEventCreateScrollWheelEvent(source: Ref, unit: u32, count: u32, ...) -> Ref;
    fn CGEventSetLocation(event: Ref, point: Point);
    fn CGEventSetFlags(event: Ref, flags: u64);
    fn CGEventKeyboardSetUnicodeString(event: Ref, length: usize, chars: *const u16);
    fn CGEventPost(tap: u32, event: Ref);
}
#[link(name = "ApplicationServices", kind = "framework")]
extern "C" {
    fn AXIsProcessTrusted() -> bool;
    fn AXIsProcessTrustedWithOptions(options: Ref) -> bool;
    static kAXTrustedCheckOptionPrompt: Ref;
}
#[link(name = "CoreFoundation", kind = "framework")]
extern "C" {
    fn CFRelease(object: Ref);
    fn CFStringCreateWithCString(allocator: Ref, chars: *const libc::c_char, encoding: u32) -> Ref;
    fn CFDictionaryGetValue(dictionary: Ref, key: Ref) -> Ref;
    fn CFDictionaryCreate(
        allocator: Ref,
        keys: *const Ref,
        values: *const Ref,
        count: isize,
        key_callbacks: Ref,
        value_callbacks: Ref,
    ) -> Ref;
    fn CFRunLoopRunInMode(mode: Ref, seconds: f64, return_after_source: bool) -> i32;
    static kCFRunLoopDefaultMode: Ref;
    static kCFBooleanTrue: Ref;
}

static PERMISSION_PROMPTED: AtomicBool = AtomicBool::new(false);
type PermissionFn = unsafe extern "C" fn() -> bool;
struct PostEventAccess {
    preflight: PermissionFn,
    request: PermissionFn,
}
fn post_event_access() -> Option<&'static PostEventAccess> {
    static API: OnceLock<Option<PostEventAccess>> = OnceLock::new();
    API.get_or_init(|| unsafe {
        // These public APIs were added in 10.15. Resolve them lazily so older
        // Intel Macs can still load the daemon and use the legacy AX check.
        let preflight = libc::dlsym(libc::RTLD_DEFAULT, c"CGPreflightPostEventAccess".as_ptr());
        let request = libc::dlsym(libc::RTLD_DEFAULT, c"CGRequestPostEventAccess".as_ptr());
        if preflight.is_null() || request.is_null() {
            None
        } else {
            Some(PostEventAccess {
                preflight: std::mem::transmute::<*mut c_void, PermissionFn>(preflight),
                request: std::mem::transmute::<*mut c_void, PermissionFn>(request),
            })
        }
    })
    .as_ref()
}

fn input_trusted() -> bool {
    unsafe {
        post_event_access()
            .map(|api| (api.preflight)())
            .unwrap_or_else(|| AXIsProcessTrusted())
    }
}

fn request_input_access() {
    unsafe {
        if let Some(api) = post_event_access() {
            (api.request)();
        } else {
            let options = Owned(CFDictionaryCreate(
                ptr::null(),
                &kAXTrustedCheckOptionPrompt,
                &kCFBooleanTrue,
                1,
                ptr::null(),
                ptr::null(),
            ));
            if !options.0.is_null() {
                AXIsProcessTrustedWithOptions(options.0);
            }
        }
    }
}

fn screen_capture_access() -> Option<&'static PostEventAccess> {
    static API: OnceLock<Option<PostEventAccess>> = OnceLock::new();
    API.get_or_init(|| unsafe {
        let preflight = libc::dlsym(
            libc::RTLD_DEFAULT,
            c"CGPreflightScreenCaptureAccess".as_ptr(),
        );
        let request = libc::dlsym(libc::RTLD_DEFAULT, c"CGRequestScreenCaptureAccess".as_ptr());
        if preflight.is_null() || request.is_null() {
            None
        } else {
            Some(PostEventAccess {
                preflight: std::mem::transmute::<*mut c_void, PermissionFn>(preflight),
                request: std::mem::transmute::<*mut c_void, PermissionFn>(request),
            })
        }
    })
    .as_ref()
}

pub fn capture_allowed() -> bool {
    // Screen recording consent was introduced in 10.15. The preflight never
    // prompts, unlike starting a fresh avfoundation child on every reconnect.
    unsafe { screen_capture_access().is_none_or(|api| (api.preflight)()) }
}

pub fn request_capture_access() {
    unsafe {
        if let Some(api) = screen_capture_access() {
            (api.request)();
        }
    }
}

// Drain main-thread system notifications, either alongside the menu's AppKit
// event loop or between accepts when running without a menu.
pub fn poll_events() {
    unsafe { CFRunLoopRunInMode(kCFRunLoopDefaultMode, 0., false) };
}

// Own every retained CoreFoundation/CG object, including error paths.
struct Owned(Ref);
impl Drop for Owned {
    fn drop(&mut self) {
        if !self.0.is_null() {
            unsafe { CFRelease(self.0) }
        }
    }
}
impl Owned {
    fn post(&self) {
        if !self.0.is_null() {
            unsafe { CGEventPost(0, self.0) }
        }
    }
}

pub fn display_asleep() -> bool {
    unsafe { CGDisplayIsAsleep(CGMainDisplayID()) }
}
pub fn display_state() {
    if display_asleep() {
        println!("SCREEN_ASLEEP");
    }
    unsafe {
        let session = Owned(CGSessionCopyCurrentDictionary());
        let key = Owned(CFStringCreateWithCString(
            ptr::null(),
            c"CGSSessionScreenIsLocked".as_ptr(),
            0x08000100,
        ));
        if !session.0.is_null()
            && !key.0.is_null()
            && !CFDictionaryGetValue(session.0, key.0).is_null()
        {
            println!("SCREEN_LOCKED");
        }
    }
}
pub fn wake_display() {}

#[derive(Default)]
pub struct Input {
    origin: Option<(f64, f64)>,
    left: bool,
    right: bool,
    last: (f64, f64),
}

impl Input {
    pub fn status(&mut self) -> u8 {
        // We synthesize CGEvents, not AX UI queries. AXIsProcessTrusted can
        // retain a denied result after a grant in a long-running macOS daemon.
        if input_trusted() {
            0
        } else {
            1
        }
    }
    unsafe fn mouse(source: Ref, kind: u32, point: Point, button: u32) {
        Owned(CGEventCreateMouseEvent(source, kind, point, button)).post();
    }
    unsafe fn key(source: Ref, code: u16, down: bool, flags: u64) {
        let event = Owned(CGEventCreateKeyboardEvent(source, code, down));
        if !event.0.is_null() {
            CGEventSetFlags(event.0, flags);
            event.post();
        }
    }
    pub fn inject(&mut self, control: &Control<'_>) {
        // A lease heartbeat is not user input and must never request consent.
        if control.kind == 13 {
            return;
        }
        if self.status() != 0 {
            // Reconnection creates a new Input, but must not create a new
            // permission alert on every click or every session.
            if !PERMISSION_PROMPTED.swap(true, Ordering::AcqRel) {
                request_input_access();
            }
            return;
        }
        unsafe {
            let source = Owned(CGEventSourceCreate(-1));
            if source.0.is_null() {
                return;
            }
            if control.kind == 4 {
                // Per Unicode scalar, never split a UTF-16 surrogate pair.
                for character in control.text.chars() {
                    let mut buffer = [0u16; 2];
                    let chars = character.encode_utf16(&mut buffer);
                    for down in [true, false] {
                        let event = Owned(CGEventCreateKeyboardEvent(source.0, 0, down));
                        if !event.0.is_null() {
                            CGEventSetFlags(event.0, 0);
                            CGEventKeyboardSetUnicodeString(event.0, chars.len(), chars.as_ptr());
                            event.post();
                        }
                    }
                }
                return;
            }
            if control.kind == 5 {
                let Ok(key) = u16::try_from(control.extra) else {
                    return;
                };
                let mask = control.x as u32;
                let modifiers = [
                    (55, 1, 1u64 << 20),
                    (56, 2, 1 << 17),
                    (59, 4, 1 << 18),
                    (58, 8, 1 << 19),
                ];
                let flags = modifiers
                    .iter()
                    .filter(|(_, bit, _)| mask & bit != 0)
                    .fold(0, |f, (_, _, flag)| f | flag);
                for (code, bit, _) in modifiers {
                    if mask & bit != 0 {
                        Self::key(source.0, code, true, flags);
                    }
                }
                Self::key(source.0, key, true, flags);
                Self::key(source.0, key, false, flags);
                for (code, bit, _) in modifiers.into_iter().rev() {
                    if mask & bit != 0 {
                        Self::key(source.0, code, false, 0);
                    }
                }
                return;
            }
            let bounds = CGDisplayBounds(CGMainDisplayID());
            let point = Point {
                x: bounds.origin.x + f64::from(control.x.clamp(0., 1.)) * bounds.size.width,
                y: bounds.origin.y + f64::from(control.y.clamp(0., 1.)) * bounds.size.height,
            };
            self.last = (point.x, point.y);
            match control.kind {
                0 => Self::mouse(source.0, 5, point, 0),
                1 => {
                    self.left = true;
                    Self::mouse(source.0, 1, point, 0);
                }
                2 => {
                    self.left = false;
                    Self::mouse(source.0, 2, point, 0);
                }
                3 => {
                    let event = Owned(CGEventCreateScrollWheelEvent(
                        source.0,
                        1,
                        1,
                        control.extra.clamp(-1000, 1000),
                    ));
                    if !event.0.is_null() {
                        CGEventSetLocation(event.0, point);
                        event.post();
                    }
                }
                6 => {
                    self.right = true;
                    Self::mouse(source.0, 3, point, 1);
                }
                7 => {
                    self.right = false;
                    Self::mouse(source.0, 4, point, 1);
                }
                8 | 9 => {
                    let current = Owned(CGEventCreate(ptr::null()));
                    let origin = (!current.0.is_null()).then(|| CGEventGetLocation(current.0));
                    let (down, up, button) = if control.kind == 8 {
                        (1, 2, 0)
                    } else {
                        (3, 4, 1)
                    };
                    Self::mouse(source.0, down, point, button);
                    Self::mouse(source.0, up, point, button);
                    if let Some(origin) = origin {
                        Self::mouse(source.0, 5, origin, 0);
                    }
                }
                10 => {
                    if self.origin.is_none() {
                        let current = Owned(CGEventCreate(ptr::null()));
                        if !current.0.is_null() {
                            let p = CGEventGetLocation(current.0);
                            self.origin = Some((p.x, p.y));
                        }
                    }
                    self.left = true;
                    Self::mouse(source.0, 1, point, 0);
                }
                11 if self.left => Self::mouse(source.0, 6, point, 0),
                12 => self.release(),
                _ => (),
            }
        }
    }
    pub fn release(&mut self) {
        unsafe {
            let source = Owned(CGEventSourceCreate(-1));
            let point = Point {
                x: self.last.0,
                y: self.last.1,
            };
            if self.left {
                Self::mouse(source.0, 2, point, 0);
            }
            if self.right {
                Self::mouse(source.0, 4, point, 1);
            }
            if let Some((x, y)) = self.origin.take() {
                Self::mouse(source.0, 5, Point { x, y }, 0);
            }
        }
        self.left = false;
        self.right = false;
    }
}

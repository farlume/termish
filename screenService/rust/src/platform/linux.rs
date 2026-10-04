use crate::protocol::Control;
use std::{
    io::Write,
    process::{Command, Stdio},
    thread,
    time::{Duration, Instant},
};
use x11rb::{
    connection::Connection,
    protocol::{
        xproto::{
            ConnectionExt as _, BUTTON_PRESS_EVENT as BUTTON_PRESS,
            BUTTON_RELEASE_EVENT as BUTTON_RELEASE, KEY_PRESS_EVENT as KEY_PRESS,
            KEY_RELEASE_EVENT as KEY_RELEASE, MOTION_NOTIFY_EVENT as MOTION_NOTIFY,
        },
        xtest::ConnectionExt as _,
    },
    rust_connection::RustConnection,
};

pub fn display_asleep() -> bool {
    false
}
pub fn display_state() {}
pub fn wake_display() {
    // Keep the graphical session awake without changing its DPMS preferences.
    // Installed xset is optional; don't wait indefinitely on a broken display.
    for args in [&["s", "reset"][..], &["dpms", "force", "on"][..]] {
        if let Ok(mut child) = Command::new("xset")
            .args(args)
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .spawn()
        {
            let deadline = Instant::now() + Duration::from_secs(1);
            while matches!(child.try_wait(), Ok(None)) && Instant::now() < deadline {
                thread::sleep(Duration::from_millis(20));
            }
            let _ = child.kill();
            let _ = child.wait();
        }
    }
}

#[derive(Default)]
pub struct X11Input {
    display: Option<(RustConnection, usize)>,
    left: bool,
    right: bool,
}
impl X11Input {
    fn connect(&mut self) -> bool {
        if self.display.is_none() {
            self.display = x11rb::connect(None).ok().filter(|(c, _)| {
                c.xtest_get_version(2, 2)
                    .ok()
                    .and_then(|r| r.reply().ok())
                    .is_some()
            });
        }
        self.display.is_some()
    }
    pub fn status(&mut self) -> u8 {
        if self.connect() {
            0
        } else {
            2
        }
    }
    fn keycode(connection: &RustConnection, kvk: i32) -> Option<u8> {
        let name = keysym(kvk)?;
        let setup = connection.setup();
        let min = setup.min_keycode;
        let reply = connection
            .get_keyboard_mapping(min, setup.max_keycode - min + 1)
            .ok()?
            .reply()
            .ok()?;
        reply
            .keysyms
            .chunks(reply.keysyms_per_keycode as usize)
            .position(|keys| keys.contains(&name))
            .map(|i| min + i as u8)
    }
    pub(super) fn unused_keysym(kvk: i32) -> Option<u32> {
        Some(match kvk {
            0 => b'a' as u32,
            1 => b's' as u32,
            2 => b'd' as u32,
            3 => b'f' as u32,
            4 => b'h' as u32,
            5 => b'g' as u32,
            6 => b'z' as u32,
            7 => b'x' as u32,
            8 => b'c' as u32,
            9 => b'v' as u32,
            11 => b'b' as u32,
            12 => b'q' as u32,
            13 => b'w' as u32,
            14 => b'e' as u32,
            15 => b'r' as u32,
            16 => b'y' as u32,
            17 => b't' as u32,
            18 => 49,
            19 => 50,
            20 => 51,
            21 => 52,
            22 => 54,
            23 => 53,
            24 => 61,
            25 => 57,
            26 => 55,
            27 => 45,
            28 => 56,
            29 => 48,
            30 => 93,
            31 => 111,
            32 => 117,
            33 => 91,
            34 => 105,
            35 => 112,
            37 => 108,
            38 => 106,
            39 => 39,
            40 => 107,
            41 => 59,
            42 => 92,
            43 => 44,
            44 => 47,
            45 => 110,
            46 => 109,
            47 => 46,
            50 => 96,
            36 => 0xff0d,
            48 => 0xff09,
            49 => 32,
            51 => 0xff08,
            53 => 0xff1b,
            115 => 0xff50,
            119 => 0xff57,
            116 => 0xff55,
            121 => 0xff56,
            117 => 0xffff,
            123 => 0xff51,
            124 => 0xff53,
            125 => 0xff54,
            126 => 0xff52,
            55 => 0xffeb,
            56 => 0xffe1,
            59 => 0xffe3,
            58 => 0xffe9,
            122 => 0xffbe,
            120 => 0xffbf,
            99 => 0xffc0,
            118 => 0xffc1,
            96 => 0xffc2,
            97 => 0xffc3,
            98 => 0xffc4,
            100 => 0xffc5,
            101 => 0xffc6,
            109 => 0xffc7,
            103 => 0xffc8,
            111 => 0xffc9,
            _ => return None,
        })
    }
    fn event(c: &RustConnection, kind: u8, detail: u8, root: u32, x: i16, y: i16) {
        let _ = c.xtest_fake_input(kind, detail, 0, root, x, y, 0);
    }
    fn combo(c: &RustConnection, kvk: i32, mask: u32) {
        let Some(key) = Self::keycode(c, kvk) else {
            return;
        };
        let modifiers: Vec<u8> = [(55, 1), (56, 2), (59, 4), (58, 8)]
            .into_iter()
            .filter(|(_, bit)| mask & bit != 0)
            .filter_map(|(kvk, _)| Self::keycode(c, kvk))
            .collect();
        for key in &modifiers {
            Self::event(c, KEY_PRESS, *key, 0, 0, 0);
        }
        Self::event(c, KEY_PRESS, key, 0, 0, 0);
        Self::event(c, KEY_RELEASE, key, 0, 0, 0);
        for key in modifiers.into_iter().rev() {
            Self::event(c, KEY_RELEASE, key, 0, 0, 0);
        }
    }
    pub fn inject(&mut self, control: &Control<'_>) {
        if !self.connect() || control.kind == 13 {
            return;
        }
        let (c, screen) = self.display.as_ref().unwrap();
        let root = c.setup().roots[*screen].root;
        let Some(size) = c.get_geometry(root).ok().and_then(|r| r.reply().ok()) else {
            self.display = None;
            return;
        };
        let x = (control.x.clamp(0., 1.) * f32::from(size.width.saturating_sub(1))) as i16;
        let y = (control.y.clamp(0., 1.) * f32::from(size.height.saturating_sub(1))) as i16;
        let motion = || Self::event(c, MOTION_NOTIFY, 0, root, x, y);
        match control.kind {
            0 => motion(),
            1 | 10 => {
                motion();
                Self::event(c, BUTTON_PRESS, 1, 0, 0, 0);
                self.left = true;
            }
            2 | 12 => {
                motion();
                Self::event(c, BUTTON_RELEASE, 1, 0, 0, 0);
                self.left = false;
            }
            11 if self.left => motion(),
            6 => {
                motion();
                Self::event(c, BUTTON_PRESS, 3, 0, 0, 0);
                self.right = true;
            }
            7 => {
                motion();
                Self::event(c, BUTTON_RELEASE, 3, 0, 0, 0);
                self.right = false;
            }
            8 | 9 => {
                motion();
                let button = if control.kind == 8 { 1 } else { 3 };
                Self::event(c, BUTTON_PRESS, button, 0, 0, 0);
                Self::event(c, BUTTON_RELEASE, button, 0, 0, 0);
            }
            3 => {
                for _ in 0..control.extra.unsigned_abs().min(30) {
                    let button = if control.extra > 0 { 4 } else { 5 };
                    Self::event(c, BUTTON_PRESS, button, 0, 0, 0);
                    Self::event(c, BUTTON_RELEASE, button, 0, 0, 0);
                }
            }
            5 => Self::combo(c, control.extra, control.x as u32),
            4 if !control.text.is_empty() => {
                if let Ok(mut child) = Command::new("xclip")
                    .args(["-selection", "clipboard"])
                    .stdin(Stdio::piped())
                    .stdout(Stdio::null())
                    .stderr(Stdio::null())
                    .spawn()
                {
                    let ok = child
                        .stdin
                        .take()
                        .map(|mut pipe| pipe.write_all(control.text.as_bytes()).is_ok())
                        .unwrap_or(false);
                    let deadline = Instant::now() + Duration::from_secs(2);
                    while matches!(child.try_wait(), Ok(None)) && Instant::now() < deadline {
                        thread::sleep(Duration::from_millis(20));
                    }
                    let completed = child
                        .try_wait()
                        .ok()
                        .flatten()
                        .map(|s| s.success())
                        .unwrap_or(false);
                    if !completed {
                        let _ = child.kill();
                    }
                    let _ = child.wait();
                    if ok && completed {
                        Self::combo(c, 9, 4);
                    }
                }
            }
            _ => (),
        }
        if c.flush().is_err() {
            self.display = None;
        }
    }
    pub fn release(&mut self) {
        if let Some((c, _)) = &self.display {
            if self.left {
                Self::event(c, BUTTON_RELEASE, 1, 0, 0, 0);
            }
            if self.right {
                Self::event(c, BUTTON_RELEASE, 3, 0, 0, 0);
            }
            let _ = c.flush();
        }
        self.left = false;
        self.right = false;
    }
}

pub(super) fn keysym(kvk: i32) -> Option<u32> {
    X11Input::unused_keysym(kvk)
}

#[derive(Default)]
pub struct Input {
    x11: X11Input,
}
impl Input {
    pub fn status(&mut self) -> u8 {
        if super::wayland::active() {
            0
        } else {
            self.x11.status()
        }
    }
    pub fn inject(&mut self, control: &Control<'_>) {
        if super::wayland::active() {
            let _ = super::wayland::inject(control);
        } else {
            self.x11.inject(control);
        }
    }
    pub fn release(&mut self) {
        if super::wayland::active() {
            let _ = super::wayland::release();
        } else {
            self.x11.release();
        }
    }
}

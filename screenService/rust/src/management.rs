//! Local menu requests use the existing session lifecycle, not the wire protocol.
use std::sync::{
    atomic::AtomicBool,
    mpsc::{self, Receiver, Sender},
    Mutex,
};

#[derive(Clone, Copy, Debug, PartialEq)]
// Linux remains headless; actions are constructed by the macOS menu or tests.
#[cfg_attr(not(any(target_os = "macos", test)), allow(dead_code))]
pub enum Action {
    TogglePause = 1,
    Disconnect = 2,
    Restart = 3,
    Quit = 4,
}
#[cfg(any(target_os = "macos", test))]
impl Action {
    pub fn from_raw(value: u32) -> Option<Self> {
        match value {
            1 => Some(Self::TogglePause),
            2 => Some(Self::Disconnect),
            3 => Some(Self::Restart),
            4 => Some(Self::Quit),
            _ => None,
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub enum Outcome {
    Quit,
    Restart,
}

pub struct Management {
    pub ready: AtomicBool,
    pub connected: AtomicBool,
    pub paused: AtomicBool,
    #[cfg_attr(not(any(target_os = "macos", test)), allow(dead_code))]
    sender: Sender<Action>,
    receiver: Mutex<Receiver<Action>>,
}
impl Default for Management {
    fn default() -> Self {
        let (sender, receiver) = mpsc::channel();
        Self {
            ready: AtomicBool::new(false),
            connected: AtomicBool::new(false),
            paused: AtomicBool::new(false),
            sender,
            receiver: Mutex::new(receiver),
        }
    }
}
impl Management {
    #[cfg(any(target_os = "macos", test))]
    pub fn request(&self, action: Action) {
        let _ = self.sender.send(action);
    }
    pub fn next_request(&self) -> Option<Action> {
        self.receiver.lock().unwrap().try_recv().ok()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn menu_requests_preserve_order_and_reject_unknown_actions() {
        let control = Management::default();
        assert!(Action::from_raw(0).is_none());
        assert!(Action::from_raw(100).is_none());
        for raw in 1..=4 {
            control.request(Action::from_raw(raw).unwrap());
        }
        assert_eq!(control.next_request(), Some(Action::TogglePause));
        assert_eq!(control.next_request(), Some(Action::Disconnect));
        assert_eq!(control.next_request(), Some(Action::Restart));
        assert_eq!(control.next_request(), Some(Action::Quit));
        assert!(control.next_request().is_none());
    }
}

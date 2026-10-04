/// One consent request per daemon lifetime, not per connection or encoder.
#[derive(Default)]
pub struct CapturePermission {
    requested: bool,
}

impl CapturePermission {
    pub fn check(&mut self, allowed: impl FnOnce() -> bool, request: impl FnOnce()) -> bool {
        if allowed() {
            return true;
        }
        if !self.requested {
            self.requested = true;
            request();
        }
        // A consent dialog is asynchronous. Do not start an encoder until a
        // subsequent preflight confirms permission for the running identity.
        false
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::cell::Cell;

    #[test]
    fn denied_reconnections_request_once_and_a_later_grant_is_observed() {
        let requests = Cell::new(0);
        let mut gate = CapturePermission::default();
        for _ in 0..3 {
            assert!(!gate.check(|| false, || requests.set(requests.get() + 1)));
        }
        assert!(gate.check(|| true, || panic!("grant must not prompt")));
        assert!(!gate.check(|| false, || panic!("revocation must not prompt again")));
        assert_eq!(requests.get(), 1);
    }

    #[test]
    fn existing_grant_does_not_consume_the_single_request() {
        let mut gate = CapturePermission::default();
        assert!(gate.check(|| true, || panic!("already authorized")));
        let requested = Cell::new(false);
        assert!(!gate.check(|| false, || requested.set(true)));
        assert!(requested.get());
    }
}

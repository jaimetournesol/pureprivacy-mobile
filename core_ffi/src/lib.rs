//! PurePrivacy mobile shared core.
//!
//! One thin Rust crate, exposed to **SwiftUI (iOS)** and **Compose (Android)**
//! via UniFFI, so both platforms share exactly one networking/crypto core and
//! the only per-platform code is the UI. This crate is the *single point of
//! contact* with matrix-rust-sdk (whose FFI churns) and with arti (embedded
//! Tor) — pin them here, isolate them here.
//!
//! Status: this is the Phase-2 *foundation* — the FFI contract is final and the
//! bindings generate, so the iOS/Android shells can build against it today. The
//! matrix-sdk + arti wiring behind these methods is the next milestone (see
//! Cargo.toml + README "Wiring the real client").

uniffi::setup_scaffolding!();

use std::sync::Arc;

use thiserror::Error;

#[derive(Debug, Error, uniffi::Error)]
pub enum PpError {
    #[error("not implemented yet: {0}")]
    NotImplemented(String),
    #[error("tor error: {0}")]
    Tor(String),
    #[error("matrix error: {0}")]
    Matrix(String),
    #[error("not connected — call connect_over_tor first")]
    NotConnected,
}

/// Embedded-Tor bootstrap state, surfaced so the UI can show honest staged
/// progress ("Building a private path…") instead of a dead spinner.
#[derive(uniffi::Enum, Clone, PartialEq, Eq, Debug)]
pub enum TorStage {
    Idle,
    Bootstrapping,
    Connected,
    Failed,
}

#[derive(uniffi::Record, Clone)]
pub struct RoomSummary {
    pub id: String,
    pub name: String,
    pub unread: u32,
}

#[derive(uniffi::Record, Clone)]
pub struct Session {
    pub user_id: String,
    pub device_id: String,
}

/// The mobile client handle. Construct once with a persistent data dir; the
/// platform UI calls the async methods.
#[derive(uniffi::Object)]
pub struct PpClient {
    data_dir: String,
}

#[uniffi::export(async_runtime = "tokio")]
impl PpClient {
    /// `data_dir`: app-private storage for the crypto store + Tor state.
    #[uniffi::constructor]
    pub fn new(data_dir: String) -> Arc<Self> {
        Arc::new(Self { data_dir })
    }

    pub fn data_dir(&self) -> String {
        self.data_dir.clone()
    }

    /// Current embedded-Tor stage (drives the onboarding progress UI).
    pub fn tor_stage(&self) -> TorStage {
        TorStage::Idle
    }

    /// Bootstrap embedded Tor (arti, no Orbot) and log in to the user's box at
    /// `onion` with `user`/`password`. NEXT MILESTONE: arti + matrix-sdk wiring.
    pub async fn connect_over_tor(
        &self,
        onion: String,
        user: String,
        password: String,
    ) -> Result<Session, PpError> {
        let _ = (onion, user, password);
        Err(PpError::NotImplemented(
            "connect_over_tor: arti + matrix-sdk wiring is the next milestone".into(),
        ))
    }

    /// Restore a prior session from the persisted store (no password needed).
    pub async fn restore(&self) -> Result<Session, PpError> {
        Err(PpError::NotImplemented("restore".into()))
    }

    pub async fn sync_once(&self) -> Result<(), PpError> {
        Err(PpError::NotConnected)
    }

    pub async fn rooms(&self) -> Result<Vec<RoomSummary>, PpError> {
        Err(PpError::NotConnected)
    }

    pub async fn send_message(&self, room_id: String, body: String) -> Result<String, PpError> {
        let _ = (room_id, body);
        Err(PpError::NotConnected)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn constructs_and_reports_data_dir() {
        let c = PpClient::new("/tmp/pp".into());
        assert_eq!(c.data_dir(), "/tmp/pp");
        assert_eq!(c.tor_stage(), TorStage::Idle);
    }

    #[tokio::test]
    async fn methods_are_callable_and_return_the_expected_stubs() {
        let c = PpClient::new("/tmp/pp".into());
        assert!(matches!(
            c.connect_over_tor("x.onion".into(), "u".into(), "p".into()).await,
            Err(PpError::NotImplemented(_))
        ));
        assert!(matches!(c.rooms().await, Err(PpError::NotConnected)));
    }
}

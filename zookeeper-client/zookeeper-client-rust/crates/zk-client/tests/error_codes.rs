// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

//! Public error type verification: protocol error code bidirectional mappings, client side
//! synthetic error representations, classification predicates, retry safety policies, and
//! `Display` formatting.

#![allow(clippy::unwrap_used)]
#![allow(clippy::disallowed_macros)]

use apache_zookeeper::{DisconnectCause, Error, MultiError, OpKind, WatchError, ZPath};

/// Complete table mapping server wire return codes to corresponding typed `Error` variants.
const TABLE: &[(i32, Error)] = &[
    (-1, Error::SystemError),
    (-2, Error::RuntimeInconsistency),
    (-3, Error::DataInconsistency),
    (-4, Error::ConnectionLoss),
    (-5, Error::MarshallingError),
    (-6, Error::Unimplemented),
    (-7, Error::OperationTimeout),
    (-8, Error::BadArguments),
    (-9, Error::InvalidState),
    (-12, Error::UnknownSession),
    (-13, Error::NewConfigNoQuorum),
    (-14, Error::ReconfigInProgress),
    (-15, Error::SslConnectionError),
    (-100, Error::ApiError),
    (-101, Error::NoNode),
    (-102, Error::NoAuth),
    (-103, Error::BadVersion),
    (-108, Error::NoChildrenForEphemerals),
    (-110, Error::NodeExists),
    (-111, Error::NotEmpty),
    (-112, Error::SessionExpired),
    (-113, Error::InvalidCallback),
    (-114, Error::InvalidAcl),
    (-115, Error::AuthFailed),
    (-116, Error::Closing),
    (-117, Error::Nothing),
    (-118, Error::SessionMoved),
    (-119, Error::NotReadOnly),
    (-120, Error::EphemeralOnLocalSession),
    (-121, Error::NoWatcher),
    (-122, Error::RequestTimeout),
    (-123, Error::ReconfigDisabled),
    (-124, Error::SessionClosedRequireSaslAuth),
    (-125, Error::QuotaExceeded),
    (-127, Error::ThrottledOp),
];

#[test]
fn code_round_trip() {
    for (code, e) in TABLE {
        assert_eq!(e.code(), *code, "{e:?}");
        assert_eq!(Error::from_code(*code).as_ref(), Some(e), "{code}");
    }
    assert_eq!(Error::from_code(0), None);
    assert_eq!(Error::from_code(-10), Some(Error::Unknown(-10)));
    assert_eq!(Error::from_code(-126), Some(Error::Unknown(-126)));
    assert_eq!(Error::from_code(7), Some(Error::Unknown(7)));
    assert_eq!(Error::Unknown(-999).code(), -999);
}

#[test]
fn client_side_codes() {
    let pe = ZPath::new("a").unwrap_err();
    assert_eq!(Error::InvalidPath(pe).code(), -8);
    assert_eq!(Error::InvalidArgument("x".into()).code(), -8);
    assert_eq!(Error::TooManyPendingRequests.code(), -127);
}

#[test]
fn predicates() {
    for (_, e) in TABLE {
        let loss = matches!(
            e,
            Error::ConnectionLoss
                | Error::OperationTimeout
                | Error::SslConnectionError
                | Error::RuntimeInconsistency
        );
        assert_eq!(e.is_connection_loss(), loss, "{e:?}");
        let terminal = matches!(
            e,
            Error::SessionExpired | Error::AuthFailed | Error::Closing
        );
        assert_eq!(e.is_session_terminal(), terminal, "{e:?}");
    }
}

#[test]
fn retry_safety() {
    let loss = Error::ConnectionLoss;
    for op in [
        OpKind::Exists,
        OpKind::GetData,
        OpKind::GetAcl,
        OpKind::GetChildren,
        OpKind::GetAllChildrenNumber,
        OpKind::GetEphemerals,
        OpKind::Sync,
        OpKind::MultiRead,
        OpKind::AddWatch,
        OpKind::RemoveWatches,
        OpKind::GetConfig,
        OpKind::WhoAmI,
        OpKind::Close,
    ] {
        assert!(loss.is_retry_safe(op), "{op:?}");
    }
    for op in [
        OpKind::Create,
        OpKind::Delete,
        OpKind::SetData,
        OpKind::SetAcl,
        OpKind::Multi,
        OpKind::Reconfig,
        OpKind::AddAuth,
    ] {
        assert!(!loss.is_retry_safe(op), "{op:?}");
        assert!(!Error::OperationTimeout.is_retry_safe(op), "{op:?}");
        assert!(Error::NodeExists.is_retry_safe(op), "{op:?}");
        assert!(Error::TooManyPendingRequests.is_retry_safe(op), "{op:?}");
    }
}

#[test]
fn display_texts() {
    assert_eq!(Error::NoNode.to_string(), "KeeperErrorCode = NoNode");
    assert_eq!(
        Error::ConnectionLoss.to_string(),
        "KeeperErrorCode = ConnectionLoss"
    );
    assert_eq!(
        Error::NotEmpty.to_string(),
        "KeeperErrorCode = Directory not empty"
    );
    assert_eq!(
        Error::SessionExpired.to_string(),
        "KeeperErrorCode = Session expired"
    );
    assert_eq!(
        Error::InvalidAcl.to_string(),
        "KeeperErrorCode = InvalidACL"
    );
    assert_eq!(Error::ApiError.to_string(), "KeeperErrorCode = APIError");
    assert_eq!(
        Error::SslConnectionError.to_string(),
        "KeeperErrorCode = SSL connection error"
    );
    assert_eq!(
        Error::Unknown(-42).to_string(),
        "KeeperErrorCode = Unknown error -42"
    );
    assert_eq!(
        Error::InvalidArgument("bad port".into()).to_string(),
        "invalid argument: bad port"
    );
    let pe = ZPath::new("/a/").unwrap_err();
    assert_eq!(Error::InvalidPath(pe.clone()).to_string(), pe.to_string());
    assert!(
        Error::TooManyPendingRequests
            .to_string()
            .contains("too many pending")
    );
    // Verify that each server error code yields a distinct canonical display string.
    let mut seen = std::collections::HashSet::new();
    for (_, e) in TABLE {
        let text = e.to_string();
        assert!(text.starts_with("KeeperErrorCode = "), "{text}");
        assert!(!text.contains("Unknown error"), "{text}");
        assert!(seen.insert(text), "{e:?}");
    }
}

#[test]
fn other_display_texts() {
    let causes = [
        DisconnectCause::ReceiveTimeout,
        DisconnectCause::TlsHandshakeTimeout,
        DisconnectCause::XidMismatch,
        DisconnectCause::MalformedFrame,
        DisconnectCause::ServerClosed,
    ];
    let texts: std::collections::HashSet<String> = causes.iter().map(ToString::to_string).collect();
    assert_eq!(texts.len(), causes.len());
    assert_eq!(
        DisconnectCause::ServerClosed.to_string(),
        "the server closed the connection"
    );

    let aborted = MultiError::Aborted {
        index: 1,
        error: Error::NoNode,
        per_op: vec![None, Some(Error::NoNode)],
    };
    assert_eq!(
        aborted.to_string(),
        "transaction rolled back: operation 1 failed: KeeperErrorCode = NoNode"
    );
    assert_eq!(
        MultiError::Request(Error::ConnectionLoss).to_string(),
        "KeeperErrorCode = ConnectionLoss"
    );

    assert_eq!(WatchError::Removed.to_string(), "the watcher was removed");
    assert_eq!(
        WatchError::Lagged { missed: 3 }.to_string(),
        "the watcher dropped 3 events because its buffer was full"
    );
    let watch = [
        WatchError::Removed,
        WatchError::ConnectionLost,
        WatchError::SessionExpired,
        WatchError::AuthFailed,
        WatchError::Closed,
        WatchError::Lagged { missed: 1 },
        WatchError::InvalidState,
    ];
    let texts: std::collections::HashSet<String> = watch.iter().map(ToString::to_string).collect();
    assert_eq!(texts.len(), watch.len());
}

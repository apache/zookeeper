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

//! Serialization and deserialization tests for domain value types using `serde`:
//! trait implementations, wire integer representations, TTL range validation, and struct field
//! preservation.

#![cfg(feature = "serde")]
#![allow(clippy::unwrap_used)]
#![allow(clippy::disallowed_macros)]

use std::borrow::Cow;
use std::collections::BTreeMap;

use apache_zookeeper::{Acl, CreateMode, Id, Perms, SessionId, Stat, Ttl, Version, Zxid};
use serde::de::Deserialize;
use serde::de::DeserializeOwned;
use serde::de::IntoDeserializer;
use serde::de::value::{Error as DeError, MapDeserializer};
use serde::ser::{Impossible, Serialize, SerializeStruct, Serializer};

/// Represents an individual emission event captured during serialization traversal.
#[derive(Debug, PartialEq, Eq)]
enum Tok {
    I32(i32),
    I64(i64),
    U64(u64),
    Str(String),
    Struct(&'static str),
    Field(&'static str),
    End,
    Variant(&'static str),
}

/// Diagnostic error emitted when an unsupported serialization method is invoked.
#[derive(Debug)]
struct SerError(String);

impl std::fmt::Display for SerError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.0)
    }
}

impl std::error::Error for SerError {}

impl serde::ser::Error for SerError {
    fn custom<T: std::fmt::Display>(msg: T) -> Self {
        Self(msg.to_string())
    }
}

/// Recording serializer that captures structural tokens emitted by ZooKeeper value type
/// serializers.
#[derive(Default)]
struct Tokens(Vec<Tok>);

fn no<T>() -> Result<T, SerError> {
    Err(SerError("shape not used by the value types".into()))
}

impl Serializer for &mut Tokens {
    type Ok = ();
    type Error = SerError;
    type SerializeSeq = Impossible<(), SerError>;
    type SerializeTuple = Impossible<(), SerError>;
    type SerializeTupleStruct = Impossible<(), SerError>;
    type SerializeTupleVariant = Impossible<(), SerError>;
    type SerializeMap = Impossible<(), SerError>;
    type SerializeStruct = Self;
    type SerializeStructVariant = Impossible<(), SerError>;

    fn serialize_bool(self, _: bool) -> Result<(), SerError> {
        no()
    }
    fn serialize_i8(self, _: i8) -> Result<(), SerError> {
        no()
    }
    fn serialize_i16(self, _: i16) -> Result<(), SerError> {
        no()
    }
    fn serialize_i32(self, v: i32) -> Result<(), SerError> {
        self.0.push(Tok::I32(v));
        Ok(())
    }
    fn serialize_i64(self, v: i64) -> Result<(), SerError> {
        self.0.push(Tok::I64(v));
        Ok(())
    }
    fn serialize_u8(self, _: u8) -> Result<(), SerError> {
        no()
    }
    fn serialize_u16(self, _: u16) -> Result<(), SerError> {
        no()
    }
    fn serialize_u32(self, _: u32) -> Result<(), SerError> {
        no()
    }
    fn serialize_u64(self, v: u64) -> Result<(), SerError> {
        self.0.push(Tok::U64(v));
        Ok(())
    }
    fn serialize_f32(self, _: f32) -> Result<(), SerError> {
        no()
    }
    fn serialize_f64(self, _: f64) -> Result<(), SerError> {
        no()
    }
    fn serialize_char(self, _: char) -> Result<(), SerError> {
        no()
    }
    fn serialize_str(self, v: &str) -> Result<(), SerError> {
        self.0.push(Tok::Str(v.to_owned()));
        Ok(())
    }
    fn serialize_bytes(self, _: &[u8]) -> Result<(), SerError> {
        no()
    }
    fn serialize_none(self) -> Result<(), SerError> {
        no()
    }
    fn serialize_some<T: ?Sized + Serialize>(self, _: &T) -> Result<(), SerError> {
        no()
    }
    fn serialize_unit(self) -> Result<(), SerError> {
        no()
    }
    fn serialize_unit_struct(self, _: &'static str) -> Result<(), SerError> {
        no()
    }
    fn serialize_unit_variant(
        self,
        _: &'static str,
        _: u32,
        variant: &'static str,
    ) -> Result<(), SerError> {
        self.0.push(Tok::Variant(variant));
        Ok(())
    }
    fn serialize_newtype_struct<T: ?Sized + Serialize>(
        self,
        _: &'static str,
        _: &T,
    ) -> Result<(), SerError> {
        no()
    }
    fn serialize_newtype_variant<T: ?Sized + Serialize>(
        self,
        _: &'static str,
        _: u32,
        variant: &'static str,
        value: &T,
    ) -> Result<(), SerError> {
        self.0.push(Tok::Variant(variant));
        value.serialize(self)
    }
    fn serialize_seq(self, _: Option<usize>) -> Result<Self::SerializeSeq, SerError> {
        no()
    }
    fn serialize_tuple(self, _: usize) -> Result<Self::SerializeTuple, SerError> {
        no()
    }
    fn serialize_tuple_struct(
        self,
        _: &'static str,
        _: usize,
    ) -> Result<Self::SerializeTupleStruct, SerError> {
        no()
    }
    fn serialize_tuple_variant(
        self,
        _: &'static str,
        _: u32,
        _: &'static str,
        _: usize,
    ) -> Result<Self::SerializeTupleVariant, SerError> {
        no()
    }
    fn serialize_map(self, _: Option<usize>) -> Result<Self::SerializeMap, SerError> {
        no()
    }
    fn serialize_struct(self, name: &'static str, _: usize) -> Result<Self, SerError> {
        self.0.push(Tok::Struct(name));
        Ok(self)
    }
    fn serialize_struct_variant(
        self,
        _: &'static str,
        _: u32,
        _: &'static str,
        _: usize,
    ) -> Result<Self::SerializeStructVariant, SerError> {
        no()
    }
}

impl SerializeStruct for &mut Tokens {
    type Ok = ();
    type Error = SerError;

    fn serialize_field<T: ?Sized + Serialize>(
        &mut self,
        key: &'static str,
        value: &T,
    ) -> Result<(), SerError> {
        self.0.push(Tok::Field(key));
        value.serialize(&mut **self)
    }

    fn end(self) -> Result<(), SerError> {
        self.0.push(Tok::End);
        Ok(())
    }
}

fn tokens<T: Serialize>(v: &T) -> Vec<Tok> {
    let mut t = Tokens::default();
    v.serialize(&mut t).unwrap();
    t.0
}

fn assert_serde<T: Serialize + DeserializeOwned>() {}

#[test]
fn every_listed_type_has_both_traits() {
    assert_serde::<Stat>();
    assert_serde::<Acl>();
    assert_serde::<Id>();
    assert_serde::<Perms>();
    assert_serde::<Zxid>();
    assert_serde::<SessionId>();
    assert_serde::<CreateMode>();
    assert_serde::<Version>();
    assert_serde::<Ttl>();
}

#[test]
fn the_ids_and_perms_are_their_wire_integers() {
    assert_eq!(tokens(&Zxid(7)), [Tok::I64(7)]);
    assert_eq!(tokens(&SessionId(-2)), [Tok::I64(-2)]);
    assert_eq!(tokens(&Version::ANY), [Tok::I32(-1)]);
    assert_eq!(tokens(&(Perms::READ | Perms::ADMIN)), [Tok::I32(17)]);
    let unknown = Perms::from_bits_retain(0x40 | 1);
    assert_eq!(tokens(&unknown), [Tok::I32(0x41)]);
    let back: Result<Perms, DeError> = Perms::deserialize(0x41i32.into_deserializer());
    assert_eq!(back.map(|p| p.bits()).ok(), Some(0x41));
    let id: Result<SessionId, DeError> = SessionId::deserialize(9i64.into_deserializer());
    assert_eq!(id.ok(), Some(SessionId(9)));
}

#[test]
fn a_ttl_out_of_range_is_an_error() {
    let ttl = Ttl::from_millis(1500).unwrap();
    assert_eq!(tokens(&ttl), [Tok::U64(1500)]);
    let ok: Result<Ttl, DeError> = Ttl::deserialize(1500u64.into_deserializer());
    assert_eq!(ok.ok(), Some(ttl));
    for bad in [0u64, Ttl::MAX.as_millis() + 1] {
        let r: Result<Ttl, DeError> = Ttl::deserialize(bad.into_deserializer());
        assert!(r.is_err(), "{bad}");
    }
}

#[test]
fn the_records_keep_their_field_names() {
    let acl = Acl {
        perms: Perms::ALL,
        id: Id {
            scheme: Cow::Borrowed("world"),
            id: Cow::Borrowed("anyone"),
        },
    };
    assert_eq!(
        tokens(&acl),
        [
            Tok::Struct("Acl"),
            Tok::Field("perms"),
            Tok::I32(0x1f),
            Tok::Field("id"),
            Tok::Struct("Id"),
            Tok::Field("scheme"),
            Tok::Str("world".into()),
            Tok::Field("id"),
            Tok::Str("anyone".into()),
            Tok::End,
            Tok::End,
        ]
    );
    let ttl = Ttl::from_millis(60_000).unwrap();
    assert_eq!(
        tokens(&CreateMode::PersistentWithTtl(ttl)),
        [Tok::Variant("PersistentWithTtl"), Tok::U64(60_000)]
    );
    assert_eq!(tokens(&CreateMode::Container), [Tok::Variant("Container")]);
    let mode: Result<CreateMode, DeError> =
        CreateMode::deserialize("EphemeralSequential".into_deserializer());
    assert_eq!(mode.ok(), Some(CreateMode::EphemeralSequential));
}

#[test]
fn a_stat_comes_from_a_map() {
    let fields: BTreeMap<&str, i64> = [
        ("czxid", 1),
        ("mzxid", 2),
        ("pzxid", 3),
        ("ctime", 4),
        ("mtime", 5),
        ("version", 6),
        ("cversion", 7),
        ("aversion", 8),
        ("ephemeral_owner", 9),
        ("data_length", 10),
        ("num_children", 11),
    ]
    .into_iter()
    .collect();
    let d: MapDeserializer<'_, _, DeError> = MapDeserializer::new(fields.into_iter());
    let stat = Stat::deserialize(d).unwrap();
    assert_eq!(stat.czxid, Zxid(1));
    assert_eq!(stat.version, Version::new(6));
    assert_eq!(stat.num_children, 11);
    let t = tokens(&stat);
    assert_eq!(t.first(), Some(&Tok::Struct("Stat")));
    assert_eq!(t.get(1..3), Some(&[Tok::Field("czxid"), Tok::I64(1)][..]));
    assert_eq!(t.len(), 1 + 2 * 11 + 1);
}

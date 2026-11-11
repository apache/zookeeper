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

//! Low-level object file parsers and symbol table extractors for compiled libraries and archives.
//!
//! Provides hermetic readers for Mach-O (64-bit thin and fat/universal binaries), ELF (32-bit
//! and 64-bit architectures in both little endian and big endian representations), and `ar`
//! static archives. Implemented entirely using safe Rust standard library abstractions without
//! native toolchain dependencies, enabling cross-platform ABI verification on any host.
//!
//! Extracts structural ABI properties directly from object sections: symbol definitions,
//! linkage bindings, symbol visibility, function/data classification, section writability,
//! Mach-O dyld export tries, dynamic library identifiers (`DT_SONAME` / `LC_ID_DYLIB`), and
//! external dependency declarations.

use std::collections::{BTreeMap, BTreeSet};

use crate::util::Error;

/// Semantic category distinguishing executable code from data symbols.
#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord)]
pub(crate) enum SymKind {
    /// Executable code / function entry point.
    Func,
    /// Global or static data object.
    Data,
    /// Absolute, thread-local, or untyped symbol definition.
    Other,
}

impl SymKind {
    /// Returns the canonical TSV string representation for ABI golden baseline comparisons.
    pub(crate) const fn as_str(self) -> &'static str {
        match self {
            Self::Func => "func",
            Self::Data => "data",
            Self::Other => "other",
        }
    }
}

/// Binary container format identifying the underlying object structure.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Format {
    /// Apple Mach-O binary format.
    MachO,
    /// Executable and Linkable Format (ELF).
    Elf,
}

/// Represents a defined global symbol and its exported ABI attributes.
#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct Symbol {
    /// Exported symbol identifier (with Mach-O leading underscore stripped).
    pub(crate) name: String,
    /// Symbol classification (function, data object, or other).
    pub(crate) kind: SymKind,
    /// Indicates whether the symbol resides in a mutable/writable memory section.
    pub(crate) writable: bool,
    /// Explicit symbol byte size recorded in ELF symbol tables (omitted in Mach-O).
    pub(crate) size: Option<u64>,
    /// Calculated byte span to the succeeding symbol or enclosing section boundary,
    /// serving as an upper bound on Mach-O symbol allocations.
    pub(crate) span: Option<u64>,
    /// Virtual memory address or relative section offset where the symbol is located.
    pub(crate) addr: u64,
    /// Indicates weak symbol linkage, allowing preemptive overrides by other definitions.
    pub(crate) weak: bool,
    /// ELF symbol visibility attribute (0: default, 1: internal, 2: hidden, 3: protected).
    pub(crate) visibility: u8,
    /// Byte alignment boundary enforced by the enclosing section.
    pub(crate) section_align: u64,
    /// Absolute file offset of the symbol's initialized payload within the object file.
    pub(crate) file_off: Option<u64>,
}

/// Comprehensive symbol table facts and metadata harvested from an object file or shared library.
#[derive(Clone, Debug)]
pub(crate) struct Binary {
    /// Binary container format.
    pub(crate) format: Format,
    /// Indicates whether the binary is a dynamic shared library rather than a relocatable object.
    pub(crate) shared: bool,
    /// Defined exported (shared library) or global (relocatable object) symbols indexed by name.
    pub(crate) symbols: BTreeMap<String, Symbol>,
    /// Undefined external symbol dependencies required by the binary.
    pub(crate) undefined: BTreeSet<String>,
    /// Shared library dependencies specified via `DT_NEEDED` (ELF) or `LC_LOAD_DYLIB` (Mach-O).
    pub(crate) needed: Vec<String>,
    /// Embedded library identity string: `DT_SONAME` (ELF) or `LC_ID_DYLIB` install name (Mach-O).
    pub(crate) soname: Option<String>,
    /// Mach-O current and compatibility version pair formatted as `major.minor.patch`.
    pub(crate) dylib_versions: Option<(String, String)>,
    /// Publicly exported symbol names decoded from the Mach-O dyld export trie, if present.
    pub(crate) trie: Option<BTreeSet<String>>,
    /// Mach-O minimum deployment operating system version constraint.
    pub(crate) minos: Option<String>,
    /// Indicates whether the binary targets a 64-bit architecture.
    pub(crate) is64: bool,
    /// Indicates whether Mach-O two-level namespace symbol resolution is enabled (`MH_TWOLEVEL`).
    pub(crate) twolevel: Option<bool>,
}

/// Safe, bounds-checked binary cursor providing endianness-aware integer decoding and word slicing.
#[derive(Clone, Copy)]
struct R<'a> {
    b: &'a [u8],
    le: bool,
    wide: bool,
}

impl<'a> R<'a> {
    fn slice(&self, off: u64, len: u64) -> Result<&'a [u8], Error> {
        let start = usize::try_from(off).map_err(|_| trunc())?;
        let len = usize::try_from(len).map_err(|_| trunc())?;
        let end = start.checked_add(len).ok_or_else(trunc)?;
        self.b.get(start..end).ok_or_else(trunc)
    }

    /// Reads an `N`-byte field at `base + off`, normalizing to big-endian byte order.
    fn field<const N: usize>(&self, base: u64, off: u64) -> Result<[u8; N], Error> {
        let s = self.slice(add(base, off)?, N as u64)?;
        let mut a = [0_u8; N];
        a.copy_from_slice(s);
        if self.le {
            a.reverse();
        }
        Ok(a)
    }

    fn u8(&self, base: u64, off: u64) -> Result<u8, Error> {
        Ok(u8::from_be_bytes(self.field(base, off)?))
    }

    fn u16(&self, base: u64, off: u64) -> Result<u16, Error> {
        Ok(u16::from_be_bytes(self.field(base, off)?))
    }

    fn u32(&self, base: u64, off: u64) -> Result<u32, Error> {
        Ok(u32::from_be_bytes(self.field(base, off)?))
    }

    fn u64(&self, base: u64, off: u64) -> Result<u64, Error> {
        Ok(u64::from_be_bytes(self.field(base, off)?))
    }

    /// Decodes a 32-bit unsigned integer widened to `u64`.
    fn w32(&self, base: u64, off: u64) -> Result<u64, Error> {
        self.u32(base, off).map(u64::from)
    }

    /// Decodes an architecture-sized word (64-bit if `wide` is true, otherwise 32-bit).
    fn word(&self, base: u64, off: u64) -> Result<u64, Error> {
        if self.wide {
            self.u64(base, off)
        } else {
            self.w32(base, off)
        }
    }

    /// Reads a NUL-terminated C string from the string table at offset `base + off`.
    fn cstr(&self, base: u64, size: u64, off: u64) -> Result<String, Error> {
        let at = add(base, off)?;
        let avail = u64::try_from(self.b.len())
            .map_err(|_| trunc())?
            .saturating_sub(at);
        let s = self.slice(at, size.saturating_sub(off).min(avail))?;
        Ok(until(s, 0))
    }
}

fn trunc() -> Error {
    Error::msg("truncated or malformed binary".to_owned())
}

/// Adds two file offsets, returning an error on integer overflow.
fn add(a: u64, b: u64) -> Result<u64, Error> {
    a.checked_add(b).ok_or_else(trunc)
}

/// Multiplies offset and stride, returning an error on integer overflow.
fn mul(a: u64, b: u64) -> Result<u64, Error> {
    a.checked_mul(b).ok_or_else(trunc)
}

/// Slices a byte buffer up to the first delimiter byte and decodes it as UTF-8.
fn until(s: &[u8], stop: u8) -> String {
    String::from_utf8_lossy(s.split(|c| *c == stop).next().unwrap_or_default()).into_owned()
}

/// Strips the platform-specific leading C underscore prefix from Mach-O symbol names.
fn strip_underscore(name: String) -> String {
    name.strip_prefix('_').map_or(name.clone(), str::to_owned)
}

/// Parses a compiled binary or shared library, extracting symbol tables and ABI linkage facts.
/// For universal (fat) Mach-O binaries, selects the slice matching `arch` (or host architecture).
pub(crate) fn parse(b: &[u8], arch: Option<&str>) -> Result<Binary, Error> {
    match b.get(..4) {
        Some([0xcf, 0xfa, 0xed, 0xfe]) => macho(R {
            b,
            le: true,
            wide: true,
        }),
        Some([0xca, 0xfe, 0xba, 0xbe]) => fat(b, arch),
        Some([0x7f, b'E', b'L', b'F']) => elf(b),
        _ => Err(Error::msg(
            "not a 64-bit Mach-O, universal or ELF file".to_owned(),
        )),
    }
}

/// Resolves the requested architecture slice from a universal (fat) Mach-O binary header.
fn fat(b: &[u8], arch: Option<&str>) -> Result<Binary, Error> {
    let r = R {
        b,
        le: false,
        wide: false,
    };
    let want: u32 = match arch.unwrap_or(std::env::consts::ARCH) {
        "aarch64" | "arm64" => 0x0100_000c,
        "x86_64" => 0x0100_0007,
        other => return Err(Error::msg(format!("unknown architecture {other}"))),
    };
    for i in 0..r.w32(4, 0)? {
        let at = add(8, mul(i, 20)?)?;
        if r.u32(at, 0)? == want {
            let off = r.w32(at, 8)?;
            let size = r.w32(at, 12)?;
            return parse(r.slice(off, size)?, arch);
        }
    }
    Err(Error::msg(
        "the universal file has no slice for the architecture".to_owned(),
    ))
}

/// Formats a packed 32-bit Mach-O version integer into a canonical `major.minor.patch` string.
fn macho_version(v: u32) -> String {
    format!("{}.{}.{}", v >> 16, (v >> 8) & 0xff, v & 0xff)
}

impl Binary {
    /// Initializes an empty `Binary` metadata descriptor with the given container attributes.
    fn new(format: Format, shared: bool, is64: bool, twolevel: Option<bool>) -> Self {
        Self {
            format,
            shared,
            symbols: BTreeMap::new(),
            undefined: BTreeSet::new(),
            needed: Vec::new(),
            soname: None,
            dylib_versions: None,
            trie: None,
            minos: None,
            is64,
            twolevel,
        }
    }
}

/// Metadata descriptor for a Mach-O section within a segment command.
struct Section {
    addr: u64,
    size: u64,
    offset: u64,
    align: u64,
    code: bool,
    writable: bool,
}

impl Section {
    /// Computes the terminal address of the section in virtual memory.
    fn end(&self) -> u64 {
        self.addr.saturating_add(self.size)
    }
}

/// Parses a 64-bit Mach-O binary, decoding load commands, sections, dynamic dependencies, and
/// symbol tables.
fn macho(r: R<'_>) -> Result<Binary, Error> {
    const LC_SEGMENT_64: u32 = 0x19;
    const LC_SYMTAB: u32 = 0x2;
    const LC_ID_DYLIB: u32 = 0xd;
    const LC_LOAD_DYLIB: u32 = 0xc;
    const LC_LOAD_WEAK_DYLIB: u32 = 0x8000_0018;
    const LC_REEXPORT_DYLIB: u32 = 0x8000_001f;
    const LC_DYLD_INFO: u32 = 0x22;
    const LC_DYLD_INFO_ONLY: u32 = 0x8000_0022;
    const LC_DYLD_EXPORTS_TRIE: u32 = 0x8000_0033;
    const LC_BUILD_VERSION: u32 = 0x32;
    const MH_DYLIB: u32 = 6;

    const MH_TWOLEVEL: u32 = 0x80;
    let filetype = r.u32(12, 0)?;
    let ncmds = r.u32(16, 0)?;
    let hflags = r.u32(24, 0)?;
    let mut sections: Vec<Section> = Vec::new();
    let mut symtab = None;
    let mut trie_at = None;
    let mut bin = Binary::new(
        Format::MachO,
        filetype == MH_DYLIB,
        true,
        Some(hflags & MH_TWOLEVEL != 0),
    );
    let mut at: u64 = 32;
    for _ in 0..ncmds {
        let cmd = r.u32(at, 0)?;
        let size = r.w32(at, 4)?;
        match cmd {
            LC_SEGMENT_64 => macho_segment(r, at, &mut sections)?,
            LC_SYMTAB => {
                symtab = Some([
                    r.w32(at, 8)?,
                    r.w32(at, 12)?,
                    r.w32(at, 16)?,
                    r.w32(at, 20)?,
                ]);
            }
            LC_ID_DYLIB => {
                bin.soname = Some(dylib_name(r, at, size)?);
                bin.dylib_versions =
                    Some((macho_version(r.u32(at, 16)?), macho_version(r.u32(at, 20)?)));
            }
            LC_LOAD_DYLIB | LC_LOAD_WEAK_DYLIB | LC_REEXPORT_DYLIB => {
                bin.needed.push(dylib_name(r, at, size)?);
            }
            LC_DYLD_INFO | LC_DYLD_INFO_ONLY => trie_at = Some((r.w32(at, 40)?, r.w32(at, 44)?)),
            LC_DYLD_EXPORTS_TRIE => trie_at = Some((r.w32(at, 8)?, r.w32(at, 12)?)),
            LC_BUILD_VERSION => bin.minos = Some(macho_version(r.u32(at, 12)?)),
            _ => {}
        }
        at = add(at, size)?;
    }
    if let Some(tab) = symtab {
        macho_symbols(r, &sections, tab, &mut bin)?;
    }
    if let Some((off, size)) = trie_at.filter(|(_, size)| *size > 0) {
        bin.trie = Some(export_trie(r.slice(off, size)?)?);
    }
    Ok(bin)
}

/// Decodes section headers from an `LC_SEGMENT_64` load command at offset `at`.
fn macho_segment(r: R<'_>, at: u64, sections: &mut Vec<Section>) -> Result<(), Error> {
    let initprot = r.u32(at, 60)?;
    let nsects = r.w32(at, 64)?;
    let flags = r.u32(at, 68)?;
    // `SG_READ_ONLY` (0x10) marks segments such as `__DATA_CONST` that dyld maps read-only
    // after relocation despite `initprot` requesting write access.
    let writable = initprot & 2 != 0 && flags & 0x10 == 0;
    for i in 0..nsects {
        let s = add(add(at, 72)?, mul(i, 80)?)?;
        let sflags = r.u32(s, 64)?;
        let align_log = r.u32(s, 52)?;
        sections.push(Section {
            addr: r.u64(s, 32)?,
            size: r.u64(s, 40)?,
            offset: r.w32(s, 48)?,
            align: 1_u64.checked_shl(align_log).unwrap_or(0),
            code: sflags & 0x8000_0400 != 0,
            writable,
        });
    }
    Ok(())
}

/// Extracts the dylib install name or dependency path from a Mach-O dylib load command.
fn dylib_name(r: R<'_>, at: u64, size: u64) -> Result<String, Error> {
    r.cstr(at, size, r.w32(at, 8)?)
}

/// Computes the byte span from `addr` to the next symbol in the same section or the end of the
/// enclosing section.
fn macho_span(sections: &[Section], addrs: &[u64], addr: u64) -> Option<u64> {
    let sec = sections.iter().find(|s| addr >= s.addr && addr < s.end())?;
    let end = sec.end();
    let next = addrs
        .iter()
        .copied()
        .find(|a| *a > addr && *a < end)
        .unwrap_or(end);
    Some(next.saturating_sub(addr))
}

/// Parses the Mach-O symbol and string tables at `[symoff, nsyms, stroff, strsize]`, recording
/// defined symbols, section spans, and undefined references.
fn macho_symbols(
    r: R<'_>,
    sections: &[Section],
    tab: [u64; 4],
    bin: &mut Binary,
) -> Result<(), Error> {
    const N_STAB: u8 = 0xe0;
    const N_PEXT: u8 = 0x10;
    const N_TYPE: u8 = 0x0e;
    const N_EXT: u8 = 0x01;
    const N_UNDF: u8 = 0x0;
    const N_SECT: u8 = 0xe;
    const N_WEAK_DEF: u16 = 0x80;
    let [symoff, nsyms, stroff, strsize] = tab;
    // Collect all section symbol addresses so `macho_span` can bound each symbol by its
    // successor.
    let mut addrs: Vec<u64> = Vec::new();
    let mut defs = Vec::new();
    for i in 0..nsyms {
        let e = add(symoff, mul(i, 16)?)?;
        let strx = r.w32(e, 0)?;
        let ntype = r.u8(e, 4)?;
        let nsect = r.u8(e, 5)?;
        let ndesc = r.u16(e, 6)?;
        let value = r.u64(e, 8)?;
        if ntype & N_STAB != 0 {
            continue;
        }
        let name = strip_underscore(r.cstr(stroff, strsize, strx)?);
        let ty = ntype & N_TYPE;
        if ty == N_SECT {
            addrs.push(value);
        }
        if ntype & N_EXT == 0 || ntype & N_PEXT != 0 {
            continue;
        }
        let common = ty == N_UNDF && value != 0 && !bin.shared;
        if ty == N_UNDF && !common {
            bin.undefined.insert(name);
            continue;
        }
        let sec = usize::from(nsect)
            .checked_sub(1)
            .and_then(|k| sections.get(k))
            .filter(|_| ty == N_SECT);
        // Common symbols (`N_UNDF | N_EXT` with non-zero `n_value` in `.o` files) represent
        // tentative writable data definitions.
        let (kind, writable) = match sec {
            _ if common => (SymKind::Data, true),
            Some(s) if s.code => (SymKind::Func, s.writable),
            Some(s) => (SymKind::Data, s.writable),
            None => (SymKind::Other, false),
        };
        defs.push(Symbol {
            name,
            kind,
            writable,
            size: None,
            span: if common {
                Some(value)
            } else {
                (ty != N_SECT).then_some(0)
            },
            addr: value,
            weak: !common && ndesc & N_WEAK_DEF != 0,
            visibility: 0,
            section_align: sec.map_or(1, |s| s.align),
            file_off: sec
                .filter(|s| !bin.shared && s.offset != 0)
                .map(|s| s.offset.saturating_add(value.saturating_sub(s.addr))),
        });
    }
    addrs.sort_unstable();
    addrs.dedup();
    for mut s in defs {
        s.span = s.span.or_else(|| macho_span(sections, &addrs, s.addr));
        bin.symbols.insert(s.name.clone(), s);
    }
    Ok(())
}

/// Decodes an unsigned LEB128 integer from `b` at `*pos` and advances `*pos`.
fn uleb(b: &[u8], pos: &mut usize) -> Result<u64, Error> {
    let mut v: u64 = 0;
    let mut shift = 0_u32;
    loop {
        let byte = *b.get(*pos).ok_or_else(trunc)?;
        *pos = pos.checked_add(1).ok_or_else(trunc)?;
        if shift >= 64 {
            return Err(trunc());
        }
        v |= u64::from(byte & 0x7f) << shift;
        if byte & 0x80 == 0 {
            return Ok(v);
        }
        shift = shift.saturating_add(7);
    }
}

/// Walks a Mach-O dyld export trie and returns the exported symbol names (without leading `_`).
fn export_trie(t: &[u8]) -> Result<BTreeSet<String>, Error> {
    let mut out = BTreeSet::new();
    let mut stack: Vec<(usize, Vec<u8>)> = vec![(0, Vec::new())];
    let mut visited = BTreeSet::new();
    while let Some((node, prefix)) = stack.pop() {
        if !visited.insert(node) {
            return Err(Error::msg("export trie has a cycle".to_owned()));
        }
        let mut pos = node;
        let term = usize::try_from(uleb(t, &mut pos)?).map_err(|_| trunc())?;
        if term > 0 {
            out.insert(strip_underscore(
                String::from_utf8_lossy(&prefix).into_owned(),
            ));
        }
        pos = pos.checked_add(term).ok_or_else(trunc)?;
        let n = *t.get(pos).ok_or_else(trunc)?;
        pos = pos.checked_add(1).ok_or_else(trunc)?;
        for _ in 0..n {
            let rest = t.get(pos..).ok_or_else(trunc)?;
            let len = rest.iter().position(|c| *c == 0).ok_or_else(trunc)?;
            let mut edge = prefix.clone();
            edge.extend_from_slice(rest.get(..len).ok_or_else(trunc)?);
            pos = pos
                .checked_add(len)
                .and_then(|p| p.checked_add(1))
                .ok_or_else(trunc)?;
            let child = usize::try_from(uleb(t, &mut pos)?).map_err(|_| trunc())?;
            stack.push((child, edge));
        }
    }
    Ok(out)
}

/// Decoded ELF section header.
struct Shdr {
    ty: u32,
    flags: u64,
    offset: u64,
    size: u64,
    link: u32,
    align: u64,
    entsize: u64,
}

impl Shdr {
    /// Returns the section header referenced by `sh_link` (e.g. the associated string table).
    fn linked<'s>(&self, shdrs: &'s [Shdr]) -> Result<&'s Shdr, Error> {
        shdrs
            .get(usize::try_from(self.link).map_err(|_| trunc())?)
            .ok_or_else(trunc)
    }
}

/// Parses an ELF shared object (`ET_DYN`) or relocatable object (`ET_REL`).
fn elf(b: &[u8]) -> Result<Binary, Error> {
    const ET_REL: u16 = 1;
    const ET_DYN: u16 = 3;
    const SHT_SYMTAB: u32 = 2;
    const SHT_DYNSYM: u32 = 11;
    const SHT_DYNAMIC: u32 = 6;
    let wide = match b.get(4) {
        Some(1) => false,
        Some(2) => true,
        _ => return Err(Error::msg("bad ELF class".to_owned())),
    };
    let r = R {
        b,
        le: b.get(5) == Some(&1),
        wide,
    };
    let etype = r.u16(16, 0)?;
    // ELF header field offsets for `(e_shoff, e_shentsize, e_shnum)` in 64-bit vs. 32-bit ELF.
    let [shoff, shentsize, shnum] = if wide {
        [0x28, 0x3a, 0x3c]
    } else {
        [0x20, 0x2e, 0x30]
    };
    let shdrs = elf_shdrs(r, r.word(0, shoff)?, r.u16(0, shentsize)?, r.u16(0, shnum)?)?;
    let shared = etype == ET_DYN;
    if !shared && etype != ET_REL {
        return Err(Error::msg(format!(
            "ELF type {etype} is not a shared library or an object"
        )));
    }
    let mut bin = Binary::new(Format::Elf, shared, wide, None);
    let want = if shared { SHT_DYNSYM } else { SHT_SYMTAB };
    for sh in shdrs.iter().filter(|s| s.ty == want) {
        elf_symbols(r, &shdrs, sh, &mut bin)?;
    }
    for sh in shdrs.iter().filter(|s| s.ty == SHT_DYNAMIC) {
        elf_dynamic(r, &shdrs, sh, &mut bin)?;
    }
    Ok(bin)
}

/// Reads all ELF section headers starting at `shoff`.
fn elf_shdrs(r: R<'_>, shoff: u64, shentsize: u16, shnum: u16) -> Result<Vec<Shdr>, Error> {
    // Section header field offsets across 64-bit and 32-bit ELF classes.
    let [flags, offset, size, link, align, entsize] = if r.wide {
        [8, 24, 32, 40, 48, 56]
    } else {
        [8, 16, 20, 24, 32, 36]
    };
    let mut shdrs = Vec::new();
    for i in 0..u64::from(shnum) {
        let s = add(shoff, mul(i, u64::from(shentsize))?)?;
        shdrs.push(Shdr {
            ty: r.u32(s, 4)?,
            flags: r.word(s, flags)?,
            offset: r.word(s, offset)?,
            size: r.word(s, size)?,
            link: r.u32(s, link)?,
            align: r.word(s, align)?,
            entsize: r.word(s, entsize)?,
        });
    }
    Ok(shdrs)
}

/// Decodes an ELF symbol table section (`.symtab` or `.dynsym`).
fn elf_symbols(r: R<'_>, shdrs: &[Shdr], sh: &Shdr, bin: &mut Binary) -> Result<(), Error> {
    let shared = bin.shared;
    let strtab = sh.linked(shdrs)?;
    // Symbol table entry field offsets across 64-bit and 32-bit ELF classes.
    let [ent, info_at, other_at, shndx_at, value_at, size_at] = if r.wide {
        [24, 4, 5, 6, 8, 16]
    } else {
        [16, 12, 13, 14, 4, 8]
    };
    let ent = if sh.entsize == 0 { ent } else { sh.entsize };
    let n = sh.size.checked_div(ent).unwrap_or(0);
    for i in 1..n {
        let e = add(sh.offset, mul(i, ent)?)?;
        let name = r.w32(e, 0)?;
        let info = r.u8(e, info_at)?;
        let other = r.u8(e, other_at)?;
        let shndx = r.u16(e, shndx_at)?;
        let value = r.word(e, value_at)?;
        let size = r.word(e, size_at)?;
        let bind = info >> 4;
        if bind == 0 {
            continue;
        }
        let name = r.cstr(strtab.offset, strtab.size, name)?;
        if name.is_empty() {
            continue;
        }
        let vis = other & 3;
        if shndx == 0 {
            bin.undefined.insert(name);
            continue;
        }
        if shared && (vis == 1 || vis == 2) {
            continue;
        }
        let kind = match info & 0xf {
            2 | 10 => SymKind::Func,
            1 | 6 => SymKind::Data,
            _ if shndx == 0xfff2 => SymKind::Data,
            _ => SymKind::Other,
        };
        let sec = shdrs.get(usize::from(shndx)).filter(|_| shndx < 0xff00);
        let writable = sec.is_some_and(|s| s.flags & 1 != 0);
        // `SHT_NOBITS` (8) sections such as `.bss` carry no bytes in the file image.
        let file_off = sec
            .filter(|s| !shared && s.ty != 8)
            .map(|s| s.offset.saturating_add(value));
        bin.symbols.insert(
            name.clone(),
            Symbol {
                name,
                kind,
                writable: writable || shndx == 0xfff2,
                size: Some(size),
                span: None,
                addr: value,
                weak: bind == 2,
                visibility: vis,
                section_align: sec.map_or(1, |s| s.align.max(1)),
                file_off,
            },
        );
    }
    Ok(())
}

/// Parses ELF `.dynamic` entries to extract `DT_NEEDED` dependencies and `DT_SONAME`.
fn elf_dynamic(r: R<'_>, shdrs: &[Shdr], sh: &Shdr, bin: &mut Binary) -> Result<(), Error> {
    let strtab = sh.linked(shdrs)?;
    let (ent, val_at) = if r.wide { (16, 8) } else { (8, 4) };
    for i in 0..sh.size.checked_div(ent).unwrap_or(0) {
        let e = add(sh.offset, mul(i, ent)?)?;
        let tag = r.word(e, 0)?;
        let val = r.word(e, val_at)?;
        match tag {
            0 => break,
            1 => bin.needed.push(r.cstr(strtab.offset, strtab.size, val)?),
            14 => bin.soname = Some(r.cstr(strtab.offset, strtab.size, val)?),
            _ => {}
        }
    }
    Ok(())
}

/// Returns `b[at..at + len]` or a truncation error.
fn take(b: &[u8], at: usize, len: usize) -> Result<&[u8], Error> {
    at.checked_add(len)
        .and_then(|end| b.get(at..end))
        .ok_or_else(trunc)
}

/// Parses an `ar` static archive and each of its member object files, supporting both BSD
/// (`#1/`) and GNU (`//`) extended member filenames.
pub(crate) fn parse_archive(b: &[u8], arch: Option<&str>) -> Result<Vec<(String, Binary)>, Error> {
    let body = b
        .strip_prefix(b"!<arch>\n")
        .ok_or_else(|| Error::msg("not an ar archive".to_owned()))?;
    let mut out = Vec::new();
    let mut long_names: &[u8] = &[];
    let mut pos = 0_usize;
    while pos < body.len() {
        let hdr = take(body, pos, 60)?;
        let field = |a: usize, z: usize| {
            String::from_utf8_lossy(hdr.get(a..z).unwrap_or_default())
                .trim()
                .to_owned()
        };
        let raw_name = field(0, 16);
        let size: usize = field(48, 58).parse().map_err(|_| trunc())?;
        let data_at = pos.checked_add(60).ok_or_else(trunc)?;
        let data = take(body, data_at, size)?;
        pos = data_at.checked_add(size).ok_or_else(trunc)?;
        // Archive member headers are aligned to 2-byte boundaries.
        pos = pos.saturating_add(pos % 2);
        let (name, content) = if let Some(n) = raw_name.strip_prefix("#1/") {
            // BSD extended filenames prepend `n` filename bytes to the member payload.
            let n: usize = n.parse().map_err(|_| trunc())?;
            let (nm, content) = data.split_at_checked(n).ok_or_else(trunc)?;
            (until(nm, 0), content)
        } else if raw_name == "//" {
            long_names = data;
            continue;
        } else if let Some(idx) = raw_name
            .strip_prefix('/')
            .and_then(|s| s.parse::<usize>().ok())
        {
            // GNU extended filenames index into the `//` string table member.
            let rest = long_names.get(idx..).ok_or_else(trunc)?;
            (until(rest, b'\n').trim_end_matches('/').to_owned(), data)
        } else {
            (raw_name.trim_end_matches('/').to_owned(), data)
        };
        if name.is_empty() || name.starts_with("__.SYMDEF") || name == "/SYM64" {
            continue;
        }
        let obj =
            parse(content, arch).map_err(|e| Error::msg(format!("archive member {name}: {e}")))?;
        out.push((name, obj));
    }
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn put(v: &mut [u8], at: usize, bytes: &[u8]) {
        if let Some(d) = v.get_mut(at..at.saturating_add(bytes.len())) {
            d.copy_from_slice(bytes);
        }
    }

    /// Synthesizes a minimal 64-bit little-endian ELF shared object fixture with `.dynsym`,
    /// `.dynstr`, and writable data sections.
    fn tiny_elf() -> Vec<u8> {
        let mut v = vec![0_u8; 0x400];
        put(&mut v, 0, &[0x7f, b'E', b'L', b'F', 2, 1, 1]);
        put(&mut v, 16, &3_u16.to_le_bytes());
        put(&mut v, 0x28, &0x100_u64.to_le_bytes());
        put(&mut v, 0x3a, &64_u16.to_le_bytes());
        put(&mut v, 0x3c, &4_u16.to_le_bytes());
        // Construct section headers: section 1 contains `.dynsym` with 4 symbol entries;
        // section 2 contains `.dynstr`; section 3 provides a writable data section.
        let sh = |v: &mut Vec<u8>,
                  i: usize,
                  ty: u32,
                  flags: u64,
                  off: u64,
                  size: u64,
                  link: u32,
                  ent: u64| {
            let s = 0x100_usize.saturating_add(i.saturating_mul(64));
            put(v, s.saturating_add(4), &ty.to_le_bytes());
            put(v, s.saturating_add(8), &flags.to_le_bytes());
            put(v, s.saturating_add(24), &off.to_le_bytes());
            put(v, s.saturating_add(32), &size.to_le_bytes());
            put(v, s.saturating_add(40), &link.to_le_bytes());
            put(v, s.saturating_add(56), &ent.to_le_bytes());
        };
        sh(&mut v, 1, 11, 2, 0x300, 96, 2, 24);
        sh(&mut v, 2, 3, 2, 0x380, 32, 0, 0);
        sh(&mut v, 3, 1, 3, 0, 0, 0, 0);
        put(&mut v, 0x380, b"\0zoo_get\0logLevel\0hidden_fn\0");
        let sym =
            |v: &mut Vec<u8>, i: usize, name: u32, info: u8, other: u8, shndx: u16, size: u64| {
                let e = 0x300_usize.saturating_add(i.saturating_mul(24));
                put(v, e, &name.to_le_bytes());
                put(v, e.saturating_add(4), &[info, other]);
                put(v, e.saturating_add(6), &shndx.to_le_bytes());
                put(v, e.saturating_add(16), &size.to_le_bytes());
            };
        sym(&mut v, 1, 1, 0x12, 0, 3, 40);
        sym(&mut v, 2, 9, 0x11, 0, 3, 4);
        sym(&mut v, 3, 18, 0x12, 2, 3, 8);
        v
    }

    #[test]
    fn elf_dynsym() {
        let b = parse(&tiny_elf(), None);
        let b = b.ok();
        let names: Vec<String> = b.iter().flat_map(|b| b.symbols.keys().cloned()).collect();
        assert_eq!(names, vec!["logLevel".to_owned(), "zoo_get".to_owned()]);
        let ll = b.as_ref().and_then(|b| b.symbols.get("logLevel"));
        assert_eq!(
            ll.map(|s| (s.kind, s.size, s.writable)),
            Some((SymKind::Data, Some(4), true))
        );
    }

    #[test]
    fn trie_walk() {
        // Fixture representing a prefix trie: root node connects via edge "_a" to node 6,
        // which marks a terminal export "a" and extends via edge "b" to node 13 for "ab".
        let t: Vec<u8> = vec![0, 1, b'_', b'a', 0, 6, 2, 0, 1, 1, b'b', 0, 13, 2, 0, 2, 0];
        let names = export_trie(&t).ok();
        let want: BTreeSet<String> = ["a".to_owned(), "ab".to_owned()].into_iter().collect();
        assert_eq!(names, Some(want));
    }

    #[test]
    fn rejects_garbage() {
        assert!(parse(b"nope", None).is_err());
        assert!(parse(&[0xcf, 0xfa, 0xed, 0xfe, 0, 0], None).is_err());
        assert!(parse_archive(b"!<arch>\nshort", None).is_err());
    }
}

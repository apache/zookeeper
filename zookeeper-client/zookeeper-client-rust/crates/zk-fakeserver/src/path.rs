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

//! Path validation and hierarchy traversal matching ZooKeeper semantics.

/// Validates a znode path against the rules enforced by Java's `PathUtils.validatePath`.
///
/// Paths must begin with `/`, must not end with `/` (except the root `/`), must not contain empty
/// segments (`//`) or relative segments (`.` or `..`), and must contain only Basic Multilingual
/// Plane code points (`<= U+FFFF`), excluding null bytes, control characters, surrogates, and
/// private-use code points.
pub(crate) fn is_valid(path: &str) -> bool {
    let chars: Vec<u32> = path.chars().map(u32::from).collect();
    let Some(&first) = chars.first() else {
        return false;
    };
    if first != u32::from('/') {
        return false;
    }
    if chars.len() == 1 {
        return true;
    }
    if chars.last() == Some(&u32::from('/')) {
        return false;
    }
    let slash = u32::from('/');
    let dot = u32::from('.');
    let get = |i: Option<usize>| i.and_then(|i| chars.get(i)).copied();
    for (i, &c) in chars.iter().enumerate().skip(1) {
        let last = get(i.checked_sub(1));
        let next = get(i.checked_add(1));
        let next_ends = next.is_none() || next == Some(slash);
        if c == 0 || c > 0xFFFF {
            return false;
        }
        if c == slash && last == Some(slash) {
            return false;
        }
        if c == dot && last == Some(dot) && get(i.checked_sub(2)) == Some(slash) && next_ends {
            return false;
        }
        if c == dot && last == Some(slash) && next_ends {
            return false;
        }
        if (c > 0 && c < 0x1f)
            || (c > 0x7f && c < 0x9f)
            || (c > 0xd800 && c < 0xf8ff)
            || (c > 0xfff0 && c < 0xffff)
        {
            return false;
        }
    }
    true
}

/// Returns the parent path by stripping the final path segment.
///
/// Returns `Some("/")` for top-level znodes (such as `"/a"`), `Some(parent)` for nested paths
/// (such as `"/a"` for `"/a/b"`), or `None` if `path` contains no `/`.
pub(crate) fn parent(path: &str) -> Option<&str> {
    let at = path.rfind('/')?;
    if at == 0 { Some("/") } else { path.get(..at) }
}

/// Returns the final path segment following the last `/`.
pub(crate) fn name(path: &str) -> &str {
    match path.rfind('/') {
        Some(at) => path.get(at.saturating_add(1)..).unwrap_or(""),
        None => path,
    }
}

/// Returns `path` followed by each ancestor path up to `/`.
pub(crate) fn self_and_ancestors(path: &str) -> Vec<&str> {
    let mut out = vec![path];
    let mut cur = path;
    while let Some(p) = parent(cur) {
        if p == cur {
            break;
        }
        out.push(p);
        cur = p;
    }
    out
}

#[cfg(test)]
#[allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects
)]
mod tests {
    use super::{is_valid, name, parent, self_and_ancestors};

    #[test]
    fn java_path_rules() {
        for ok in ["/", "/a", "/a/b", "/a.b", "/..a", "/a/.b", "/\u{1f}"] {
            assert!(is_valid(ok), "{ok:?}");
        }
        for bad in [
            "",
            "a",
            "/a/",
            "//",
            "/a//b",
            "/.",
            "/..",
            "/a/./b",
            "/a/../b",
            "/a\0",
            "/\u{1}",
            "/\u{80}",
            "/\u{e000}",
            "/\u{1F600}",
            "/\u{fff5}",
        ] {
            assert!(!is_valid(bad), "{bad:?}");
        }
    }

    #[test]
    fn helpers() {
        assert_eq!(parent("/a"), Some("/"));
        assert_eq!(parent("/a/b"), Some("/a"));
        assert_eq!(parent("a"), None);
        assert_eq!(name("/a/b"), "b");
        assert_eq!(self_and_ancestors("/a/b"), vec!["/a/b", "/a", "/"]);
        assert_eq!(self_and_ancestors("/"), vec!["/"]);
    }
}

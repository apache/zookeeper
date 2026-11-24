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

//! Common utility primitives supporting task orchestration, file I/O, error handling, and
//! structured TSV parsing across xtask subcommands.

use std::io::Write as _;
use std::path::{Path, PathBuf};

/// Represents operational errors encountered during xtask execution.
#[derive(Debug)]
pub(crate) enum Error {
    /// Command-line invocation or argument parsing error, exiting with status code 2.
    Usage(String),
    /// Task execution or verification failure, exiting with status code 1.
    Failed(String),
}

impl Error {
    /// Constructs a task execution failure from a displayable message.
    pub(crate) fn msg(s: impl Into<String>) -> Self {
        Self::Failed(s.into())
    }

    /// Constructs a command-line usage error from an explanatory message.
    pub(crate) fn usage(s: impl Into<String>) -> Self {
        Self::Usage(s.into())
    }

    /// Constructs an I/O failure associating an error with the affected filesystem path.
    pub(crate) fn io(path: &Path, e: &std::io::Error) -> Self {
        Self::Failed(format!("{}: {e}", path.display()))
    }
}

impl std::fmt::Display for Error {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Usage(s) | Self::Failed(s) => f.write_str(s),
        }
    }
}

/// Standard Apache Software Foundation license header formatted for script and config files.
const LICENSE: &str = "\
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
\"License\"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an \"AS IS\" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
";

/// Formats the Apache Software Foundation license header using `#` line comment syntax.
pub(crate) fn tsv_license() -> String {
    comment_license("#")
}

/// Prefixes each line of the Apache license header with the specified comment delimiter.
pub(crate) fn comment_license(prefix: &str) -> String {
    let mut s = String::new();
    for l in LICENSE.lines() {
        if l.is_empty() {
            s.push_str(prefix);
        } else {
            s.push_str(prefix);
            s.push(' ');
            s.push_str(l);
        }
        s.push('\n');
    }
    s.push('\n');
    s
}

/// Reads an entire UTF-8 text file from disk into a string, mapping I/O errors.
pub(crate) fn read(path: &Path) -> Result<String, Error> {
    std::fs::read_to_string(path).map_err(|e| Error::io(path, &e))
}

/// Reads an entire binary file into an owned byte vector, mapping I/O errors.
pub(crate) fn read_bytes(path: &Path) -> Result<Vec<u8>, Error> {
    std::fs::read(path).map_err(|e| Error::io(path, &e))
}

/// Writes text content to the specified path, creating any missing parent directories.
pub(crate) fn write(path: &Path, text: &str) -> Result<(), Error> {
    if let Some(dir) = path.parent() {
        std::fs::create_dir_all(dir).map_err(|e| Error::io(dir, &e))?;
    }
    std::fs::write(path, text).map_err(|e| Error::io(path, &e))
}

/// Formats a filesystem path for diagnostic messages and user-facing output.
pub(crate) fn rel(path: &Path) -> String {
    path.display().to_string()
}

/// Emits formatted text directly to standard output and flushes the buffer.
pub(crate) fn out(s: &str) {
    let mut o = std::io::stdout().lock();
    let _ = o.write_all(s.as_bytes());
    let _ = o.flush();
}

/// Emits diagnostic text directly to standard error.
pub(crate) fn err(s: &str) {
    let mut o = std::io::stderr().lock();
    let _ = o.write_all(s.as_bytes());
}

/// Extracts the required argument parameter following a command-line option flag.
pub(crate) fn value<'a>(
    it: &mut impl Iterator<Item = &'a String>,
    opt: &str,
) -> Result<&'a str, Error> {
    it.next()
        .map(String::as_str)
        .ok_or_else(|| Error::usage(format!("{opt} needs a value")))
}

/// Resolves the root directory of the Cargo workspace containing this crate.
pub(crate) fn workspace_dir() -> PathBuf {
    let manifest = Path::new(env!("CARGO_MANIFEST_DIR"));
    manifest
        .parent()
        .map_or_else(|| manifest.to_path_buf(), Path::to_path_buf)
}

/// Resolves the filesystem path to the legacy C client tree (`zookeeper-client-c`),
/// which contains the original reference headers, source implementations, and tests.
pub(crate) fn c_client_dir() -> PathBuf {
    workspace_dir().join("../zookeeper-client-c")
}

/// Resolves the filesystem path to the C compatibility tree (`zookeeper-client-rust/c`),
/// hosting the C shim interface, build definitions, ABI golden baselines, and test harnesses.
pub(crate) fn rust_c_dir() -> PathBuf {
    workspace_dir().join("c")
}

/// Retrieves the Git commit hash of the legacy C source tree, appending `+dirty` if
/// any library sources contain uncommitted modifications.
pub(crate) fn git_head(c_dir: &Path) -> String {
    let run = |args: &[&str]| {
        std::process::Command::new("git")
            .arg("-C")
            .arg(c_dir)
            .args(args)
            .output()
            .ok()
            .filter(|o| o.status.success())
            .map(|o| String::from_utf8_lossy(&o.stdout).trim().to_owned())
    };
    let Some(head) = run(&["rev-parse", "HEAD"]) else {
        return "unknown".to_owned();
    };
    let dirty = run(&["status", "--porcelain", "--", "src", "include", "generated"])
        .is_some_and(|s| !s.is_empty());
    if dirty { format!("{head}+dirty") } else { head }
}

/// Parses tab-separated values (TSV) into row entries, skipping empty lines and comments (`#`).
/// Validates that the initial non-comment line exactly matches the expected column header.
pub(crate) fn tsv_rows<'a>(
    text: &'a str,
    header: &str,
) -> Result<Vec<(usize, Vec<&'a str>)>, Error> {
    let mut rows = Vec::new();
    let mut seen_header = false;
    for (i, line) in text.lines().enumerate() {
        let n = i.saturating_add(1);
        if line.is_empty() || line.starts_with('#') {
            continue;
        }
        if !seen_header {
            if line != header {
                return Err(Error::msg(format!(
                    "line {n}: expected the column header `{header}`"
                )));
            }
            seen_header = true;
            continue;
        }
        rows.push((n, line.split('\t').collect()));
    }
    if seen_header {
        Ok(rows)
    } else {
        Err(Error::msg("no column header".to_owned()))
    }
}

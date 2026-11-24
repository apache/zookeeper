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

//! On-demand generation and content addressed caching of TLS test fixtures.
//!
//! To avoid storing static private keys or cryptographic certificates within version
//! control, test fixtures are generated dynamically at test execution time.
//! [`tls_dir`] compiles the baseline TLS test materials via `gen.sh` and `trust/gen.sh`,
//! while [`tls_corpus_dir`] generates the comprehensive trust-decision verification
//! corpus via `gencorpus.sh`.
//!
//! Each fixture set is isolated within a content addressed cache directory keyed by
//! an FNV-1a hash of its deterministic build inputs: shell script contents, toolchain
//! binary paths, and the active OpenSSL version string. Modifications to source scripts
//! or updates to the OpenSSL toolchain automatically yield fresh cache partitions,
//! preventing accidental reuse of outdated or incompatible artifacts.
//!
//! Cache generation performs work inside isolated temporary directories before an
//! atomic directory rename into the finalized cache path. Concurrency across parallel
//! test runners is arbitrated via filesystem advisory locks with PID tracking and
//! stale lock recovery.
//!
//! Environment variables controlling fixture location and generation:
//!
//! - `ZK_TEST_OPENSSL`: Explicit path to a compatible OpenSSL binary.
//! - `ZK_TEST_FIXTURE_DIR`: Root cache directory for compiled fixtures.
//! - `ZK_TEST_TLS_FIXTURES`, `ZK_TEST_TLS_CORPUS`: Pre-generated fixture directories
//!   overriding local invocation.
//!
//! Directory paths specified via environment variables must be absolute.

use std::env;
use std::fmt::Write as _;
use std::fs::{self, File};
use std::io::{self, Write as _};
use std::path::{Path, PathBuf};
use std::process::{self, Command, Stdio};
use std::sync::OnceLock;
use std::thread;
use std::time::{Duration, Instant};

/// Cache key schema version tag, incremented whenever hashing semantics change.
const KEY_RULE: &str = "zk-fixtures/1";
/// Maximum duration a process will wait for a concurrent builder to finish.
const WAIT: Duration = Duration::from_secs(15 * 60);
/// Polling interval for observing lock release or directory publication.
const POLL: Duration = Duration::from_millis(250);
/// Inactivity grace period before a lock directory lacking a PID file is classified as stale.
const NO_PID_GRACE: Duration = Duration::from_secs(30);
/// Maximum tail line count retained in failure diagnostics from build logs.
const TAIL_LINES: usize = 30;
/// Minimum expected case count for a fully populated trust decision corpus.
const MIN_CASES: usize = 100;

/// Generator script paths located within the workspace tree.
const TLS_SRC: &str = "crates/zk-driver/tests/fixtures/tls";
const TLS_SCRIPTS: [&str; 2] = ["gen.sh", "trust/gen.sh"];
const CORPUS_SCRIPT: &str = "c/tests/conformance/tls/gencorpus.sh";

const OPENSSL_NEED: &str = "the TLS test fixtures need the command line tool of \
     OpenSSL 3.4 or later (3.x, the OpenSSL that the legacy C client links; not 4.x and \
     not LibreSSL). Set ZK_TEST_OPENSSL=/path/to/openssl.";
const CORPUS_HELD_BACK: &str = "the trust-decision corpus needs \
     c/tests/conformance/tls/gencorpus.sh from the held-back subtree c/tests";

/// Returns the filesystem path to the compiled TLS test fixtures directory.
///
/// The resulting directory mirrors the layout expected by `zk-driver`, containing
/// `keys/` and `trust/` subtrees alongside copies of `gen.sh` and `trust/gen.sh`.
/// On the initial invocation within a test process, missing fixtures are generated
/// and cached atomically. Subsequent calls synchronize on the cached directory path.
///
/// # Panics
///
/// Panics if a compatible OpenSSL binary is unavailable or if script execution fails.
pub fn tls_dir() -> &'static Path {
    TLS.dir()
}

/// Returns the filesystem path to the trust-decision conformance corpus directory.
///
/// The directory structure contains isolated subdirectories for individual test cases
/// alongside shared cryptographic keys in `_keys`. Initial generation compiles the
/// full test suite using OpenSSL and legacy C test generation tools, which may take
/// one to two minutes on first run.
///
/// # Panics
///
/// Panics if `gencorpus.sh` is absent from the source tree, if suitable OpenSSL/Java
/// toolchains are not located, or if corpus generation fails.
pub fn tls_corpus_dir() -> &'static Path {
    CORPUS.dir()
}

/// Target fixture dataset descriptor and its process-wide lazy initialization cell.
struct Set {
    name: &'static str,
    what: &'static str,
    ready_var: &'static str,
    /// Indicates whether this set represents the corpus requiring auxiliary toolchains.
    is_corpus: bool,
    cell: OnceLock<Result<PathBuf, String>>,
}

static TLS: Set = Set {
    name: "tls",
    what: "TLS fixtures",
    ready_var: "ZK_TEST_TLS_FIXTURES",
    is_corpus: false,
    cell: OnceLock::new(),
};

static CORPUS: Set = Set {
    name: "corpus",
    what: "trust-decision corpus",
    ready_var: "ZK_TEST_TLS_CORPUS",
    is_corpus: true,
    cell: OnceLock::new(),
};

impl Set {
    #[allow(
        clippy::panic,
        reason = "a test fixture that cannot be made stops the test"
    )]
    fn dir(&'static self) -> &'static Path {
        // Failures are memoized in the OnceLock to prevent subsequent tests from
        // repeatedly attempting and failing expensive generator invocations.
        match self.cell.get_or_init(|| locate(self)) {
            Ok(dir) => dir,
            Err(msg) => panic!("{msg}"),
        }
    }

    /// Resolves the effective target path within an initialized cache entry directory.
    fn entry(&self, dir: &Path) -> PathBuf {
        if self.is_corpus {
            dir.join("corpus")
        } else {
            dir.to_path_buf()
        }
    }
}

/// Resolved toolchain configuration and input dependencies used to compute a cache key.
struct Plan {
    set: &'static Set,
    /// Root directory of the enclosing Cargo workspace.
    rs: PathBuf,
    openssl: PathBuf,
    /// Auxiliary tools required specifically for corpus generation.
    corpus: Option<CorpusTools>,
    key: String,
    inputs: String,
}

struct CorpusTools {
    bash: PathBuf,
    c_dir: PathBuf,
}

fn locate(set: &'static Set) -> Result<PathBuf, String> {
    if let Some(dir) = env_dir(set.ready_var)? {
        return ready(set, dir);
    }
    let plan = plan(set)?;
    obtain(&cache_root()?, &plan)
}

/// Validates an externally provided fixture directory from environment variables without
/// spawning subprocesses.
fn ready(set: &Set, dir: PathBuf) -> Result<PathBuf, String> {
    check_path_text(&dir)?;
    let var = set.ready_var;
    let why = if set.is_corpus {
        let cases = count_cases(&dir);
        (cases <= MIN_CASES).then(|| {
            format!("has {cases} case directories, and a full corpus has more than {MIN_CASES}")
        })
    } else {
        (!dir.join("ca.pem").is_file()).then(|| "has no ca.pem".to_owned())
    };
    match why {
        Some(why) => Err(format!("{var}={} {why}", dir.display())),
        None => Ok(dir),
    }
}

/// Reads an environment variable representing a directory path, enforcing absolute paths.
fn env_dir(var: &str) -> Result<Option<PathBuf>, String> {
    match env::var_os(var).map(PathBuf::from) {
        Some(path) if !path.is_absolute() => Err(format!(
            "{var}={} is a relative path. Set it to an absolute path",
            path.display()
        )),
        dir => Ok(dir),
    }
}

/// Constructs a closure formatting I/O errors with their associated filesystem path context.
fn at(path: &Path) -> impl FnOnce(io::Error) -> String + '_ {
    move |e| format!("{}: {e}", path.display())
}

fn canonical(path: &Path) -> PathBuf {
    fs::canonicalize(path).unwrap_or_else(|_| path.to_path_buf())
}

/// Resolves the root directory of the Rust workspace from crate manifest metadata.
fn workspace_dir() -> Result<PathBuf, String> {
    Path::new(env!("CARGO_MANIFEST_DIR"))
        .parent()
        .and_then(Path::parent)
        .map(Path::to_path_buf)
        .ok_or_else(|| "zk-testkit is not in crates/ of the Rust workspace".to_owned())
}

/// Discovers requisite external tools, validates inputs, and computes the composite cache key.
fn plan(set: &'static Set) -> Result<Plan, String> {
    let rs = workspace_dir()?;
    let script = rs.join(CORPUS_SCRIPT);
    if set.is_corpus && !script.is_file() {
        return Err(format!(
            "{CORPUS_HELD_BACK} ({} is missing)",
            script.display()
        ));
    }
    let (openssl, version) = find_openssl()?;
    let (scripts, corpus, corpus_fields): (Vec<PathBuf>, _, Vec<Field>) = if set.is_corpus {
        let (tools, fields) = corpus_tools(&rs)?;
        (vec![script], Some(tools), fields.into())
    } else {
        (
            TLS_SCRIPTS.map(|s| rs.join(TLS_SRC).join(s)).into(),
            None,
            Vec::new(),
        )
    };
    let real = canonical(&openssl);
    let mut fields: Vec<Field> = vec![
        ("rule", KEY_RULE.to_owned(), KEY_RULE.into()),
        ("set", set.name.to_owned(), set.name.into()),
        (
            "openssl",
            format!("{} (given as {})", real.display(), openssl.display()),
            real.into_os_string().into_encoded_bytes(),
        ),
        ("version", version.clone(), version.into_bytes()),
    ];
    for script in &scripts {
        fields.push(file_field("script", script).map_err(at(script))?);
    }
    fields.extend(corpus_fields);
    let (key, inputs) = cache_key(&fields);
    Ok(Plan {
        set,
        rs,
        openssl,
        corpus,
        key,
        inputs,
    })
}

/// Discovers auxiliary dependencies required for corpus generation and captures their hash
/// attributes.
fn corpus_tools(rs: &Path) -> Result<(CorpusTools, [Field; 2]), String> {
    if on_path("xxd").is_none() {
        return Err("the trust-decision corpus needs xxd on PATH: \
             install xxd (vim-common)"
            .to_owned());
    }
    let bash = on_path("bash").ok_or("the trust-decision corpus needs bash on PATH")?;
    // The legacy gencorpus.sh script conditionally executes keytool-dependent cases;
    // verify keytool presence upfront so test coverage cannot silently degrade.
    let keytool = on_path("keytool").ok_or(
        "the trust-decision corpus needs keytool (a JDK) on PATH, because \
         gencorpus.sh runs the real ssl/gencerts.sh of the legacy C client",
    )?;
    let c_dir = env_dir("ZK_C_DIR")?.unwrap_or_else(|| rs.join("../zookeeper-client-c"));
    let gencerts = canonical(&c_dir.join("ssl/gencerts.sh"));
    let gencerts = file_field("gencerts", &gencerts).map_err(|e| {
        format!(
            "the trust-decision corpus needs {} of the legacy C client \
             (ZK_C_DIR selects its directory): {e}",
            gencerts.display()
        )
    })?;
    let keytool = canonical(&keytool);
    let keytool = (
        "keytool",
        keytool.display().to_string(),
        keytool.into_os_string().into_encoded_bytes(),
    );
    Ok((CorpusTools { bash, c_dir }, [gencerts, keytool]))
}

/// Cache key input tuple comprising an identifier label, diagnostic description, and hashed
/// payload bytes.
type Field = (&'static str, String, Vec<u8>);

/// Constructs a key field from file contents, recording file size and path in the build log.
fn file_field(label: &'static str, path: &Path) -> io::Result<Field> {
    let bytes = fs::read(path)?;
    let shown = format!("{} ({} bytes)", path.display(), bytes.len());
    Ok((label, shown, bytes))
}

/// Computes an FNV-1a 64-bit cryptographic digest over length-prefixed input fields.
fn cache_key(fields: &[Field]) -> (String, String) {
    let mut hash: u64 = 0xcbf2_9ce4_8422_2325;
    let mut inputs = String::new();
    for (label, shown, bytes) in fields {
        let len = u64::try_from(bytes.len()).unwrap_or(u64::MAX);
        for &b in len.to_le_bytes().iter().chain(bytes) {
            hash = (hash ^ u64::from(b)).wrapping_mul(0x0100_0000_01b3);
        }
        let _ = writeln!(inputs, "  {label}: {shown}");
    }
    (format!("{hash:016x}"), inputs)
}

/// Locates an acceptable OpenSSL binary by probing standard locations and PATH candidates.
fn find_openssl() -> Result<(PathBuf, String), String> {
    let candidates: Vec<PathBuf> = match env::var_os("ZK_TEST_OPENSSL") {
        Some(path) => vec![PathBuf::from(path)],
        None => [
            "/opt/homebrew/opt/openssl@3/bin/openssl",
            "/usr/local/opt/openssl@3/bin/openssl",
        ]
        .iter()
        .map(PathBuf::from)
        .chain(all_on_path("openssl"))
        .collect(),
    };
    let mut tried = String::new();
    for candidate in candidates {
        match probe_openssl(&candidate) {
            // Subprocesses run within temporary working directories, requiring absolute paths.
            Ok(version) => {
                let path = std::path::absolute(&candidate).map_err(at(&candidate))?;
                return Ok((path, version));
            }
            Err(why) => {
                let _ = writeln!(tried, "  {}: {why}", candidate.display());
            }
        }
    }
    Err(format!("{OPENSSL_NEED} Tried:\n{tried}"))
}

fn probe_openssl(candidate: &Path) -> Result<String, String> {
    if !candidate.is_file() {
        return Err("not found".to_owned());
    }
    let out = Command::new(candidate)
        .arg("version")
        .stdin(Stdio::null())
        .stderr(Stdio::null())
        .output()
        .map_err(|e| format!("does not run ({e})"))?;
    if !out.status.success() {
        return Err(format!("`version` fails ({})", out.status));
    }
    let text = String::from_utf8_lossy(&out.stdout);
    let line = text.lines().next().unwrap_or("").trim().to_owned();
    if accepts_version(&line) {
        Ok(line)
    } else {
        Err(format!("\"{line}\" is not OpenSSL 3.4 to 3.x"))
    }
}

/// Validates that OpenSSL reports version 3.4.x or newer (3.x branch).
///
/// Versions prior to 3.4 lack required CLI flags (`-not_before` and `-x509v1`),
/// while OpenSSL 4.x or LibreSSL introduce divergent certificate validation semantics.
fn accepts_version(line: &str) -> bool {
    let Some(rest) = line.strip_prefix("OpenSSL ") else {
        return false;
    };
    let mut parts = rest.split_whitespace().next().unwrap_or("").split('.');
    let major = parts.next();
    let minor = parts.next().and_then(|m| m.parse::<u32>().ok());
    major == Some("3") && minor.is_some_and(|m| m >= 4)
}

fn all_on_path(name: &str) -> Vec<PathBuf> {
    env::var_os("PATH")
        .map(|path| {
            env::split_paths(&path)
                .map(|dir| dir.join(name))
                .filter(|file| file.is_file())
                .collect()
        })
        .unwrap_or_default()
}

fn on_path(name: &str) -> Option<PathBuf> {
    all_on_path(name).into_iter().next()
}

/// Locates the persistent cache root directory, defaulting adjacent to the build artifacts.
fn cache_root() -> Result<PathBuf, String> {
    let root = env_dir("ZK_TEST_FIXTURE_DIR")?.unwrap_or_else(|| {
        env::current_exe()
            .ok()
            .and_then(|exe| {
                exe.ancestors()
                    .skip(1)
                    .find(|dir| dir.join("CACHEDIR.TAG").is_file())
                    .map(Path::to_path_buf)
            })
            .or_else(|| env::var_os("CARGO_TARGET_DIR").map(PathBuf::from))
            .unwrap_or_else(env::temp_dir)
            .join("zk-fixtures")
    });
    let root = std::path::absolute(&root).map_err(at(&root))?;
    check_path_text(&root)?;
    fs::create_dir_all(&root).map_err(at(&root))?;
    Ok(root)
}

/// Characters forbidden in fixture directory paths to prevent parsing conflicts
/// in OpenSSL environment variables, certificate lists, and C string literals.
const PATH_BAD: [char; 4] = [':', ',', '"', '\\'];

/// Verifies that a path consists exclusively of ASCII characters without reserved delimiters.
fn check_path_text(path: &Path) -> Result<(), String> {
    match path.to_str() {
        Some(text) if text.is_ascii() && !text.contains(PATH_BAD) => Ok(()),
        _ => Err(format!(
            "the fixture path {} must be ASCII and must not hold ':', ',', '\"' or '\\'. \
             Set ZK_TEST_FIXTURE_DIR to another directory",
            path.display()
        )),
    }
}

/// Obtains a cached fixture directory, synchronizing through advisory file locking and building
/// if absent.
fn obtain(root: &Path, plan: &Plan) -> Result<PathBuf, String> {
    let name = format!("{}-{}", plan.set.name, plan.key);
    let done = root.join(&name);
    let lock = wait_for_lock(root, &name, &done);
    // Re-check existence in case a concurrent process completed the build while acquiring the lock.
    if done.is_dir() {
        return Ok(plan.set.entry(&done));
    }
    let made = build(root, plan, &name, &done);
    drop(lock);
    made
}

/// RAII guard representing an exclusive advisory lock directory tagged with the owner's PID.
struct Busy(PathBuf);

impl Drop for Busy {
    fn drop(&mut self) {
        // Only release the lock directory if this process is still recorded as the active owner,
        // avoiding removing locks that were reclaimed after being deemed stale.
        if read_pid(&self.0) == Some(process::id()) {
            let _ = fs::remove_dir_all(&self.0);
        }
    }
}

/// Polls for acquisition of an advisory build lock, returning an RAII guard on success.
fn wait_for_lock(root: &Path, name: &str, done: &Path) -> Option<Busy> {
    let busy = root.join(format!("{name}.busy"));
    let start = Instant::now();
    loop {
        if done.is_dir() {
            return None;
        }
        match take_lock(root, name, &busy) {
            Ok(Some(lock)) => return Some(lock),
            Ok(None) if start.elapsed() <= WAIT => {}
            _ => return None,
        }
        if !stale(&busy).is_some_and(|seen| reclaim(root, name, &busy, seen)) {
            thread::sleep(POLL);
        }
    }
}

/// Attempts an atomic creation and rename sequence to acquire the advisory lock directory.
fn take_lock(root: &Path, name: &str, busy: &Path) -> io::Result<Option<Busy>> {
    let tmp = root.join(format!(".{name}.busy-{}", process::id()));
    let _ = fs::remove_dir_all(&tmp);
    fs::create_dir(&tmp)?;
    let made =
        fs::write(tmp.join("pid"), process::id().to_string()).and_then(|()| fs::rename(&tmp, busy));
    match made {
        Ok(()) => Ok(Some(Busy(busy.to_path_buf()))),
        Err(e) => {
            let _ = fs::remove_dir_all(&tmp);
            if busy.exists() { Ok(None) } else { Err(e) }
        }
    }
}

/// Reads the owning process identifier from an active lock directory.
fn read_pid(busy: &Path) -> Option<u32> {
    fs::read_to_string(busy.join("pid"))
        .ok()?
        .trim()
        .parse()
        .ok()
}

/// Determines whether an active lock directory is orphaned by inspecting the owning process state.
fn stale(busy: &Path) -> Option<Option<u32>> {
    let age = fs::metadata(busy)
        .and_then(|m| m.modified())
        .ok()
        .and_then(|t| t.elapsed().ok());
    let pid = read_pid(busy);
    let dead = match pid {
        Some(pid) => owner_gone(pid),
        // Valid lock directories created by this helper always contain a pid file.
        // Directories lacking one suggest an interrupted cleanup or external interference.
        None => age.is_some_and(|age| age > NO_PID_GRACE),
    };
    (dead || age.is_some_and(|age| age > WAIT)).then_some(pid)
}

/// Safely reclaims an orphaned lock directory via atomic renaming.
fn reclaim(root: &Path, name: &str, busy: &Path, seen: Option<u32>) -> bool {
    let grave = root.join(format!(".{name}.stale-{}", process::id()));
    let _ = fs::remove_dir_all(&grave);
    if fs::rename(busy, &grave).is_err() {
        return false;
    }
    if read_pid(&grave) != seen && fs::rename(&grave, busy).is_ok() {
        return true;
    }
    let _ = fs::remove_dir_all(&grave);
    true
}

/// Tests whether a process ID corresponds to an active running process.
#[cfg(unix)]
fn owner_gone(pid: u32) -> bool {
    Command::new("kill")
        .args(["-0", &pid.to_string()])
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .status()
        .is_ok_and(|s| !s.success())
}

#[cfg(not(unix))]
fn owner_gone(_pid: u32) -> bool {
    false
}

/// Orchestrates fixture generation within an isolated scratch directory before atomically
/// publishing it.
#[allow(
    clippy::print_stderr,
    reason = "one line tells where the new material is"
)]
fn build(root: &Path, plan: &Plan, name: &str, done: &Path) -> Result<PathBuf, String> {
    let job = Job::start(root, name, &plan.inputs)?;
    match &plan.corpus {
        None => make_tls(plan, &job)?,
        Some(tools) => make_corpus(plan, tools, &job)?,
    }
    match fs::rename(&job.work, done) {
        Ok(()) => {
            let log_path = root.join(format!("{name}.log"));
            let _ = fs::rename(&job.log_path, &log_path);
            eprintln!(
                "zk-testkit: made the {} in {} (log {})",
                plan.set.what,
                done.display(),
                log_path.display()
            );
        }
        // A directory bearing the finalized name is guaranteed complete; if another builder won
        // the race, use its output.
        Err(_) if done.is_dir() => {
            let _ = fs::remove_dir_all(&job.work);
            let _ = fs::remove_file(&job.log_path);
        }
        Err(e) => {
            return Err(format!(
                "cannot rename {} to {}: {e}",
                job.work.display(),
                done.display()
            ));
        }
    }
    Ok(plan.set.entry(done))
}

/// Diagnostic context and filesystem tracking for an in-flight fixture compilation job.
struct Job {
    log: File,
    log_path: PathBuf,
    work: PathBuf,
}

impl Job {
    /// Initializes an isolated temporary workspace and prepares an execution log detailing input
    /// hashes.
    fn start(root: &Path, name: &str, inputs: &str) -> Result<Self, String> {
        let work = root.join(format!(".tmp-{name}-{}", process::id()));
        if work.exists() {
            fs::remove_dir_all(&work).map_err(at(&work))?;
        }
        fs::create_dir_all(&work).map_err(at(&work))?;
        let log_path = root.join(format!(".tmp-{name}-{}.log", process::id()));
        let mut log = File::create(&log_path).map_err(at(&log_path))?;
        let header = format!("{KEY_RULE} inputs of {name}:\n{inputs}\n");
        log.write_all(header.as_bytes()).map_err(at(&log_path))?;
        Ok(Self {
            log,
            log_path,
            work,
        })
    }

    /// Invokes a generator command, streaming stdout and stderr into the compilation log.
    fn run(&self, cmd: &mut Command, what: &str) -> Result<(), String> {
        let log = || self.log.try_clone().map_err(at(&self.log_path));
        let status = cmd
            .current_dir(&self.work)
            .stdin(Stdio::null())
            .stdout(log()?)
            .stderr(log()?)
            .status()
            .map_err(|e| self.failure(&format!("{what} does not start ({e})")))?;
        if status.success() {
            Ok(())
        } else {
            Err(self.failure(&format!("{what} fails ({status})")))
        }
    }

    /// Constructs a detailed error description containing the terminal log output, preserving the
    /// scratch tree for debugging.
    fn failure(&self, what: &str) -> String {
        let text = fs::read(&self.log_path)
            .map(|b| String::from_utf8_lossy(&b).into_owned())
            .unwrap_or_default();
        let lines: Vec<&str> = text.lines().collect();
        let tail: String = lines
            .iter()
            .skip(lines.len().saturating_sub(TAIL_LINES))
            .map(|line| format!("  {line}\n"))
            .collect();
        format!(
            "{what}. The work directory {} stays. Log {}, last lines:\n{tail}",
            self.work.display(),
            self.log_path.display()
        )
    }
}

fn make_tls(plan: &Plan, job: &Job) -> Result<(), String> {
    // Because gen.sh and trust/gen.sh generate artifacts relative to their own directories,
    // script copies are staged into the temporary workspace (retained in the output for C
    // conformance tests).
    let trust = job.work.join("trust");
    fs::create_dir_all(&trust).map_err(at(&trust))?;
    let src = plan.rs.join(TLS_SRC);
    for script in TLS_SCRIPTS {
        let (from, to) = (src.join(script), job.work.join(script));
        fs::copy(&from, &to).map_err(|e| format!("{} to {}: {e}", from.display(), to.display()))?;
    }
    // Execute generation passes; 'all' directs gen.sh to compile all certificate sections.
    for (script, sections) in [("gen.sh", Some("all")), ("trust/gen.sh", None)] {
        let mut cmd = Command::new("sh");
        job.run(cmd.arg(script).arg(&plan.openssl).args(sections), script)?;
    }

    for file in [
        "ca.pem",
        "server.pem",
        "client.key",
        "keys/rsa-pkcs1.pem",
        "keys/p8-scrypt.pem",
    ] {
        if !job.work.join(file).is_file() {
            return Err(job.failure(&format!("gen.sh made no {file}")));
        }
    }
    let expect = fs::read_to_string(trust.join("expect.txt")).unwrap_or_default();
    for line in ["load ca-root.pem ok", "load ca-bundle.pem ok"] {
        if !expect.lines().any(|l| l.trim() == line) {
            return Err(job.failure(&format!(
                "trust/expect.txt has no \"{line}\" line: a process listens on \
                 127.0.0.1:23419, so the load verdicts are wrong"
            )));
        }
    }
    Ok(())
}

fn make_corpus(plan: &Plan, tools: &CorpusTools, job: &Job) -> Result<(), String> {
    let out = job.work.join("corpus");
    let mut cmd = Command::new(&tools.bash);
    cmd.arg(plan.rs.join(CORPUS_SCRIPT))
        .env("OPENSSL", &plan.openssl)
        .env("ZK_CORPUS_OUT", &out)
        .env("ZK_C_DIR", &tools.c_dir);
    job.run(&mut cmd, "gencorpus.sh")?;
    let cases = count_cases(&out);
    if cases <= MIN_CASES {
        return Err(job.failure(&format!(
            "gencorpus.sh made {cases} cases, and a full corpus has more than {MIN_CASES}"
        )));
    }
    if !out.join("_keys/leaf.pem").is_file() {
        return Err(job.failure("gencorpus.sh made no _keys/leaf.pem"));
    }
    for case in ["tc01b-gencerts", "tc02-gencerts"] {
        if !out.join(case).join("verdict.txt").is_file() {
            return Err(job.failure(&format!(
                "gencorpus.sh made no {case} case from the real gencerts.sh"
            )));
        }
    }
    Ok(())
}

/// Computes the total number of test case directories that contain an evaluated verdict file.
fn count_cases(dir: &Path) -> usize {
    fs::read_dir(dir)
        .map(|entries| {
            entries
                .filter_map(Result::ok)
                .filter(|e| e.path().join("verdict.txt").is_file())
                .count()
        })
        .unwrap_or(0)
}

#[cfg(test)]
mod tests {
    use super::accepts_version;

    #[test]
    fn version_rule_accepts_only_openssl_3_4_to_3_x() {
        assert!(accepts_version("OpenSSL 3.4.0 22 Oct 2024"));
        assert!(accepts_version(
            "OpenSSL 3.6.3 9 Jun 2026 (Library: OpenSSL 3.6.3 9 Jun 2026)"
        ));
        assert!(!accepts_version("OpenSSL 3.0.13 30 Jan 2024"));
        assert!(!accepts_version("OpenSSL 3.3.2 3 Sep 2024"));
        assert!(!accepts_version("OpenSSL 4.1.0 23 Oct 2026"));
        assert!(!accepts_version("LibreSSL 3.3.6"));
        assert!(!accepts_version(""));
    }
}

//! 데스크톱 마스터 키 파일(ADR-023, ADR-024): 런타임이 업무 DB 비밀번호를 암호화하는 256 비트 키.
//!
//! 경로는 lib.rs 만 정한다: `<앱 로컬 데이터 디렉터리>/secrets/master.key`. 이 모듈은 경로를 받기만 한다(시험은 임시 디렉터리를
//! 준다). 형식은 소문자 16진수 64 자와 LF 하나다(OS 난수 생성기의 32 바이트, `openssl rand -hex 32` 와 같은 형식).
//!
//! load_or_create 는 시작할 때마다 부른다(캐시하지 않는다).
//! - 키 디렉터리: 없으면 만들고 unix 에서는 0700 으로 정한다. 있으면 심볼릭 링크가 아닌 디렉터리여야 하고, unix 에서는
//!   셸의 실효 uid 소유이며 그룹·기타 권한 비트가 없어야 한다.
//! - 파일이 없으면 첫 실행이다: 32 바이트를 만들어 create_new 로 쓰고(unix 0600), sync_all 한 뒤 다시 읽어 같은지 본다.
//!   create_new 가 AlreadyExists 이면(다른 인스턴스가 먼저 만들었다) 읽기 단계로 간다.
//! - 파일이 있으면 읽기만 한다: 심볼릭 링크가 아닌 일반 파일이고, unix 에서는 실효 uid 소유이며 그룹·기타 권한 비트가 없어야
//!   한다. 4096 바이트 이하이고, 끝의 LF 나 CRLF 하나를 뺀 값이 16진수 64 자(대소문자 무관, 소문자로 맞춘다)이며, 32 바이트가
//!   모두 같지 않아야 한다.
//! - 그 밖의 모든 경우는 master_key_unavailable 로 실패하고 새 키를 만들지 않는다. 있는 파일을 덮어쓰거나, 자르거나,
//!   이름을 바꾸거나, 지우지 않는다. 느슨한 권한을 조용히 고치지 않고 chmod 명령을 안내한다.
//! - Windows 는 권한과 소유자를 확인하지 않는다. %LOCALAPPDATA% 사용자 프로필의 ACL 에만 기댄다(미검증, PLAT-1, CI-1).
//!
//! 값은 런타임 자식의 환경변수 QUERYHUB_MASTER_KEY(launch::child_env)와 자식 출력 가리기 목록에만 쓴다. argv, 상태
//! (RuntimeStatus, Failure), 로그, WebView 에는 넣지 않는다. 오류 메시지는 경로(비밀이 아니다)와 고치는 방법만 말하고 파일
//! 내용은 말하지 않는다.

use std::fmt;
use std::fs::{self, File, OpenOptions};
use std::io::{self, Read, Write};
use std::path::Path;

use super::output::MASK;
use super::private_dir;
// 아래 P1-04 시험(the_owner_and_mode_rule_is_a_pure_decision, the_effective_uid_owns_files_this_process_creates)이 공유
// 규칙을 이 이름들로 부른다. 규칙 자체는 private_dir 에 있다(데이터 디렉터리와 같이 쓴다, P1-06).
#[cfg(all(test, unix))]
use super::private_dir::{effective_uid, permission_problem, PermissionProblem};
use super::token::to_lower_hex;
use super::{Failure, FailureKind};

/// 앱 로컬 데이터 디렉터리 아래의 키 디렉터리 이름.
pub const KEY_DIR_NAME: &str = "secrets";
/// 키 파일 이름.
pub const KEY_FILE_NAME: &str = "master.key";
/// 키 바이트 수(256 비트). 16진수로 두 배 길이(64 자)가 된다.
pub const KEY_BYTES: usize = 32;
const KEY_HEX_LEN: usize = KEY_BYTES * 2;
/// 키 파일의 최대 크기. 이보다 크면 읽지 않는다(런타임의 QUERYHUB_MASTER_KEY_FILE 과 같은 한도).
pub const MAX_FILE_BYTES: usize = 4096;

#[cfg(unix)]
const FILE_MODE: u32 = 0o600;

/// 모든 거부 메시지의 끝에 붙는 안내.
const GUIDE: &str = "docs/DEPLOYMENT.md 의 「마스터 키」 절을 본다. 키 파일을 지우거나 새로 만들면 그 키로 암호화한 비밀번호를 되살릴 수 없다";

/// 키 하나(소문자 16진수 64 자). Debug 와 Display 는 값을 보이지 않고, 값은 expose() 로만 꺼낸다.
#[derive(Clone, PartialEq, Eq)]
pub struct MasterKey(String);

impl MasterKey {
    /// 키 값. 자식 환경(launch::child_env)과 출력 가리기 목록(supervisor::preflight)만 부른다.
    pub fn expose(&self) -> &str {
        &self.0
    }

    /// 시험용: 파일 없이 고정 값으로 만든다(형식 검사는 같다). 시험의 값은 NOT A SECRET 인 고정 값이다.
    #[cfg(test)]
    pub(crate) fn from_test_value(value: &str) -> Self {
        parse_key(value.as_bytes()).expect("시험용 키 형식")
    }
}

impl fmt::Debug for MasterKey {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_tuple("MasterKey").field(&MASK).finish()
    }
}

impl fmt::Display for MasterKey {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(MASK)
    }
}

/// master_key_unavailable 실패. path 는 문제가 있는 파일이나 디렉터리다(비밀이 아니다). cause 에 파일 내용은 넣지 않는다.
fn unavailable(path: &Path, cause: impl fmt::Display) -> Failure {
    Failure::new(
        FailureKind::MasterKeyUnavailable,
        format!(
            "마스터 키를 쓸 수 없어 런타임을 시작하지 않는다 ({}): {cause}. {GUIDE}.",
            path.display()
        ),
    )
}

/// 키 파일을 읽고, 없으면 한 번 만든다. 실패하면 master_key_unavailable 이고 아무것도 쓰지 않는다(첫 실행의 쓰기 도중
/// 실패한 경우만 빼고. 그 파일은 다음 시작에서 형식 오류로 거부된다).
///
/// fill 은 난수 원천이다(운영: getrandom::fill). 파일이 이미 있으면 부르지 않는다.
pub fn load_or_create(
    path: &Path,
    fill: impl FnOnce(&mut [u8]) -> Result<(), getrandom::Error>,
) -> Result<MasterKey, Failure> {
    let dir = match path.parent() {
        Some(dir) if !dir.as_os_str().is_empty() => dir,
        _ => return Err(unavailable(path, "키 파일 경로에 상위 디렉터리가 없다")),
    };
    prepare_dir(dir)?;
    match fs::symlink_metadata(path) {
        Ok(_) => return read_key(path),
        Err(e) if e.kind() == io::ErrorKind::NotFound => {}
        Err(e) => return Err(unavailable(path, format!("키 파일을 확인하지 못했다: {e}"))),
    }

    // 첫 실행: 파일이 없다.
    let mut bytes = [0u8; KEY_BYTES];
    fill(&mut bytes)
        .map_err(|e| unavailable(path, format!("OS 난수 생성기에서 키를 만들지 못했다: {e}")))?;
    if all_identical(&bytes) {
        return Err(unavailable(
            path,
            "난수 생성기가 32 바이트가 모두 같은 키를 돌려주었다(약한 키). 파일을 쓰지 않았다",
        ));
    }
    let generated = to_lower_hex(&bytes);
    let mut contents = generated.clone().into_bytes();
    contents.push(b'\n');
    match write_new(path, &contents) {
        Ok(()) => {}
        // 다른 인스턴스가 먼저 만들었다: 그 파일을 읽는다(덮어쓰지 않는다).
        Err(e) if e.kind() == io::ErrorKind::AlreadyExists => return read_key(path),
        Err(e) => return Err(unavailable(path, format!("키 파일을 만들지 못했다: {e}"))),
    }
    let key = read_key(path)?;
    if key.expose() != generated {
        return Err(unavailable(
            path,
            "새로 쓴 키 파일을 다시 읽은 값이 쓴 값과 다르다",
        ));
    }
    Ok(key)
}

/// 키 디렉터리를 확인하고, 없으면 만든다(unix 0700). 있는 디렉터리의 권한은 바꾸지 않는다. 규칙은 private_dir 에 있다.
fn prepare_dir(dir: &Path) -> Result<(), Failure> {
    private_dir::prepare(dir, "키", &key_failure)
}

/// private_dir 의 실패를 master_key_unavailable 로 만든다.
fn key_failure(path: &Path, cause: String) -> Failure {
    unavailable(path, cause)
}

/// unix: 소유자가 셸의 실효 uid 이고 그룹·기타 권한 비트가 없어야 한다(private_dir 의 규칙).
fn check_permissions(path: &Path, meta: &fs::Metadata, fix_mode: &str) -> Result<(), Failure> {
    private_dir::check_permissions(path, meta, fix_mode, &key_failure)
}

fn check_file(path: &Path, meta: &fs::Metadata) -> Result<(), Failure> {
    if meta.file_type().is_symlink() {
        return Err(unavailable(
            path,
            "키 파일이 심볼릭 링크다. 링크가 아닌 일반 파일이어야 한다",
        ));
    }
    if !meta.is_file() {
        return Err(unavailable(
            path,
            "키 파일 자리에 일반 파일이 아닌 것(디렉터리 등)이 있다",
        ));
    }
    check_permissions(path, meta, "600")?;
    if meta.len() > MAX_FILE_BYTES as u64 {
        return Err(too_large(path));
    }
    Ok(())
}

fn too_large(path: &Path) -> Failure {
    unavailable(path, format!("키 파일이 {MAX_FILE_BYTES} 바이트보다 크다"))
}

/// 있는 키 파일을 읽고 검사한다. 쓰지 않는다.
fn read_key(path: &Path) -> Result<MasterKey, Failure> {
    let meta = fs::symlink_metadata(path)
        .map_err(|e| unavailable(path, format!("키 파일을 확인하지 못했다: {e}")))?;
    check_file(path, &meta)?;
    let file = open_for_read(path)
        .map_err(|e| unavailable(path, format!("키 파일을 열지 못했다: {e}")))?;
    // 연 파일 자체를 다시 확인한다(확인과 열기 사이에 바뀐 경우).
    let opened = file
        .metadata()
        .map_err(|e| unavailable(path, format!("키 파일을 확인하지 못했다: {e}")))?;
    check_file(path, &opened)?;
    let mut bytes = Vec::with_capacity(KEY_HEX_LEN + 2);
    file.take(MAX_FILE_BYTES as u64 + 1)
        .read_to_end(&mut bytes)
        .map_err(|e| unavailable(path, format!("키 파일을 읽지 못했다: {e}")))?;
    if bytes.len() > MAX_FILE_BYTES {
        return Err(too_large(path));
    }
    parse_key(&bytes).map_err(|problem| unavailable(path, problem))
}

/// 파일 내용을 키로 바꾼다. 끝의 LF 나 CRLF 하나를 빼고, 16진수 64 자를 소문자로 맞춘다. 오류에 내용은 넣지 않는다.
fn parse_key(bytes: &[u8]) -> Result<MasterKey, &'static str> {
    let body = bytes
        .strip_suffix(b"\r\n")
        .or_else(|| bytes.strip_suffix(b"\n"))
        .unwrap_or(bytes);
    if body.len() != KEY_HEX_LEN || !body.iter().all(u8::is_ascii_hexdigit) {
        return Err("키 파일의 내용이 16진수 64 자(끝의 줄바꿈 하나는 허용한다)가 아니다");
    }
    let text = String::from_utf8_lossy(body).to_ascii_lowercase();
    // 16진수 두 자가 한 바이트다. 모든 쌍이 첫 쌍과 같으면 32 바이트가 모두 같다.
    let pairs = text.as_bytes();
    if pairs.chunks(2).all(|pair| pair == &pairs[..2]) {
        return Err("키의 32 바이트가 모두 같다(약한 키)");
    }
    Ok(MasterKey(text))
}

fn all_identical(bytes: &[u8]) -> bool {
    bytes.iter().all(|b| *b == bytes[0])
}

/// 새 파일로만 쓴다(create_new). 파일이 있으면 AlreadyExists 이고 그 파일은 건드리지 않는다. unix 에서는 0600 이다.
fn write_new(path: &Path, contents: &[u8]) -> io::Result<()> {
    let mut options = OpenOptions::new();
    options.write(true).create_new(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.mode(FILE_MODE);
    }
    let mut file = options.open(path)?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        file.set_permissions(fs::Permissions::from_mode(FILE_MODE))?;
    }
    file.write_all(contents)?;
    file.sync_all()
}

/// unix: 심볼릭 링크를 따라가지 않고(O_NOFOLLOW), FIFO 에서 멈추지 않게(O_NONBLOCK) 연다.
#[cfg(unix)]
fn open_for_read(path: &Path) -> io::Result<File> {
    use std::os::unix::fs::OpenOptionsExt;
    OpenOptions::new()
        .read(true)
        .custom_flags(libc::O_NOFOLLOW | libc::O_NONBLOCK)
        .open(path)
}

#[cfg(not(unix))]
fn open_for_read(path: &Path) -> io::Result<File> {
    File::open(path)
}

#[cfg(test)]
mod tests {
    use std::path::PathBuf;
    use std::sync::atomic::{AtomicUsize, Ordering};
    use std::time::{SystemTime, UNIX_EPOCH};

    use super::*;

    static COUNTER: AtomicUsize = AtomicUsize::new(0);

    /// 시험마다 쓰는 임시 디렉터리. 실제 앱 데이터 디렉터리는 쓰지 않는다. data/ 는 앱 로컬 데이터 디렉터리 자리다.
    struct Sandbox {
        root: PathBuf,
    }

    impl Sandbox {
        fn new() -> Self {
            let nanos = SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .map(|d| d.as_nanos())
                .unwrap_or(0);
            let root = std::env::temp_dir().join(format!(
                "queryhub-master-key-test-{}-{}-{nanos}",
                std::process::id(),
                COUNTER.fetch_add(1, Ordering::SeqCst)
            ));
            fs::create_dir_all(&root).expect("sandbox");
            Sandbox { root }
        }

        fn data(&self) -> PathBuf {
            self.root.join("data")
        }

        fn dir(&self) -> PathBuf {
            self.data().join(KEY_DIR_NAME)
        }

        fn key(&self) -> PathBuf {
            self.dir().join(KEY_FILE_NAME)
        }

        /// 0700 키 디렉터리와 0600 키 파일을 미리 놓는다(관리자가 미리 배치한 키와 같다).
        fn place(&self, contents: &[u8]) {
            fs::create_dir_all(self.dir()).expect("dir");
            fs::write(self.key(), contents).expect("key");
            #[cfg(unix)]
            {
                use std::os::unix::fs::PermissionsExt;
                fs::set_permissions(self.dir(), fs::Permissions::from_mode(0o700)).expect("chmod");
                fs::set_permissions(self.key(), fs::Permissions::from_mode(0o600)).expect("chmod");
            }
        }
    }

    impl Drop for Sandbox {
        fn drop(&mut self) {
            let _ = fs::remove_dir_all(&self.root);
        }
    }

    /// 시험용 고정 바이트 0x00..0x1f. 실제 키가 아니다(NOT A SECRET).
    fn counting(bytes: &mut [u8]) -> Result<(), getrandom::Error> {
        for (i, byte) in bytes.iter_mut().enumerate() {
            *byte = i as u8;
        }
        Ok(())
    }

    /// 불리면 시험이 실패하는 난수 원천(있는 파일이 있으면 새 키를 만들면 안 된다).
    fn no_random(_: &mut [u8]) -> Result<(), getrandom::Error> {
        panic!("난수 원천을 부르면 안 된다(새 키를 만들면 안 된다)");
    }

    /// 시험용 고정 키(NOT A SECRET): 0x00..0x1f 의 16진수.
    const COUNTING_HEX: &str = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f";

    /// 거부를 확인한다. root 는 시험 디렉터리다: 경로(비밀이 아니다)를 지운 뒤 메시지에 16진수가 16 자 넘게 이어진 곳이 없다.
    fn assert_unavailable_without_content(
        result: Result<MasterKey, Failure>,
        root: &Path,
        content: &str,
    ) -> Failure {
        let failure = result.expect_err("거부해야 한다");
        assert_eq!(failure.kind, FailureKind::MasterKeyUnavailable);
        assert!(!failure.kind.has_output());
        let trimmed = content.trim_end();
        if trimmed.len() >= 8 {
            assert!(
                !failure.message.contains(trimmed),
                "파일 내용이 메시지에 있다: {}",
                failure.message
            );
        }
        // 경로를 뺀 메시지에 16진수가 16 자 넘게 이어진 곳이 없다(키의 일부도 보이지 않는다).
        let without_paths = failure
            .message
            .replace(&root.display().to_string(), "<root>");
        let mut run = 0;
        for c in without_paths.chars() {
            run = if c.is_ascii_hexdigit() { run + 1 } else { 0 };
            assert!(run < 16, "16진수가 이어진다: {}", failure.message);
        }
        failure
    }

    /// AC16: 아무것도 없는 첫 실행은 디렉터리와 파일을 만들고, 파일은 주입한 바이트의 소문자 16진수 64 자와 LF 다.
    #[test]
    fn first_run_creates_the_directory_and_the_file_from_the_injected_bytes() {
        let sandbox = Sandbox::new();
        assert!(!sandbox.data().exists());
        let key = load_or_create(&sandbox.key(), counting).expect("key");
        assert_eq!(key.expose(), COUNTING_HEX);
        let contents = fs::read(sandbox.key()).expect("file");
        assert_eq!(contents, format!("{COUNTING_HEX}\n").into_bytes());
        assert!(sandbox.dir().is_dir());
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let dir_mode = fs::metadata(sandbox.dir())
                .expect("dir")
                .permissions()
                .mode();
            let file_mode = fs::metadata(sandbox.key())
                .expect("file")
                .permissions()
                .mode();
            assert_eq!(dir_mode & 0o777, 0o700, "{dir_mode:o}");
            assert_eq!(file_mode & 0o777, 0o600, "{file_mode:o}");
        }
    }

    /// AC16: 실제 난수 원천(getrandom::fill)으로 만든 키도 형식이 같고 파일 내용과 같다.
    #[test]
    fn first_run_with_the_os_random_source_writes_64_lowercase_hex_and_lf() {
        let sandbox = Sandbox::new();
        let key = load_or_create(&sandbox.key(), getrandom::fill).expect("key");
        assert_eq!(key.expose().len(), 64);
        assert!(key
            .expose()
            .bytes()
            .all(|b| matches!(b, b'0'..=b'9' | b'a'..=b'f')));
        assert_eq!(
            fs::read_to_string(sandbox.key()).expect("file"),
            format!("{}\n", key.expose())
        );
    }

    /// AC17: 두 번째 호출은 난수 원천을 부르지 않고 같은 키를 돌려주며 파일의 바이트와 수정 시각이 그대로다.
    #[test]
    fn a_second_call_reuses_the_key_without_random_and_leaves_the_file_unchanged() {
        let sandbox = Sandbox::new();
        let first = load_or_create(&sandbox.key(), getrandom::fill).expect("first");
        let before = fs::read(sandbox.key()).expect("bytes");
        let modified = fs::metadata(sandbox.key())
            .expect("meta")
            .modified()
            .expect("mtime");
        let second = load_or_create(&sandbox.key(), no_random).expect("second");
        assert_eq!(first, second);
        assert_eq!(fs::read(sandbox.key()).expect("bytes"), before);
        assert_eq!(
            fs::metadata(sandbox.key())
                .expect("meta")
                .modified()
                .expect("mtime"),
            modified
        );
    }

    /// AC17: 미리 놓은 올바른 파일(대문자 16진수와 CRLF 도 허용)은 그대로 쓰고 바이트를 바꾸지 않는다.
    #[test]
    fn a_pre_placed_key_is_used_as_is_including_uppercase_hex_and_crlf() {
        for contents in [
            format!("{COUNTING_HEX}\n"),
            COUNTING_HEX.to_string(),
            format!("{}\r\n", COUNTING_HEX.to_ascii_uppercase()),
        ] {
            let sandbox = Sandbox::new();
            sandbox.place(contents.as_bytes());
            let key = load_or_create(&sandbox.key(), no_random).expect("pre-placed");
            assert_eq!(key.expose(), COUNTING_HEX);
            assert_eq!(fs::read(sandbox.key()).expect("bytes"), contents.as_bytes());
        }
    }

    /// AC17: 새 파일 쓰기(create_new)는 있는 파일에 AlreadyExists 를 돌려주고 내용을 바꾸지 않는다.
    #[test]
    fn the_create_new_helper_refuses_an_existing_file_and_keeps_its_content() {
        let sandbox = Sandbox::new();
        sandbox.place(b"existing");
        let error = write_new(&sandbox.key(), b"replacement").expect_err("must refuse");
        assert_eq!(error.kind(), io::ErrorKind::AlreadyExists);
        assert_eq!(fs::read(sandbox.key()).expect("bytes"), b"existing");
    }

    /// AC18: 형식이 틀린 내용은 모두 거부하고, 난수 원천을 부르지 않으며, 바이트를 바꾸지 않고, 메시지에 내용이 없다.
    #[test]
    fn malformed_contents_are_refused_without_random_or_writes_and_without_echoing_content() {
        let long = "0".repeat(MAX_FILE_BYTES + 1);
        let cases: Vec<(&str, String)> = vec![
            ("empty", String::new()),
            ("only newline", "\n".to_string()),
            ("63 hex", format!("{}\n", &COUNTING_HEX[..63])),
            ("65 hex", format!("{COUNTING_HEX}a\n")),
            ("non-hex", format!("{}zz\n", &COUNTING_HEX[..62])),
            (
                "inner space",
                format!("{} {}\n", &COUNTING_HEX[..32], &COUNTING_HEX[33..]),
            ),
            ("two newlines", format!("{COUNTING_HEX}\n\n")),
            ("leading space", format!(" {COUNTING_HEX}")),
            ("all identical", format!("{}\n", "ab".repeat(32))),
            ("all zero", format!("{}\n", "0".repeat(64))),
            ("over 4096 bytes", long),
        ];
        for (name, contents) in cases {
            let sandbox = Sandbox::new();
            sandbox.place(contents.as_bytes());
            let failure = assert_unavailable_without_content(
                load_or_create(&sandbox.key(), no_random),
                &sandbox.root,
                &contents,
            );
            assert!(
                failure
                    .message
                    .contains(&sandbox.key().display().to_string()),
                "{name}: {}",
                failure.message
            );
            assert_eq!(
                fs::read(sandbox.key()).expect("bytes"),
                contents.as_bytes(),
                "{name}: 파일이 바뀌었다"
            );
        }
    }

    /// AC18: 키 파일 자리에 디렉터리가 있으면 거부하고 난수 원천을 부르지 않는다.
    #[test]
    fn a_directory_at_the_key_path_is_refused() {
        let sandbox = Sandbox::new();
        sandbox.place(b"x");
        fs::remove_file(sandbox.key()).expect("rm");
        fs::create_dir(sandbox.key()).expect("dir at key path");
        let failure = assert_unavailable_without_content(
            load_or_create(&sandbox.key(), no_random),
            &sandbox.root,
            "",
        );
        assert!(
            failure.message.contains("일반 파일이 아닌"),
            "{}",
            failure.message
        );
        assert!(sandbox.key().is_dir());
    }

    /// 난수 원천이 실패하면 파일을 쓰지 않는다. 모두 같은 바이트를 돌려주어도 쓰지 않는다.
    #[test]
    fn a_failing_or_constant_random_source_writes_no_file() {
        let sandbox = Sandbox::new();
        let failure = load_or_create(&sandbox.key(), |_| Err(getrandom::Error::UNSUPPORTED))
            .expect_err("must fail");
        assert_eq!(failure.kind, FailureKind::MasterKeyUnavailable);
        assert!(
            failure.message.contains("난수 생성기"),
            "{}",
            failure.message
        );
        assert!(!sandbox.key().exists());

        let failure = load_or_create(&sandbox.key(), |bytes| {
            bytes.fill(0x42);
            Ok(())
        })
        .expect_err("must refuse a constant key");
        assert!(failure.message.contains("약한 키"), "{}", failure.message);
        assert!(!sandbox.key().exists());
    }

    /// AC21: Debug 와 Display 는 값을 보이지 않는다(16진수 숫자가 하나도 없다).
    #[test]
    fn debug_and_display_never_show_the_key() {
        let sandbox = Sandbox::new();
        let key = load_or_create(&sandbox.key(), counting).expect("key");
        let shown = [format!("{key:?}"), format!("{key:#?}"), format!("{key}")];
        assert_eq!(shown[0], format!("MasterKey({MASK:?})"));
        assert_eq!(shown[2], MASK);
        for text in &shown {
            assert!(!text.contains(key.expose()));
            let rest = text.replace("MasterKey", "");
            assert!(!rest.bytes().any(|b| b.is_ascii_hexdigit()), "{text}");
        }
    }

    #[cfg(unix)]
    mod unix {
        use std::os::unix::fs::{symlink, PermissionsExt};

        use super::super::*;
        use super::*;

        fn chmod(path: &Path, mode: u32) {
            fs::set_permissions(path, fs::Permissions::from_mode(mode)).expect("chmod");
        }

        /// AC18: 키 파일 자리의 심볼릭 링크(올바른 키를 가리켜도)는 거부하고 링크와 대상을 바꾸지 않는다.
        #[test]
        fn a_symlink_at_the_key_path_is_refused() {
            let sandbox = Sandbox::new();
            sandbox.place(b"x");
            fs::remove_file(sandbox.key()).expect("rm");
            let target = sandbox.root.join("elsewhere.key");
            let contents = format!("{COUNTING_HEX}\n");
            fs::write(&target, &contents).expect("target");
            chmod(&target, 0o600);
            symlink(&target, sandbox.key()).expect("symlink");
            let failure = assert_unavailable_without_content(
                load_or_create(&sandbox.key(), no_random),
                &sandbox.root,
                &contents,
            );
            assert!(
                failure.message.contains("심볼릭 링크"),
                "{}",
                failure.message
            );
            assert!(fs::symlink_metadata(sandbox.key())
                .expect("link")
                .file_type()
                .is_symlink());
            assert_eq!(fs::read_to_string(&target).expect("target"), contents);
        }

        /// AC18: 키 디렉터리 자리에 파일이나 심볼릭 링크가 있으면 거부한다.
        #[test]
        fn a_key_directory_that_is_a_file_or_a_symlink_is_refused() {
            let sandbox = Sandbox::new();
            fs::create_dir_all(sandbox.data()).expect("data");
            fs::write(sandbox.dir(), b"not a directory").expect("file at dir");
            let failure = assert_unavailable_without_content(
                load_or_create(&sandbox.key(), no_random),
                &sandbox.root,
                "not a directory",
            );
            assert!(
                failure.message.contains("디렉터리가 아닌"),
                "{}",
                failure.message
            );
            assert_eq!(fs::read(sandbox.dir()).expect("bytes"), b"not a directory");

            let sandbox = Sandbox::new();
            let real = sandbox.root.join("real-secrets");
            fs::create_dir_all(&real).expect("real");
            chmod(&real, 0o700);
            fs::create_dir_all(sandbox.data()).expect("data");
            symlink(&real, sandbox.dir()).expect("symlink");
            let failure = assert_unavailable_without_content(
                load_or_create(&sandbox.key(), no_random),
                &sandbox.root,
                "",
            );
            assert!(
                failure.message.contains("심볼릭 링크"),
                "{}",
                failure.message
            );
            assert!(
                !real.join(KEY_FILE_NAME).exists(),
                "링크 너머에 키를 만들었다"
            );
        }

        /// AC19: 0644 나 0640 인 키 파일은 거부하고, 메시지에 경로와 chmod 600 이 있으며, 권한을 바꾸지 않는다.
        #[test]
        fn a_key_file_readable_by_group_or_others_is_refused_with_the_chmod_fix() {
            for mode in [0o644, 0o640, 0o604, 0o660] {
                let sandbox = Sandbox::new();
                let contents = format!("{COUNTING_HEX}\n");
                sandbox.place(contents.as_bytes());
                chmod(&sandbox.key(), mode);
                let failure = assert_unavailable_without_content(
                    load_or_create(&sandbox.key(), no_random),
                    &sandbox.root,
                    &contents,
                );
                let fix = format!("chmod 600 '{}'", sandbox.key().display());
                assert!(
                    failure.message.contains(&fix),
                    "{mode:o}: {}",
                    failure.message
                );
                let now = fs::metadata(sandbox.key())
                    .expect("meta")
                    .permissions()
                    .mode()
                    & 0o777;
                assert_eq!(now, mode, "권한을 저절로 바꾸었다");
                assert_eq!(fs::read(sandbox.key()).expect("bytes"), contents.as_bytes());
            }
        }

        /// AC19: 0755 나 0750 인 키 디렉터리는 거부하고, 메시지에 경로와 chmod 700 이 있으며, 파일을 만들지 않는다.
        #[test]
        fn a_key_directory_open_to_group_or_others_is_refused_with_the_chmod_fix() {
            for mode in [0o755, 0o750, 0o701, 0o770] {
                let sandbox = Sandbox::new();
                fs::create_dir_all(sandbox.dir()).expect("dir");
                chmod(&sandbox.dir(), mode);
                let failure = assert_unavailable_without_content(
                    load_or_create(&sandbox.key(), no_random),
                    &sandbox.root,
                    "",
                );
                let fix = format!("chmod 700 '{}'", sandbox.dir().display());
                assert!(
                    failure.message.contains(&fix),
                    "{mode:o}: {}",
                    failure.message
                );
                assert!(!sandbox.key().exists(), "파일을 만들었다");
                let now = fs::metadata(sandbox.dir())
                    .expect("meta")
                    .permissions()
                    .mode()
                    & 0o777;
                assert_eq!(now, mode, "권한을 저절로 바꾸었다");
            }
        }

        /// AC19: 순수 판정 함수는 다른 uid 와 0o077 비트를 거부하고, 같은 uid 의 0600/0700/0400 을 받는다.
        #[test]
        fn the_owner_and_mode_rule_is_a_pure_decision() {
            let euid = 501;
            for accepted in [0o600, 0o700, 0o400, 0o100600, 0o040700] {
                assert_eq!(
                    permission_problem(euid, accepted, euid),
                    None,
                    "{accepted:o}"
                );
            }
            assert_eq!(
                permission_problem(0, 0o600, euid),
                Some(PermissionProblem::Owner { owner: 0, euid })
            );
            assert_eq!(
                permission_problem(502, 0o700, euid),
                Some(PermissionProblem::Owner { owner: 502, euid })
            );
            for bit in [0o040, 0o020, 0o010, 0o004, 0o002, 0o001] {
                assert_eq!(
                    permission_problem(euid, 0o600 | bit, euid),
                    Some(PermissionProblem::Mode { mode: 0o600 | bit }),
                    "{bit:o}"
                );
            }
            // 소유자 판정이 먼저다.
            assert!(matches!(
                permission_problem(7, 0o644, euid),
                Some(PermissionProblem::Owner { .. })
            ));
        }

        /// 실행한 사용자와 같은 uid 의 실제 파일은 받는다(판정에 쓰는 uid 가 실효 uid 다).
        #[test]
        fn the_effective_uid_owns_files_this_process_creates() {
            use std::os::unix::fs::MetadataExt;
            let sandbox = Sandbox::new();
            sandbox.place(format!("{COUNTING_HEX}\n").as_bytes());
            assert_eq!(
                fs::metadata(sandbox.key()).expect("meta").uid(),
                effective_uid()
            );
            load_or_create(&sandbox.key(), no_random).expect("accepted");
        }
    }
}

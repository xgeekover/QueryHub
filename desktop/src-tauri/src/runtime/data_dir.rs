//! 데스크톱 데이터 디렉터리(P1-06, ADR-026): 런타임의 내장 SQLite Metadata 저장소가 있는 곳.
//!
//! 경로는 lib.rs 만 정한다: `<앱 로컬 데이터 디렉터리>/data`(키 디렉터리 secrets/ 옆). 이 모듈은 경로를 받기만 한다(시험은
//! 임시 디렉터리를 준다).
//!
//! prepare 는 감독이 시작할 때마다 자식을 띄우기 전에 부른다. 규칙은 마스터 키 디렉터리와 같다(private_dir).
//! - 절대 경로여야 하고 UTF-8 이어야 한다(자식 환경 QUERYHUB_DATA_DIR 의 값이 된다).
//! - 없으면 만들고 unix 에서는 0700 으로 정한다. 있으면 심볼릭 링크가 아닌 디렉터리여야 하고, unix 에서는 셸의 실효 uid
//!   소유이며 그룹·기타 권한 비트가 없어야 한다. 느슨한 권한은 고치지 않고 chmod 700 을 안내한다.
//! - Windows 는 권한과 소유자를 확인하지 않는다(미검증, PLAT-1, CI-1).
//! - 그 밖의 경우는 data_dir_unavailable 로 실패하고 자식을 띄우지 않는다.
//!
//! 셸은 디렉터리만 준비한다. 그 안의 파일(queryhub.db, -wal, -shm, queryhub.lock, native/)은 런타임이 만들고 확인한다(런타임도
//! 같은 0700/0600 규칙과 심볼릭 링크 거부를 따로 적용한다). 셸은 그 파일들을 만들거나, 읽거나, 지우지 않는다.

use std::fmt;
use std::path::Path;

use super::private_dir;
use super::{Failure, FailureKind};

/// 앱 로컬 데이터 디렉터리 아래의 데이터 디렉터리 이름.
pub const DATA_DIR_NAME: &str = "data";

/// 모든 거부 메시지의 끝에 붙는 안내.
const GUIDE: &str = "docs/DEPLOYMENT.md 의 「데이터 디렉터리」 절을 본다. 이 디렉터리에 Metadata 저장소(queryhub.db)가 있다";

/// data_dir_unavailable 실패. path 는 문제가 있는 경로다(비밀이 아니다).
fn unavailable(path: &Path, cause: impl fmt::Display) -> Failure {
    Failure::new(
        FailureKind::DataDirUnavailable,
        format!(
            "데이터 디렉터리를 쓸 수 없어 런타임을 시작하지 않는다 ({}): {cause}. {GUIDE}.",
            path.display()
        ),
    )
}

/// private_dir 의 실패를 data_dir_unavailable 로 만든다.
fn dir_failure(path: &Path, cause: String) -> Failure {
    unavailable(path, cause)
}

/// 데이터 디렉터리를 확인하고, 없으면 만든다(unix 0700). 돌려주는 값은 자식 환경 QUERYHUB_DATA_DIR 의 값(절대 경로)이다.
pub fn prepare(dir: &Path) -> Result<String, Failure> {
    if !dir.is_absolute() {
        return Err(unavailable(
            dir,
            "절대 경로가 아니다(런타임은 절대 경로만 받는다)",
        ));
    }
    let Some(value) = dir.to_str() else {
        return Err(unavailable(
            dir,
            "경로가 UTF-8 이 아니어서 환경변수로 넘길 수 없다",
        ));
    };
    private_dir::prepare(dir, "데이터", &dir_failure)?;
    Ok(value.to_string())
}

#[cfg(test)]
mod tests {
    use std::fs;
    use std::path::PathBuf;
    use std::sync::atomic::{AtomicUsize, Ordering};
    use std::time::{SystemTime, UNIX_EPOCH};

    use super::*;

    static COUNTER: AtomicUsize = AtomicUsize::new(0);

    /// 시험마다 쓰는 임시 디렉터리. 실제 앱 데이터 디렉터리는 쓰지 않는다. app/ 은 앱 로컬 데이터 디렉터리 자리다.
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
                "queryhub-data-dir-test-{}-{}-{nanos}",
                std::process::id(),
                COUNTER.fetch_add(1, Ordering::SeqCst)
            ));
            fs::create_dir_all(&root).expect("sandbox");
            Sandbox { root }
        }

        fn app(&self) -> PathBuf {
            self.root.join("app")
        }

        fn data(&self) -> PathBuf {
            self.app().join(DATA_DIR_NAME)
        }
    }

    impl Drop for Sandbox {
        fn drop(&mut self) {
            let _ = fs::remove_dir_all(&self.root);
        }
    }

    fn assert_unavailable(result: Result<String, Failure>, path: &Path) -> Failure {
        let failure = result.expect_err("거부해야 한다");
        assert_eq!(failure.kind, FailureKind::DataDirUnavailable);
        assert_eq!(failure.kind.as_str(), "data_dir_unavailable");
        assert!(!failure.kind.has_output());
        assert!(
            failure.message.contains(&path.display().to_string()),
            "경로가 없다: {}",
            failure.message
        );
        assert!(
            failure.message.contains("docs/DEPLOYMENT.md"),
            "{}",
            failure.message
        );
        failure
    }

    /// AC20: 없는 데이터 디렉터리(상위의 앱 디렉터리도 없다)를 만들고, 값은 그 절대 경로다. 안에 파일을 만들지 않는다.
    #[test]
    fn a_missing_data_dir_is_created_and_its_absolute_path_is_returned() {
        let sandbox = Sandbox::new();
        assert!(!sandbox.app().exists());
        let value = prepare(&sandbox.data()).expect("prepared");
        assert_eq!(value, sandbox.data().display().to_string());
        assert!(sandbox.data().is_dir());
        assert_eq!(
            fs::read_dir(sandbox.data()).expect("list").count(),
            0,
            "셸이 데이터 디렉터리 안에 무엇인가를 만들었다"
        );
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let mode = fs::metadata(sandbox.data())
                .expect("meta")
                .permissions()
                .mode();
            assert_eq!(mode & 0o777, 0o700, "{mode:o}");
        }
    }

    /// 상대 경로는 만들지 않고 거부한다(셸과 런타임의 작업 디렉터리가 달라 다른 곳을 가리킬 수 있다).
    #[test]
    fn a_relative_path_is_refused_without_creating_anything() {
        let relative = Path::new("queryhub-relative-data-dir-test");
        let failure = assert_unavailable(prepare(relative), relative);
        assert!(failure.message.contains("절대 경로"), "{}", failure.message);
        assert!(!relative.exists());
    }

    #[cfg(unix)]
    mod unix {
        use std::os::unix::fs::{symlink, PermissionsExt};

        use super::*;

        fn chmod(path: &Path, mode: u32) {
            fs::set_permissions(path, fs::Permissions::from_mode(mode)).expect("chmod");
        }

        fn mode(path: &Path) -> u32 {
            fs::symlink_metadata(path)
                .expect("meta")
                .permissions()
                .mode()
                & 0o777
        }

        /// 다시 시작하면 있는 0700 디렉터리를 그대로 쓰고 안의 파일과 수정 시각을 건드리지 않는다.
        #[test]
        fn an_existing_private_data_dir_is_reused_untouched() {
            let sandbox = Sandbox::new();
            prepare(&sandbox.data()).expect("first");
            let db = sandbox.data().join("queryhub.db");
            fs::write(&db, b"store-bytes").expect("db");
            chmod(&db, 0o600);
            let modified = fs::metadata(&db).and_then(|m| m.modified()).expect("mtime");
            let value = prepare(&sandbox.data()).expect("second");
            assert_eq!(value, sandbox.data().display().to_string());
            assert_eq!(fs::read(&db).expect("db"), b"store-bytes");
            assert_eq!(
                fs::metadata(&db).and_then(|m| m.modified()).expect("mtime"),
                modified
            );
            assert_eq!(mode(&sandbox.data()), 0o700);
            assert_eq!(mode(&db), 0o600);
        }

        /// AC20: 데이터 디렉터리 자리의 심볼릭 링크(0700 디렉터리를 가리켜도)는 거부하고 링크와 대상을 바꾸지 않는다.
        #[test]
        fn a_symlinked_data_dir_is_refused() {
            let sandbox = Sandbox::new();
            let real = sandbox.root.join("real-data");
            fs::create_dir_all(&real).expect("real");
            chmod(&real, 0o700);
            fs::create_dir_all(sandbox.app()).expect("app");
            symlink(&real, sandbox.data()).expect("symlink");
            let failure = assert_unavailable(prepare(&sandbox.data()), &sandbox.data());
            assert!(
                failure.message.contains("심볼릭 링크"),
                "{}",
                failure.message
            );
            assert!(fs::symlink_metadata(sandbox.data())
                .expect("link")
                .file_type()
                .is_symlink());
            assert_eq!(fs::read_dir(&real).expect("list").count(), 0);
            assert_eq!(mode(&real), 0o700);
        }

        /// AC20: 데이터 디렉터리 자리에 일반 파일이 있으면 거부하고 내용을 바꾸지 않는다.
        #[test]
        fn a_file_at_the_data_dir_path_is_refused() {
            let sandbox = Sandbox::new();
            fs::create_dir_all(sandbox.app()).expect("app");
            fs::write(sandbox.data(), b"not a directory").expect("file");
            let failure = assert_unavailable(prepare(&sandbox.data()), &sandbox.data());
            assert!(
                failure.message.contains("디렉터리가 아닌"),
                "{}",
                failure.message
            );
            assert_eq!(fs::read(sandbox.data()).expect("bytes"), b"not a directory");
        }

        /// AC20: 0755, 0750 같은 데이터 디렉터리는 거부하고, 메시지에 경로와 chmod 700 이 있으며, 권한을 바꾸지 않는다.
        #[test]
        fn a_data_dir_open_to_group_or_others_is_refused_with_the_chmod_fix() {
            for open in [0o755, 0o750, 0o701, 0o770, 0o707] {
                let sandbox = Sandbox::new();
                fs::create_dir_all(sandbox.data()).expect("data");
                chmod(&sandbox.data(), open);
                let failure = assert_unavailable(prepare(&sandbox.data()), &sandbox.data());
                let fix = format!("chmod 700 '{}'", sandbox.data().display());
                assert!(
                    failure.message.contains(&fix),
                    "{open:o}: {}",
                    failure.message
                );
                assert!(
                    failure.message.contains("저절로 고치지 않는다"),
                    "{}",
                    failure.message
                );
                assert_eq!(mode(&sandbox.data()), open, "권한을 저절로 바꾸었다");
            }
        }

        /// 키 디렉터리와 같은 규칙: 판정은 private_dir 의 순수 함수이고 실효 uid 가 이 프로세스가 만든 디렉터리의 소유자다.
        #[test]
        fn the_owner_rule_is_the_shared_private_dir_rule() {
            use std::os::unix::fs::MetadataExt;
            let sandbox = Sandbox::new();
            prepare(&sandbox.data()).expect("prepared");
            let meta = fs::metadata(sandbox.data()).expect("meta");
            assert_eq!(meta.uid(), private_dir::effective_uid());
            assert_eq!(
                private_dir::permission_problem(meta.uid() + 1, 0o700, meta.uid()),
                Some(private_dir::PermissionProblem::Owner {
                    owner: meta.uid() + 1,
                    euid: meta.uid()
                })
            );
        }
    }
}

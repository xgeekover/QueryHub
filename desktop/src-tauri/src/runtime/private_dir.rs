//! 비공개 디렉터리 규칙: 마스터 키 디렉터리(secrets/, ADR-023)와 데이터 디렉터리(data/, P1-06)가 같이 쓴다.
//!
//! P1-04 의 master_key.rs 에서 꺼낸 규칙이다(동작은 그대로다).
//! - 없으면 상위 디렉터리를 만들고(create_dir_all) 맨 끝 디렉터리를 만든 뒤 unix 에서는 0700 으로 정한다(umask 와 상관없이).
//!   다른 인스턴스가 먼저 만들었으면(AlreadyExists) 권한을 바꾸지 않고 확인만 한다.
//! - 있으면 심볼릭 링크가 아닌 디렉터리여야 하고, unix 에서는 셸의 실효 uid 소유이며 그룹·기타 권한 비트가 없어야 한다.
//!   있는 디렉터리의 권한은 바꾸지 않는다. 느슨한 권한을 조용히 고치지 않고 chmod 명령을 안내한다.
//! - Windows 는 권한과 소유자를 확인하지 않는다. 사용자 프로필의 ACL 에만 기댄다(미검증, PLAT-1, CI-1).
//!
//! 실패의 종류(FailureKind)와 메시지의 앞뒤는 부르는 쪽이 fail 로 정한다. what 은 메시지에 쓰는 디렉터리 이름의 앞말이다
//! ("키" -> 「키 디렉터리」, "데이터" -> 「데이터 디렉터리」). 메시지는 경로(비밀이 아니다)와 고치는 방법만 말한다.

use std::fs;
use std::io;
use std::path::Path;

use super::Failure;

/// 실패를 만드는 함수: (문제가 있는 경로, 원인) -> Failure.
pub(super) type FailWith<'a> = &'a dyn Fn(&Path, String) -> Failure;

#[cfg(unix)]
const DIR_MODE: u32 = 0o700;
/// 그룹과 다른 사용자의 권한 비트. 하나라도 있으면 거부한다.
#[cfg(unix)]
const GROUP_OTHER_BITS: u32 = 0o077;

/// 디렉터리를 확인하고, 없으면 만든다(unix 0700). 있는 디렉터리의 권한은 바꾸지 않는다.
pub(super) fn prepare(dir: &Path, what: &str, fail: FailWith<'_>) -> Result<(), Failure> {
    match fs::symlink_metadata(dir) {
        Ok(meta) => return check_dir(dir, &meta, what, fail),
        Err(e) if e.kind() == io::ErrorKind::NotFound => {}
        Err(e) => return Err(fail(dir, format!("{what} 디렉터리를 확인하지 못했다: {e}"))),
    }
    if let Some(parent) = dir.parent() {
        fs::create_dir_all(parent)
            .map_err(|e| fail(parent, format!("앱 데이터 디렉터리를 만들지 못했다: {e}")))?;
    }
    match create_private_dir(dir) {
        Ok(()) => set_dir_mode(dir, what, fail)?,
        // 다른 인스턴스가 먼저 만들었다: 권한을 바꾸지 않고 확인만 한다.
        Err(e) if e.kind() == io::ErrorKind::AlreadyExists => {}
        Err(e) => return Err(fail(dir, format!("{what} 디렉터리를 만들지 못했다: {e}"))),
    }
    let meta = fs::symlink_metadata(dir)
        .map_err(|e| fail(dir, format!("{what} 디렉터리를 확인하지 못했다: {e}")))?;
    check_dir(dir, &meta, what, fail)
}

fn check_dir(
    dir: &Path,
    meta: &fs::Metadata,
    what: &str,
    fail: FailWith<'_>,
) -> Result<(), Failure> {
    if meta.file_type().is_symlink() {
        return Err(fail(
            dir,
            format!("{what} 디렉터리가 심볼릭 링크다. 링크가 아닌 실제 디렉터리여야 한다"),
        ));
    }
    if !meta.is_dir() {
        return Err(fail(
            dir,
            format!("{what} 디렉터리 자리에 디렉터리가 아닌 것이 있다"),
        ));
    }
    check_permissions(dir, meta, "700", fail)
}

#[cfg(unix)]
fn create_private_dir(dir: &Path) -> io::Result<()> {
    use std::os::unix::fs::DirBuilderExt;
    fs::DirBuilder::new().mode(DIR_MODE).create(dir)
}

#[cfg(not(unix))]
fn create_private_dir(dir: &Path) -> io::Result<()> {
    fs::create_dir(dir)
}

/// 새로 만든 디렉터리만 0700 으로 정한다(umask 와 상관없이).
#[cfg(unix)]
fn set_dir_mode(dir: &Path, what: &str, fail: FailWith<'_>) -> Result<(), Failure> {
    use std::os::unix::fs::PermissionsExt;
    fs::set_permissions(dir, fs::Permissions::from_mode(DIR_MODE)).map_err(|e| {
        fail(
            dir,
            format!("{what} 디렉터리의 권한을 0700 으로 정하지 못했다: {e}"),
        )
    })
}

#[cfg(not(unix))]
fn set_dir_mode(_dir: &Path, _what: &str, _fail: FailWith<'_>) -> Result<(), Failure> {
    Ok(())
}

/// unix 의 소유자·권한 판정에서 찾은 문제.
#[cfg(unix)]
#[derive(Debug, PartialEq, Eq)]
pub(super) enum PermissionProblem {
    /// 소유자가 셸의 실효 uid 가 아니다.
    Owner { owner: u32, euid: u32 },
    /// 그룹이나 다른 사용자의 권한 비트가 있다.
    Mode { mode: u32 },
}

/// 소유자와 모드 판정(순수 함수, root 로 실행해도 시험 결과가 같다).
#[cfg(unix)]
pub(super) fn permission_problem(owner: u32, mode: u32, euid: u32) -> Option<PermissionProblem> {
    if owner != euid {
        return Some(PermissionProblem::Owner { owner, euid });
    }
    if mode & GROUP_OTHER_BITS != 0 {
        return Some(PermissionProblem::Mode {
            mode: mode & 0o7777,
        });
    }
    None
}

/// unix: 소유자가 셸의 실효 uid 이고 그룹·기타 권한 비트가 없어야 한다. 고치지 않고 명령을 안내한다. fix_mode 는 안내할
/// chmod 의 모드다(디렉터리 700, 파일 600).
#[cfg(unix)]
pub(super) fn check_permissions(
    path: &Path,
    meta: &fs::Metadata,
    fix_mode: &str,
    fail: FailWith<'_>,
) -> Result<(), Failure> {
    use std::os::unix::fs::MetadataExt;
    match permission_problem(meta.uid(), meta.mode(), effective_uid()) {
        None => Ok(()),
        Some(PermissionProblem::Owner { owner, euid }) => Err(fail(
            path,
            format!(
                "소유자(uid {owner})가 앱을 실행한 사용자(uid {euid})가 아니다. 소유자를 바꾼다: chown {euid} '{}'",
                path.display()
            ),
        )),
        Some(PermissionProblem::Mode { mode }) => Err(fail(
            path,
            format!(
                "그룹이나 다른 사용자에게 권한이 있다(모드 {mode:04o}). 권한을 저절로 고치지 않는다. 권한을 줄인다: chmod {fix_mode} '{}'",
                path.display()
            ),
        )),
    }
}

/// Windows 등: 권한과 소유자를 확인하지 않는다(사용자 프로필의 ACL 에 기댄다. 미검증, PLAT-1).
#[cfg(not(unix))]
pub(super) fn check_permissions(
    _path: &Path,
    _meta: &fs::Metadata,
    _fix_mode: &str,
    _fail: FailWith<'_>,
) -> Result<(), Failure> {
    Ok(())
}

#[cfg(unix)]
pub(super) fn effective_uid() -> u32 {
    // SAFETY: geteuid(2) 는 인자가 없고 실패하지 않는다.
    unsafe { libc::geteuid() }
}

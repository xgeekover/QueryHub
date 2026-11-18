//! Java 버전 확인: `<java> -version` 의 stderr 에서 처음 나오는 따옴표 안 버전을 읽는다.
//!
//! "1.x" 형식(Java 8 이하)은 x 가 주 버전이다. 런타임 JAR 은 Java 21 용으로 컴파일되어 있다.

use std::io::Read;
use std::path::Path;
use std::process::{Command, Stdio};
use std::thread;
use std::time::{Duration, Instant};

use super::launch::Env;
use super::{Failure, FailureKind};

/// 런타임 실행에 필요한 최소 주 버전.
pub const MIN_MAJOR: u32 = 21;

/// 버전 출력에서 읽는 최대 바이트.
const VERSION_OUTPUT_LIMIT: u64 = 64 * 1024;

/// 출력에서 처음 나오는 따옴표 안의 문자열.
pub fn quoted_version(output: &str) -> Option<&str> {
    let start = output.find('"')? + 1;
    let len = output[start..].find('"')?;
    Some(&output[start..start + len])
}

/// "21.0.11" -> 21, "1.8.0_402" -> 8, "25-ea" -> 25, "17" -> 17.
pub fn parse_major(version: &str) -> Option<u32> {
    let mut parts = version.split(['.', '-', '+', '_']);
    let first = parts.next()?;
    let major = if first == "1" { parts.next()? } else { first };
    if major.is_empty() || !major.bytes().all(|b| b.is_ascii_digit()) {
        return None;
    }
    major.parse().ok()
}

/// java 를 실행해 버전을 확인하고 주 버전을 돌려준다. 실패하면 java_not_found 또는 java_too_old 다.
///
/// env 는 자식 환경과 같은 규칙으로 정리한 환경이다(JAVA_TOOL_OPTIONS 등이 없어 "Picked up ..." 줄이 끼지 않는다).
pub fn check(java: &Path, env: &Env, timeout: Duration) -> Result<u32, Failure> {
    let shown = java.display();
    let output = read_version_output(java, env, timeout).map_err(|reason| {
        Failure::new(
            FailureKind::JavaNotFound,
            format!(
                "Java 버전을 확인하지 못했다 ({shown}): {reason}. QUERYHUB_DESKTOP_JAVA 에 JDK {MIN_MAJOR} 이상의 java 경로를 지정한다."
            ),
        )
    })?;
    let version = quoted_version(&output).ok_or_else(|| {
        Failure::new(
            FailureKind::JavaNotFound,
            format!("Java 버전 출력을 해석하지 못했다 ({shown}). QUERYHUB_DESKTOP_JAVA 에 JDK {MIN_MAJOR} 이상의 java 경로를 지정한다."),
        )
    })?;
    let major = parse_major(version).ok_or_else(|| {
        Failure::new(
            FailureKind::JavaNotFound,
            format!("Java 버전 \"{version}\" 을(를) 해석하지 못했다 ({shown})."),
        )
    })?;
    if major < MIN_MAJOR {
        return Err(Failure::new(
            FailureKind::JavaTooOld,
            format!(
                "Java {major} 은(는) 런타임을 실행할 수 없다 ({shown}, 버전 \"{version}\"). 런타임은 Java {MIN_MAJOR} 이상이 필요하다. QUERYHUB_DESKTOP_JAVA 에 JDK {MIN_MAJOR} 의 java 경로를 지정한다."
            ),
        ));
    }
    Ok(major)
}

/// `<java> -version` 을 실행하고 stderr 를 돌려준다. timeout 안에 끝나지 않으면 죽이고 실패한다.
fn read_version_output(java: &Path, env: &Env, timeout: Duration) -> Result<String, String> {
    let mut child = Command::new(java)
        .arg("-version")
        .env_clear()
        .envs(env)
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::piped())
        .spawn()
        .map_err(|e| format!("실행하지 못했다: {e}"))?;
    let stderr = child.stderr.take();
    let reader = thread::spawn(move || {
        let mut text = String::new();
        if let Some(stream) = stderr {
            let mut bytes = Vec::new();
            let _ = stream.take(VERSION_OUTPUT_LIMIT).read_to_end(&mut bytes);
            text = String::from_utf8_lossy(&bytes).into_owned();
        }
        text
    });
    let deadline = Instant::now() + timeout;
    let status = loop {
        match child.try_wait() {
            Ok(Some(status)) => break status,
            Ok(None) if Instant::now() >= deadline => {
                let _ = child.kill();
                let _ = child.wait();
                let _ = reader.join();
                return Err(format!("{} 초 안에 끝나지 않았다", timeout.as_secs()));
            }
            Ok(None) => thread::sleep(Duration::from_millis(20)),
            Err(e) => return Err(format!("종료를 기다리지 못했다: {e}")),
        }
    };
    let text = reader.join().unwrap_or_default();
    if !status.success() {
        return Err(format!("종료 코드 {:?}", status.code()));
    }
    Ok(text)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_major_versions() {
        assert_eq!(parse_major("21.0.11"), Some(21));
        assert_eq!(parse_major("17.0.2"), Some(17));
        assert_eq!(parse_major("1.8.0_402"), Some(8));
        assert_eq!(parse_major("25-ea"), Some(25));
        assert_eq!(parse_major("21"), Some(21));
        assert_eq!(parse_major("21+35"), Some(21));
        assert_eq!(parse_major(""), None);
        assert_eq!(parse_major("abc"), None);
        assert_eq!(parse_major("1."), None);
    }

    #[test]
    fn finds_the_first_quoted_version() {
        let output = "openjdk version \"21.0.11\" 2026-04-21 LTS\nOpenJDK Runtime Environment Temurin-21.0.11+10 (build \"x\")\n";
        assert_eq!(quoted_version(output), Some("21.0.11"));
        assert_eq!(
            quoted_version("java version \"1.8.0_402\""),
            Some("1.8.0_402")
        );
        assert_eq!(quoted_version("no quotes"), None);
        assert_eq!(quoted_version("unterminated \"21"), None);
    }
}

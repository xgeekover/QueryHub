//! 루프백 HTTP 상태 확인. std::net::TcpStream 으로 GET 을 보내고 상태 줄만 읽는다.
//!
//! 셸은 actuator 의 health 경로만 부른다. 관리 API 는 UI 의 몫이다.

use std::io::{BufRead, BufReader, Read, Write};
use std::net::{SocketAddr, TcpStream};
use std::time::Duration;

/// starting 에서 running 으로 넘어가는 기준.
pub const READINESS_PATH: &str = "/actuator/health/readiness";
/// running 동안의 건강 상태.
pub const HEALTH_PATH: &str = "/actuator/health";

/// 상태 줄의 최대 길이. 그보다 길면 응답으로 인정하지 않는다.
const STATUS_LINE_LIMIT: u64 = 512;

/// 127.0.0.1:<port><path> 에 GET 을 보내고 HTTP 상태 코드를 돌려준다. 연결·읽기 실패나 해석할 수 없는 응답은 None 이다.
pub fn http_status(
    port: u16,
    path: &str,
    connect_timeout: Duration,
    io_timeout: Duration,
) -> Option<u16> {
    let addr = SocketAddr::from(([127, 0, 0, 1], port));
    let mut stream = TcpStream::connect_timeout(&addr, connect_timeout).ok()?;
    stream.set_read_timeout(Some(io_timeout)).ok()?;
    stream.set_write_timeout(Some(io_timeout)).ok()?;
    let request = format!(
        "GET {path} HTTP/1.1\r\nHost: 127.0.0.1:{port}\r\nAccept: */*\r\nConnection: close\r\n\r\n"
    );
    stream.write_all(request.as_bytes()).ok()?;
    let mut line = Vec::new();
    BufReader::new(stream.take(STATUS_LINE_LIMIT))
        .read_until(b'\n', &mut line)
        .ok()?;
    parse_status_line(&String::from_utf8_lossy(&line))
}

/// "HTTP/1.1 200 OK" 에서 200 을 읽는다.
pub fn parse_status_line(line: &str) -> Option<u16> {
    let mut parts = line.trim_end().splitn(3, ' ');
    let version = parts.next()?;
    if !version.starts_with("HTTP/") {
        return None;
    }
    let code = parts.next()?;
    if code.len() != 3 || !code.bytes().all(|b| b.is_ascii_digit()) {
        return None;
    }
    code.parse().ok()
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::net::TcpListener;
    use std::thread;

    #[test]
    fn parses_status_lines() {
        assert_eq!(parse_status_line("HTTP/1.1 200 OK\r\n"), Some(200));
        assert_eq!(
            parse_status_line("HTTP/1.1 503 Service Unavailable"),
            Some(503)
        );
        assert_eq!(parse_status_line("HTTP/1.0 404"), Some(404));
        assert_eq!(parse_status_line("SSH-2.0-OpenSSH"), None);
        assert_eq!(parse_status_line("HTTP/1.1 20 OK"), None);
        assert_eq!(parse_status_line("HTTP/1.1 abc OK"), None);
        assert_eq!(parse_status_line(""), None);
    }

    #[test]
    fn reads_the_status_of_a_loopback_server_and_none_when_nothing_listens() {
        let listener = TcpListener::bind("127.0.0.1:0").expect("bind");
        let port = listener.local_addr().expect("addr").port();
        let server = thread::spawn(move || {
            let (mut stream, _) = listener.accept().expect("accept");
            let mut request = String::new();
            BufReader::new(&stream)
                .read_line(&mut request)
                .expect("request");
            stream
                .write_all(b"HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\n\r\n")
                .expect("response");
            request
        });
        let status = http_status(
            port,
            HEALTH_PATH,
            Duration::from_secs(1),
            Duration::from_secs(2),
        );
        assert_eq!(status, Some(503));
        assert_eq!(
            server.join().expect("server"),
            "GET /actuator/health HTTP/1.1\r\n"
        );

        // 닫힌 포트: 연결이 거부되므로 None.
        let closed = TcpListener::bind("127.0.0.1:0").expect("bind");
        let closed_port = closed.local_addr().expect("addr").port();
        drop(closed);
        assert_eq!(
            http_status(
                closed_port,
                HEALTH_PATH,
                Duration::from_millis(300),
                Duration::from_millis(300)
            ),
            None
        );
    }
}

//! 자식 프로세스 출력: 줄 단위로 끊어 비밀번호를 가리고, 길이를 자르고, 링 버퍼에 마지막 줄들만 남긴다.
//!
//! 셸은 출력을 파일로 쓰지 않는다. 버퍼는 메모리에만 있고 상태 조회(outputTail)와 디버그 stderr 출력에만 쓰인다.

use std::collections::VecDeque;
use std::io::{BufRead, BufReader, ErrorKind, Read};
use std::sync::Mutex;

/// 비밀번호 자리에 넣는 고정 문자열. 길이로 비밀번호 길이를 알 수 없다.
pub const MASK: &str = "********";

/// 링 버퍼. reset 마다 세대(epoch)가 바뀌고, 이전 세대의 읽기 스레드가 늦게 넣는 줄은 버린다.
pub struct OutputBuffer {
    capacity: usize,
    state: Mutex<BufferState>,
}

struct BufferState {
    epoch: u64,
    lines: VecDeque<String>,
}

impl OutputBuffer {
    pub fn new(capacity: usize) -> Self {
        OutputBuffer {
            capacity,
            state: Mutex::new(BufferState {
                epoch: 0,
                lines: VecDeque::new(),
            }),
        }
    }

    /// 버퍼를 비우고 새 세대 번호를 돌려준다.
    pub fn reset(&self) -> u64 {
        let mut state = self.lock();
        state.epoch += 1;
        state.lines.clear();
        state.epoch
    }

    /// 세대가 같을 때만 줄을 넣는다. 용량을 넘으면 가장 오래된 줄을 버린다.
    pub fn push(&self, epoch: u64, line: String) {
        let mut state = self.lock();
        if state.epoch != epoch || self.capacity == 0 {
            return;
        }
        while state.lines.len() >= self.capacity {
            state.lines.pop_front();
        }
        state.lines.push_back(line);
    }

    /// 마지막 n 줄.
    pub fn tail(&self, n: usize) -> Vec<String> {
        let state = self.lock();
        let skip = state.lines.len().saturating_sub(n);
        state.lines.iter().skip(skip).cloned().collect()
    }

    fn lock(&self) -> std::sync::MutexGuard<'_, BufferState> {
        self.state.lock().unwrap_or_else(|e| e.into_inner())
    }
}

/// bytes 안의 secret 을 모두 MASK 로 바꾼다. secret 이 비어 있으면 그대로 돌려준다.
pub fn mask_secret(bytes: &[u8], secret: &[u8]) -> Vec<u8> {
    if secret.is_empty() {
        return bytes.to_vec();
    }
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i..].starts_with(secret) {
            out.extend_from_slice(MASK.as_bytes());
            i += secret.len();
        } else {
            out.push(bytes[i]);
            i += 1;
        }
    }
    out
}

/// 한 줄을 화면에 보일 문자열로 만든다.
///
/// raw 는 읽은 줄의 앞부분이다. cut 이면 줄이 더 길었고 뒷부분은 버려졌다. 그때 raw 의 끝에 비밀번호의 앞부분만 걸려 있을 수
/// 있으므로(나머지는 잘림) 가린 뒤에 끝에 남은 비밀번호 접두사를 지운다. 마지막으로 max_bytes 바이트 이하로 자른다(문자 경계).
pub fn sanitize_line(raw: &[u8], cut: bool, secret: &[u8], max_bytes: usize) -> String {
    let mut masked = mask_secret(raw, secret);
    if cut && secret.len() > 1 {
        for k in (1..secret.len()).rev() {
            if masked.ends_with(&secret[..k]) {
                masked.truncate(masked.len() - k);
                break;
            }
        }
    }
    let mut text = String::from_utf8_lossy(&masked).into_owned();
    if text.len() > max_bytes {
        let mut end = max_bytes;
        while !text.is_char_boundary(end) {
            end -= 1;
        }
        text.truncate(end);
    }
    text
}

/// reader 를 끝(EOF)까지 읽어 줄마다 sink 를 부른다. 줄은 sanitize_line 을 거친다.
///
/// 한 줄에서 기억하는 바이트는 max_bytes * 2 + 비밀번호 길이까지다. 줄바꿈 없는 큰 출력도 메모리를 늘리지 않는다.
pub fn drain<R: Read>(reader: R, secret: &[u8], max_bytes: usize, mut sink: impl FnMut(String)) {
    let keep = max_bytes.saturating_mul(2).saturating_add(secret.len());
    let mut reader = BufReader::new(reader);
    let mut line: Vec<u8> = Vec::new();
    let mut cut = false;
    loop {
        let (consumed, complete) = match reader.fill_buf() {
            Ok([]) => break,
            Ok(buf) => {
                let newline = buf.iter().position(|b| *b == b'\n');
                let chunk = match newline {
                    Some(i) => &buf[..i],
                    None => buf,
                };
                let room = keep.saturating_sub(line.len());
                if chunk.len() > room {
                    line.extend_from_slice(&chunk[..room]);
                    cut = true;
                } else {
                    line.extend_from_slice(chunk);
                }
                (newline.map_or(buf.len(), |i| i + 1), newline.is_some())
            }
            Err(e) if e.kind() == ErrorKind::Interrupted => continue,
            Err(_) => break,
        };
        reader.consume(consumed);
        if complete {
            emit(&mut line, &mut cut, secret, max_bytes, &mut sink);
        }
    }
    if !line.is_empty() || cut {
        emit(&mut line, &mut cut, secret, max_bytes, &mut sink);
    }
}

fn emit(
    line: &mut Vec<u8>,
    cut: &mut bool,
    secret: &[u8],
    max_bytes: usize,
    sink: &mut impl FnMut(String),
) {
    if !*cut && line.last() == Some(&b'\r') {
        line.pop();
    }
    sink(sanitize_line(line, *cut, secret, max_bytes));
    line.clear();
    *cut = false;
}

#[cfg(test)]
mod tests {
    use super::*;

    fn collect(input: &[u8], secret: &str, max: usize) -> Vec<String> {
        let mut lines = Vec::new();
        drain(input, secret.as_bytes(), max, |l| lines.push(l));
        lines
    }

    #[test]
    fn ring_buffer_keeps_the_last_lines_and_drops_other_epochs() {
        let buffer = OutputBuffer::new(3);
        let epoch = buffer.reset();
        for i in 0..5 {
            buffer.push(epoch, format!("line {i}"));
        }
        assert_eq!(buffer.tail(10), vec!["line 2", "line 3", "line 4"]);
        assert_eq!(buffer.tail(2), vec!["line 3", "line 4"]);
        let next = buffer.reset();
        assert!(buffer.tail(10).is_empty());
        buffer.push(epoch, "late line of the previous child".to_string());
        buffer.push(next, "new".to_string());
        assert_eq!(buffer.tail(10), vec!["new"]);
    }

    #[test]
    fn masks_every_occurrence_of_the_password() {
        let lines = collect(
            b"url=jdbc user=qh password=s3cret-pw\r\ns3cret-pws3cret-pw end\nno secret\n",
            "s3cret-pw",
            2048,
        );
        assert_eq!(
            lines,
            vec![
                format!("url=jdbc user=qh password={MASK}"),
                format!("{MASK}{MASK} end"),
                "no secret".to_string(),
            ]
        );
        assert!(lines.iter().all(|l| !l.contains("s3cret")));
    }

    #[test]
    fn long_lines_are_cut_without_leaking_a_partial_password() {
        let secret = "abcdefgh-secret";
        // 비밀번호가 기억하는 범위(max*2 + 길이)의 끝에 걸쳐 잘리는 줄.
        let max = 8;
        let keep = max * 2 + secret.len();
        let mut input = vec![b'x'; keep - 4];
        input.extend_from_slice(secret.as_bytes());
        input.extend_from_slice(b" tail\nnext\n");
        let lines = collect(&input, secret, max);
        assert_eq!(lines.len(), 2);
        assert_eq!(lines[0], "x".repeat(max));
        assert_eq!(lines[1], "next");

        // 잘린 줄 끝의 비밀번호 접두사는 지운다.
        let raw = format!("{}abcd", "y".repeat(10));
        assert_eq!(
            sanitize_line(raw.as_bytes(), true, secret.as_bytes(), 100),
            "y".repeat(10)
        );
        // 잘리지 않은 줄은 그대로 둔다(abcd 는 비밀번호가 아니다).
        assert_eq!(
            sanitize_line(raw.as_bytes(), false, secret.as_bytes(), 100),
            raw
        );
    }

    #[test]
    fn truncates_on_a_char_boundary_and_keeps_a_last_line_without_newline() {
        let lines = collect("가나다라마\n끝".as_bytes(), "", 7);
        // "가나" 는 6 바이트다. 7 바이트로 자르면 세 번째 글자 중간이므로 6 바이트에서 끊는다.
        assert_eq!(lines, vec!["가나".to_string(), "끝".to_string()]);
    }
}

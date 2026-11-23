//! 관리 토큰(ADR-021, ADR-022): 셸 프로세스마다 한 번, OS 난수 생성기(getrandom)에서 32 바이트를 받아 소문자 16진수
//! 64 자로 만든다.
//!
//! 값을 쓰는 곳은 두 군데뿐이다: 런타임 자식의 환경변수 QUERYHUB_ADMIN_TOKEN(launch::child_env)과 UI 가 부르는
//! admin_token 커맨드. argv, 상태(RuntimeStatus, Failure), 로그에는 넣지 않는다. Debug 와 Display 는 값을 보이지 않고,
//! 값은 expose() 로만 꺼낸다.

use std::fmt;

use super::output::MASK;

/// 난수 바이트 수(256 비트). 16진수로 두 배 길이(64 자)가 된다.
pub const TOKEN_BYTES: usize = 32;

/// 셸 프로세스 하나의 관리 토큰.
#[derive(Clone, PartialEq, Eq)]
pub struct AdminToken(String);

/// OS 난수 생성기가 실패했다. 메시지에 토큰 값은 없다(만들지 못했으므로).
#[derive(Debug)]
pub struct TokenError(getrandom::Error);

impl fmt::Display for TokenError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(
            f,
            "OS 난수 생성기에서 관리 토큰을 만들지 못했다: {}",
            self.0
        )
    }
}

impl std::error::Error for TokenError {}

impl AdminToken {
    /// 새 토큰. 실패하면 셸은 런타임을 시작하지 않는다(lib.rs 의 setup 이 실패한다).
    pub fn generate() -> Result<Self, TokenError> {
        Self::generate_with(getrandom::fill)
    }

    /// generate 의 본체. 난수 원천을 바꿔 끼울 수 있다(시험이 실패 경로를 확인한다).
    fn generate_with(
        fill: impl FnOnce(&mut [u8]) -> Result<(), getrandom::Error>,
    ) -> Result<Self, TokenError> {
        let mut bytes = [0u8; TOKEN_BYTES];
        fill(&mut bytes).map_err(TokenError)?;
        Ok(AdminToken(to_lower_hex(&bytes)))
    }

    /// 토큰 값. 자식 환경(launch::child_env)과 admin_token 커맨드만 부른다.
    pub fn expose(&self) -> &str {
        &self.0
    }
}

/// 소문자 16진수(관리 토큰과 마스터 키가 같이 쓴다).
pub(super) fn to_lower_hex(bytes: &[u8]) -> String {
    const DIGITS: &[u8; 16] = b"0123456789abcdef";
    let mut text = String::with_capacity(bytes.len() * 2);
    for byte in bytes {
        text.push(char::from(DIGITS[usize::from(byte >> 4)]));
        text.push(char::from(DIGITS[usize::from(byte & 0x0f)]));
    }
    text
}

impl fmt::Debug for AdminToken {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_tuple("AdminToken").field(&MASK).finish()
    }
}

impl fmt::Display for AdminToken {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(MASK)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn is_lower_hex_64(text: &str) -> bool {
        text.len() == 64 && text.bytes().all(|b| matches!(b, b'0'..=b'9' | b'a'..=b'f'))
    }

    #[test]
    fn a_generated_token_is_64_lowercase_hex_characters_and_two_generations_differ() {
        let first = AdminToken::generate().expect("token");
        let second = AdminToken::generate().expect("token");
        assert!(is_lower_hex_64(first.expose()), "형식이 다르다");
        assert!(is_lower_hex_64(second.expose()), "형식이 다르다");
        assert_ne!(first, second);
        assert_ne!(first.expose(), second.expose());
    }

    #[test]
    fn debug_and_display_never_show_the_value() {
        let token = AdminToken::generate().expect("token");
        let debug = format!("{token:?}");
        let pretty = format!("{token:#?}");
        let display = format!("{token}");
        assert_eq!(debug, format!("AdminToken({MASK:?})"));
        assert_eq!(display, MASK);
        for shown in [&debug, &pretty, &display] {
            assert!(!shown.contains(token.expose()), "토큰이 보인다");
            // 타입 이름을 빼면 16진수 숫자가 하나도 없다(값의 일부도 보이지 않는다).
            let rest = shown.replace("AdminToken", "");
            assert!(
                !rest.bytes().any(|b| b.is_ascii_hexdigit()),
                "16진수가 보인다: {shown}"
            );
        }
    }

    #[test]
    fn encoding_is_lowercase_hex_of_every_byte_in_order() {
        let token = AdminToken::generate_with(|bytes| {
            for (i, byte) in bytes.iter_mut().enumerate() {
                *byte = [0x00, 0x0f, 0xa5, 0xff][i % 4];
            }
            Ok(())
        })
        .expect("token");
        assert_eq!(token.expose(), "000fa5ff".repeat(8));
    }

    #[test]
    fn a_failing_random_source_gives_an_error_and_no_token() {
        let error = AdminToken::generate_with(|_| Err(getrandom::Error::UNSUPPORTED))
            .expect_err("must fail");
        assert!(
            error.to_string().contains("관리 토큰을 만들지 못했다"),
            "{error}"
        );
    }
}

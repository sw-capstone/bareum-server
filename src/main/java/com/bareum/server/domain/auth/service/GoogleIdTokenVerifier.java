package com.bareum.server.domain.auth.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Set;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;

import com.bareum.server.domain.auth.config.GoogleLoginProperties;
import com.bareum.server.domain.auth.exception.AuthErrorCode;
import com.bareum.server.domain.auth.exception.AuthException;

@Component
public class GoogleIdTokenVerifier {

	private static final OAuth2Error INVALID_CLAIMS =
			new OAuth2Error("invalid_token", "Invalid Google ID token claims", null);
	private final GoogleLoginProperties properties;
	private final NimbusJwtDecoder decoder;
	private final Clock clock;

	public GoogleIdTokenVerifier(GoogleLoginProperties properties, NimbusJwtDecoder decoder, Clock clock) {
		this.properties = properties;
		this.decoder = decoder;
		this.clock = clock;
		decoder.setJwtValidator(
				jwt -> {
					try {
						return claimsValid(jwt)
								? OAuth2TokenValidatorResult.success()
								: OAuth2TokenValidatorResult.failure(INVALID_CLAIMS);
					} catch (IllegalArgumentException | ClassCastException invalid) {
						return OAuth2TokenValidatorResult.failure(INVALID_CLAIMS);
					}
				});
	}

	public GoogleIdentity verify(String idToken) {
		// 다른 기능의 기동을 막지 않지만 Client ID 누락 상태에서 인증을 허용하지 않습니다.
		properties.allowedClientIds();
		if (idToken == null || idToken.isBlank() || idToken.length() > 8192) {
			throw new AuthException(AuthErrorCode.INVALID_GOOGLE_AUTHENTICATION);
		}
		try {
			Jwt jwt = decoder.decode(idToken);
			return new GoogleIdentity(
					jwt.getSubject(),
					jwt.getClaimAsString("email"),
					jwt.getClaimAsString("name"),
					jwt.getClaimAsString("hd"));
		} catch (BadJwtException | IllegalArgumentException | ClassCastException exception) {
			// 토큰 원문과 라이브러리의 예외 메시지를 업무 예외·로그에 전달하지 않습니다.
			throw new AuthException(AuthErrorCode.INVALID_GOOGLE_AUTHENTICATION);
		} catch (JwtException exception) {
			throw new AuthException(AuthErrorCode.GOOGLE_AUTHENTICATION_UNAVAILABLE);
		}
	}

	private boolean claimsValid(Jwt jwt) {
		Set<String> audiences = properties.allowedClientIds();
		Instant now = clock.instant();
		String issuer = jwt.getClaimAsString("iss");
		String authorizedParty = jwt.getClaimAsString("azp");
		return ("accounts.google.com".equals(issuer)
				|| "https://accounts.google.com".equals(issuer))
				&& jwt.getAudience() != null
				&& jwt.getAudience().stream().anyMatch(audiences::contains)
				&& (authorizedParty == null || audiences.contains(authorizedParty))
				&& (jwt.getAudience().size() <= 1 || authorizedParty != null)
				&& jwt.getExpiresAt() != null
				&& jwt.getExpiresAt().isAfter(now)
				&& jwt.getIssuedAt() != null
				&& !jwt.getIssuedAt().isAfter(now)
				&& (jwt.getNotBefore() == null || !jwt.getNotBefore().isAfter(now))
				&& textWithinStorage(jwt.getSubject())
				&& textWithinStorage(jwt.getClaimAsString("email"))
				&& jwt.getClaimAsString("email").matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")
				&& Boolean.TRUE.equals(jwt.getClaims().get("email_verified"))
				&& optionalProfileText(jwt.getClaims().get("name"))
				&& optionalProfileText(jwt.getClaims().get("hd"));
	}

	private boolean textWithinStorage(String value) {
		return value != null && !value.isBlank() && value.length() <= 255;
	}

	private boolean optionalProfileText(Object value) {
		return value == null
				|| value instanceof String text && !text.isBlank() && text.length() <= 255;
	}
}

package com.bareum.server.domain.auth.service;

import java.text.ParseException;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import com.bareum.server.domain.auth.config.LoginJwtProperties;
import com.bareum.server.domain.auth.exception.AuthErrorCode;
import com.bareum.server.domain.auth.exception.AuthException;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

@Component
public class LoginJwtService {
	private static final String ISSUER = "bareum-server";
	private static final String AUDIENCE = "bareum-web";
	private final LoginJwtProperties properties;
	private final Clock clock;

	public LoginJwtService(LoginJwtProperties properties, @Qualifier("loginClock") Clock clock) {
		this.properties = properties;
		this.clock = clock;
	}

	public String issue(String purpose, long memberId, UUID family, Instant issuedAt, Instant expiresAt) {
		if (!List.of("access", "refresh").contains(purpose) || memberId < 1
				|| !expiresAt.isAfter(issuedAt))
			throw new IllegalArgumentException("Invalid token claims");
		var claims = new JWTClaimsSet.Builder().issuer(ISSUER)
				.audience(AUDIENCE)
				.subject(Long.toString(memberId))
				.jwtID(UUID.randomUUID().toString())
				.issueTime(Date.from(issuedAt))
				.expirationTime(Date.from(expiresAt))
				.claim("token_use", purpose)
				.claim("family", family.toString())
				.build();
		var signed = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256)
				.type(JOSEObjectType.JWT)
				.build(), claims);
		try {
			signed.sign(new MACSigner(key()));
			return signed.serialize();
		} catch (JOSEException failure) {
			throw unavailable();
		}
	}

	public Optional<VerifiedToken> verify(String value, String purpose) {
		if (value == null || value.length() > 8192
				|| !value.matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+"))
			return Optional.empty();
		byte[] key = key();
		try {
			SignedJWT token = SignedJWT.parse(value);
			if (!JWSAlgorithm.HS256.equals(token.getHeader().getAlgorithm())
					|| !JOSEObjectType.JWT.equals(token.getHeader().getType())
					|| token.getHeader().getCriticalParams() != null
					|| !token.verify(new MACVerifier(key)))
				return Optional.empty();
			JWTClaimsSet claims = token.getJWTClaimsSet();
			Instant now = clock.instant();
			if (!ISSUER.equals(claims.getIssuer()) || !List.of(AUDIENCE).equals(claims.getAudience())
					|| !purpose.equals(claims.getStringClaim("token_use"))
					|| claims.getIssueTime() == null || claims.getIssueTime().toInstant().isAfter(now)
					|| claims.getExpirationTime() == null
					|| !claims.getExpirationTime().toInstant().isAfter(now)
					|| !claims.getExpirationTime().after(claims.getIssueTime())
					|| (claims.getNotBeforeTime() != null && claims.getNotBeforeTime().toInstant().isAfter(now)))
				return Optional.empty();
			long memberId = Long.parseLong(claims.getSubject());
			UUID family = UUID.fromString(claims.getStringClaim("family"));
			UUID.fromString(claims.getJWTID());
			if (memberId < 1)
				return Optional.empty();
			return Optional.of(new VerifiedToken(memberId, family, claims.getExpirationTime().toInstant()));
		} catch (ParseException | JOSEException | IllegalArgumentException | NullPointerException invalid) {
			return Optional.empty();
		}
	}

	private byte[] key() {
		try {
			byte[] key = Base64.getDecoder().decode(properties.signingKey());
			if (key.length < 32)
				throw unavailable();
			return key;
		} catch (IllegalArgumentException | NullPointerException invalid) {
			throw unavailable();
		}
	}

	private AuthException unavailable() { return new AuthException(AuthErrorCode.LOGIN_SERVICE_UNAVAILABLE); }

	public record VerifiedToken(
		long memberId,
		UUID family,
		Instant expiresAt
	) {
	}
}

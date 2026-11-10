package com.bareum.server.domain.auth.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.web.client.RestTemplate;

@Configuration(proxyBeanMethods = false)
public class GoogleJwtDecoderConfig {
	private static final String GOOGLE_JWKS = "https://www.googleapis.com/oauth2/v3/certs";

	// 검증 키의 출처를 고정하고 토큰 헤더의 키 주소는 사용하지 않습니다.
	@Bean
	public NimbusJwtDecoder googleDecoder() {
		var requests = new SimpleClientHttpRequestFactory();
		requests.setConnectTimeout(3000);
		requests.setReadTimeout(3000);
		return NimbusJwtDecoder.withJwkSetUri(GOOGLE_JWKS)
				.jwsAlgorithm(SignatureAlgorithm.RS256)
				.restOperations(new RestTemplate(requests))
				.build();
	}
}

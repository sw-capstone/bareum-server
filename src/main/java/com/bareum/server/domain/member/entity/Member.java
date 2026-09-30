package com.bareum.server.domain.member.entity;

import com.bareum.server.domain.member.exception.MemberException;
import com.bareum.server.domain.member.exception.MemberErrorCode;
import com.bareum.server.domain.member.enums.MemberStatus;
import com.bareum.server.domain.member.enums.SignupMethod;
import com.bareum.server.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnDefault;

@Entity
@Table(name = "member")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Member extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "id", nullable = false)
	private Long id;

	@Column(name = "email", nullable = false, length = 255, unique = true)
	private String email;

	@Column(name = "name", nullable = false, length = 255)
	private String name;

	@Column(name = "password_hash", nullable = true, length = 255)
	private String passwordHash;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 30)
	@ColumnDefault("'ACTIVE'")
	private MemberStatus status;

	@Enumerated(EnumType.STRING)
	@Column(name = "signup_method", nullable = false, length = 30)
	private SignupMethod signupMethod;

	@Column(name = "withdrawn_at", nullable = true)
	private Instant withdrawnAt;

	@Column(name = "withdrawn_reason", nullable = true, length = 255)
	private String withdrawnReason;

	private Member(String email, String name, String passwordHash, SignupMethod signupMethod) {
		Objects.requireNonNull(signupMethod, "signupMethod");
		this.email = email;
		this.name = name;
		this.passwordHash = passwordHash;
		this.status = MemberStatus.ACTIVE;
		this.signupMethod = signupMethod;
	}

	public static Member createLocal(String email, String name, String passwordHash) {
		if (passwordHash == null || passwordHash.isBlank()) {
			throw new MemberException(MemberErrorCode.PASSWORD_HASH_REQUIRED);
		}
		return new Member(email, name, passwordHash, SignupMethod.LOCAL);
	}

	public static Member createGoogle(String email, String name) {
		return new Member(email, name, null, SignupMethod.GOOGLE);
	}
}

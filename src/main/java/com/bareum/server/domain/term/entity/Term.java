package com.bareum.server.domain.term.entity;

import com.bareum.server.domain.term.enums.TermCode;
import com.bareum.server.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "term", uniqueConstraints = @UniqueConstraint(columnNames = {"code", "version"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Term extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "id", nullable = false)
	private Long id;

	@Enumerated(EnumType.STRING)
	@Column(name = "code", nullable = false, length = 30)
	private TermCode code;

	@Column(name = "version", nullable = false, length = 30)
	private String version;

	@Column(name = "is_required", nullable = false)
	private boolean isRequired;

	private Term(TermCode code, String version, boolean isRequired) {
		Objects.requireNonNull(code, "code");
		this.code = code;
		this.version = version;
		this.isRequired = isRequired;
	}

	public static Term create(TermCode code, String version, boolean isRequired) {
		return new Term(code, version, isRequired);
	}
}

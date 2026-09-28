package com.bareum.server.global.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import org.hibernate.annotations.ColumnDefault;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Getter
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseCreatedTimeEntity {

	@CreatedDate
	@ColumnDefault("now()")
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;
}


package com.bareum.server.global.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import org.hibernate.annotations.ColumnDefault;
import org.springframework.data.annotation.LastModifiedDate;

@Getter
@MappedSuperclass
public abstract class BaseTimeEntity extends BaseCreatedTimeEntity {

	@LastModifiedDate
	@ColumnDefault("now()")
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;
}

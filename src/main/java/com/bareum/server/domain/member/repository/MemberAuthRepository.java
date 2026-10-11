package com.bareum.server.domain.member.repository;

import com.bareum.server.domain.member.entity.MemberAuth;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MemberAuthRepository extends JpaRepository<MemberAuth, Long> {}

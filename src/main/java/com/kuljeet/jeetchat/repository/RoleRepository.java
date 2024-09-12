package com.kuljeet.jeetchat.repository;

import com.kuljeet.jeetchat.entity.RoleEntity;
import com.kuljeet.jeetchat.model.ERole;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RoleRepository extends JpaRepository<RoleEntity, UUID> {
  Optional<RoleEntity> findByName(ERole name);
}

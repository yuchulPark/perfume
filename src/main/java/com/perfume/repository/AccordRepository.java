package com.perfume.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.perfume.domain.Accord;

public interface AccordRepository extends JpaRepository<Accord, Long> {

    Optional<Accord> findByName(String name);
}

package com.perfume.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.perfume.domain.Note;

public interface NoteRepository extends JpaRepository<Note, Long> {

    Optional<Note> findByName(String name);
}

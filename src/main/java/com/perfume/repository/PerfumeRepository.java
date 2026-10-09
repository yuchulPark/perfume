package com.perfume.repository;

import java.util.Optional;
import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.perfume.domain.Perfume;

public interface PerfumeRepository extends JpaRepository<Perfume, Long> {

    /** Scalar page with an explicit count query; no lazy Brand access or collection pagination. */
    @Query(value = """
            select p.id as id, p.scentrevPublicId as publicId, p.fragranceSlug as fragranceSlug,
                   p.name as name, p.releaseYear as releaseYear, p.imageUrl as imageUrl,
                   b.id as brandId, b.name as brandName, b.brandSlug as brandSlug
            from Perfume p join p.brand b
            where (:pattern is null or lower(p.name) like :pattern escape '!'
                   or lower(b.name) like :pattern escape '!' or lower(b.brandSlug) like :pattern escape '!')
              and (:brandSlug is null or b.brandSlug = :brandSlug)
            order by lower(p.name), p.id
            """, countQuery = """
            select count(p) from Perfume p join p.brand b
            where (:pattern is null or lower(p.name) like :pattern escape '!'
                   or lower(b.name) like :pattern escape '!' or lower(b.brandSlug) like :pattern escape '!')
              and (:brandSlug is null or b.brandSlug = :brandSlug)
            """)
    Page<PerfumeListProjection> findCatalogPage(@Param("pattern") String pattern,
            @Param("brandSlug") String brandSlug, Pageable page);

    @Query("select p from Perfume p join fetch p.brand where p.id = :id")
    Optional<Perfume> findDetailById(@Param("id") Long id);

    Optional<Perfume> findByScentrevPublicId(String scentrevPublicId);

    Optional<Perfume> findByFragranceSlug(String fragranceSlug);

    boolean existsByScentrevPublicId(String scentrevPublicId);

    boolean existsByFragranceSlug(String fragranceSlug);

    @Query("""
            select p.fragranceSlug as fragranceSlug, p.scentrevPublicId as publicId, p.name as name,
                   b.name as brandName, b.brandSlug as brandSlug
            from Perfume p join p.brand b
            where lower(p.name) like :pattern escape '!' or lower(b.name) like :pattern escape '!'
               or lower(p.fragranceSlug) like :pattern escape '!'
            order by lower(p.name), p.id
            """)
    List<PerfumeSearchProjection> searchCached(@Param("pattern") String pattern, Pageable page);

    @Query("select p.fragranceSlug from Perfume p where p.fragranceSlug in :slugs")
    List<String> findCachedFragranceSlugs(@Param("slugs") Collection<String> slugs);
}

package com.perfume.scentrev.service;

import static com.perfume.scentrev.service.ScentRevOnDemandException.FailureType.*;
import static com.perfume.scentrev.service.ScentRevOnDemandException.failure;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import com.perfume.domain.Perfume;
import com.perfume.scentrev.client.ScentRevMcpClient;
import com.perfume.scentrev.client.ScentRevMcpClientException;
import com.perfume.scentrev.dto.ScentRevFragranceProfileResponse;
import com.perfume.scentrev.dto.ScentRevSearchResponse;

/** Local-first user lookup and one explicitly selected cache fill; no bulk orchestration dependencies. */
@Service
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class ScentRevOnDemandPerfumeService {
    private static final Pattern SLUG = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");
    private final ScentRevMcpClient client;
    private final ScentRevOnDemandCacheAccess cache;
    private final ConcurrentHashMap<String, SlugLock> slugLocks = new ConcurrentHashMap<>();

    public ScentRevOnDemandPerfumeService(ScentRevMcpClient client, ScentRevOnDemandCacheAccess cache) {
        this.client = client;
        this.cache = cache;
    }

    public PerfumeSearchResult search(String query, int limit) {
        String normalized = query == null ? "" : query.strip();
        if (normalized.codePointCount(0, normalized.length()) < 2) { throw failure(INVALID_QUERY); }
        if (limit < 1 || limit > 10) { throw failure(INVALID_LIMIT); }
        java.util.List<PerfumeSearchCandidate> local;
        try { local = cache.searchCached(normalized, limit); }
        catch (RuntimeException exception) { throw failure(PERSISTENCE_FAILURE); }
        if (!local.isEmpty()) { return new PerfumeSearchResult(local.stream().limit(limit).toList()); }
        ScentRevSearchResponse remote;
        try { remote = client.searchFragrances(normalized, limit); }
        catch (RuntimeException exception) { throw providerFailure(exception, PROVIDER_SEARCH_FAILURE); }
        if (remote == null) { throw failure(PROVIDER_SEARCH_FAILURE); }
        var unique = new LinkedHashMap<String, ScentRevSearchResponse.Fragrance>();
        for (var row : remote.results()) {
            if (!canonicalSlug(row.fragranceSlug()) || row.publicId() == null || row.publicId().isBlank()
                    || row.name() == null || row.name().isBlank()
                    || (row.brandSlug() != null && !canonicalSlug(row.brandSlug()))) {
                throw failure(PROVIDER_SEARCH_FAILURE);
            }
            unique.putIfAbsent(row.fragranceSlug(), row);
            if (unique.size() == limit) { break; }
        }
        java.util.Set<String> cached;
        try { cached = cache.cachedSlugs(unique.keySet()); }
        catch (RuntimeException exception) { throw failure(PERSISTENCE_FAILURE); }
        return new PerfumeSearchResult(unique.values().stream().map(row -> new PerfumeSearchCandidate(
                row.fragranceSlug(), row.publicId(), row.name(), row.brandName(), row.brandSlug(),
                cached.contains(row.fragranceSlug()), PerfumeSearchCandidate.Source.SCENTREV)).toList());
    }

    public Perfume getOrImportBySlug(String fragranceSlug) {
        String slug = fragranceSlug == null ? "" : fragranceSlug.strip().toLowerCase(Locale.ROOT);
        if (!canonicalSlug(slug)) { throw failure(INVALID_SLUG); }
        var slot = slugLocks.compute(slug, (key, current) -> {
            var lock = current == null ? new SlugLock() : current;
            lock.users++;
            return lock;
        });
        slot.lock.lock();
        try {
            var existing = findCached(slug);
            if (existing.isPresent()) { return existing.get(); }
            ScentRevFragranceProfileResponse profile;
            try { profile = client.getFragranceProfile(slug); }
            catch (RuntimeException exception) { throw providerFailure(exception, PROFILE_LOOKUP_FAILURE); }
            validateProfile(slug, profile);
            try { cache.importSelected(slug, profile); }
            catch (DataIntegrityViolationException race) {
                // This transaction has rolled back. Read the winner in a fresh transaction; never retry the provider.
                var winner = findCached(slug);
                if (winner.isPresent() && ScentRevOnDemandCacheAccess.matches(winner.get(), slug, profile)) { return winner.get(); }
                throw failure(PERSISTENCE_FAILURE);
            } catch (ScentRevOnDemandException safe) { throw safe; }
            catch (RuntimeException exception) { throw failure(PERSISTENCE_FAILURE); }
            Perfume persisted = findCached(slug).orElseThrow(() -> failure(PERSISTENCE_FAILURE));
            if (!ScentRevOnDemandCacheAccess.matches(persisted, slug, profile)) { throw failure(IDENTIFIER_CONFLICT); }
            return persisted;
        } finally {
            slot.lock.unlock();
            slugLocks.computeIfPresent(slug, (key, current) -> --current.users == 0 ? null : current);
        }
    }

    private java.util.Optional<Perfume> findCached(String slug) {
        try { return cache.findCached(slug); }
        catch (RuntimeException exception) { throw failure(PERSISTENCE_FAILURE); }
    }

    private static void validateProfile(String slug, ScentRevFragranceProfileResponse profile) {
        if (profile == null || profile.identity() == null) { throw failure(CANONICAL_IDENTITY_MISMATCH); }
        var identity = profile.identity();
        if (!slug.equals(identity.fragranceSlug()) || !canonicalSlug(identity.brandSlug())
                || identity.publicId() == null || identity.publicId().isBlank() || identity.publicId().length() > 128
                || identity.name() == null || identity.name().isBlank()
                || identity.brandName() == null || identity.brandName().isBlank()) {
            throw failure(CANONICAL_IDENTITY_MISMATCH);
        }
    }

    private static boolean canonicalSlug(String slug) {
        return slug != null && slug.length() <= 255 && SLUG.matcher(slug).matches();
    }

    private static ScentRevOnDemandException providerFailure(RuntimeException exception,
            ScentRevOnDemandException.FailureType otherwise) {
        if (exception instanceof ScentRevMcpClientException safe) {
            return failure(switch (safe.getFailureType()) {
                case AUTHENTICATION -> PROVIDER_AUTHENTICATION;
                case CONFIGURATION -> PROVIDER_CONFIGURATION;
                default -> otherwise;
            });
        }
        return failure(otherwise);
    }

    /** References include waiters; map computation makes removal safe against a concurrent acquisition. */
    private static class SlugLock {
        private final ReentrantLock lock = new ReentrantLock();
        private int users;
    }
}

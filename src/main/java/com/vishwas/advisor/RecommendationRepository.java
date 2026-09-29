package com.vishwas.advisor;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface RecommendationRepository extends JpaRepository<Recommendation, Long> {

    Optional<Recommendation> findFirstByMismatchIdOrderByCreatedAtDescIdDesc(Long mismatchId);

    List<Recommendation> findByMismatchIdOrderByCreatedAtAsc(Long mismatchId);

    List<Recommendation> findByMismatchIdInOrderByCreatedAtAsc(Collection<Long> mismatchIds);

    List<Recommendation> findByVendorGstinOrderByCreatedAtAsc(String gstin);

    List<Recommendation> findByRunId(Long runId);

    /** Recommendation accuracy per category, computed by the database: [category, judged, correct]. */
    @Query("select r.category, count(r), sum(case when r.wasCorrect = true then 1 else 0 end) "
            + "from Recommendation r where r.wasCorrect is not null group by r.category")
    List<Object[]> accuracyByCategory();

    @Query("select r from Recommendation r where r.wasCorrect is null and r.predictedOutcome is not null and r.mismatchId in :ids")
    List<Recommendation> unjudgedFor(Collection<Long> ids);
}

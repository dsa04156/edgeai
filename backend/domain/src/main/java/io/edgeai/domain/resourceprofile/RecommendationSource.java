package io.edgeai.domain.resourceprofile;

/** Operational KRR invocation for one validated target; the history window is server-bounded. */
public interface RecommendationSource {
    KrrRecommendation recommend(WorkloadTarget target, int historyHours);
}

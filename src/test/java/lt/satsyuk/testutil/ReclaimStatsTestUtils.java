package lt.satsyuk.testutil;

import lt.satsyuk.repository.RequestRepository;

public final class ReclaimStatsTestUtils {

    private ReclaimStatsTestUtils() {
    }

    public static RequestRepository.ReclaimStats reclaimStats(int reclaimedCount, long maxAgeSeconds) {
        return new RequestRepository.ReclaimStats() {
            @Override
            public Integer getReclaimedCount() {
                return reclaimedCount;
            }

            @Override
            public Long getMaxAgeSeconds() {
                return maxAgeSeconds;
            }
        };
    }
}

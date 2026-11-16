package dev.soprano.path;

import java.util.ArrayList;
import java.util.List;

/** Connects a verified continuation to the unconsumed part of a moving player's route. */
public final class FollowSplice {
    private FollowSplice() { }

    /** Keeps the current edge intact and trims any return along the unused prefix. */
    public static List<Step> join(List<Step> current, int activeStep, int prefixSteps, Node anchor, List<Step> continuation) {
        if (activeStep < 0 || activeStep >= prefixSteps || prefixSteps > current.size()
            || !current.get(prefixSteps - 1).to().equals(anchor)
            || !continuation.isEmpty() && !continuation.getFirst().from().equals(anchor)) return List.of();

        int keep = prefixSteps;
        int skip = 0;
        while (keep > activeStep + 1 && skip < continuation.size()) {
            Step reverse = continuation.get(skip);
            Step forward = current.get(keep - 1);
            if (!reverse.from().equals(forward.to()) || !reverse.to().equals(forward.from())) break;
            keep--;
            skip++;
        }

        List<Step> joined = new ArrayList<>(keep + continuation.size() - skip);
        joined.addAll(current.subList(0, keep));
        joined.addAll(continuation.subList(skip, continuation.size()));
        return List.copyOf(joined);
    }
}

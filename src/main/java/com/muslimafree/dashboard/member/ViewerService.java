package com.muslimafree.dashboard.member;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.muslimafree.dashboard.common.config.AppProperties;
import com.muslimafree.dashboard.common.exception.ApiException;
import com.muslimafree.dashboard.common.exception.ErrorCode;
import com.muslimafree.dashboard.common.security.CurrentUser;

/** Loads the signed-in member's own profile: the starting point of every discovery request. */
@Service
public class ViewerService {

    private final ProfileSnapshotRepository profiles;
    private final boolean oppositeGenderOnly;

    public ViewerService(ProfileSnapshotRepository profiles, AppProperties properties) {
        this.profiles = profiles;
        this.oppositeGenderOnly = properties.discovery().oppositeGenderOnly();
    }

    /** The gender of the members this viewer may see, or {@code null} for both ({@code opposite-gender-only}). */
    public Gender visibleGender(ProfileSnapshot viewer) {
        return oppositeGenderOnly ? viewer.gender().opposite() : null;
    }

    /**
     * The viewer must have finished onboarding: their gender decides whom they see, and their age and preferences drive
     * the ranking. A member who stops after sign-up step 1 is not visible to others either, so they cannot browse.
     */
    public ProfileSnapshot requireDiscoverer(CurrentUser user) {
        ProfileSnapshot viewer = profiles.find(user.userId()).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));
        if (!canDiscover(viewer)) {
            throw new ApiException(ErrorCode.ONBOARDING_INCOMPLETE);
        }
        return viewer;
    }

    /** The same viewer for work no request is waiting on (a push): empty instead of an error. */
    public Optional<ProfileSnapshot> discoverer(UUID memberId) {
        return profiles.find(memberId).filter(ViewerService::canDiscover);
    }

    private static boolean canDiscover(ProfileSnapshot viewer) {
        return viewer.onboardingCompleted() && viewer.gender() != null && viewer.dateOfBirth() != null;
    }
}

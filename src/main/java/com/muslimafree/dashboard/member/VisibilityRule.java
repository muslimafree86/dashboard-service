package com.muslimafree.dashboard.member;

import java.util.List;

/**
 * user-profile-managment's {@code MemberVisibilityPolicy} as SQL over {@code p} (user_profiles) and {@code u} (users):
 * the member is ACTIVE, verified, finished onboarding, VISIBLE, of the gender the viewer may see, not the viewer, and
 * neither blocked the other. Shared by discovery and every list of members (Activity, messages, notifications), so a
 * member who may not be seen disappears from all of them - and every card the dashboard shows opens.
 */
public final class VisibilityRule {

    private VisibilityRule() {
    }

    /**
     * @param viewerIdParam the already-bound named parameter holding the viewer's id
     * @param gender        the gender the viewer may see, {@code null} for both
     */
    public static void appendTo(String viewerIdParam, Gender gender, boolean requireVerifiedEmail, List<String> where) {
        where.add("u.status = 'ACTIVE'");
        if (requireVerifiedEmail) {
            where.add("u.email_verified = TRUE");
        }
        where.add("p.onboarding_step = 'COMPLETED'");
        where.add("p.visibility = 'VISIBLE'");
        if (gender != null) {
            // enum constant, never user input
            where.add("p.gender = '" + gender.name() + "'");
        }
        where.add("p.user_id <> " + viewerIdParam);
        where.add("NOT EXISTS (SELECT 1 FROM user_blocks b WHERE b.blocker_id = " + viewerIdParam
                + " AND b.blocked_id = p.user_id)");
        where.add("NOT EXISTS (SELECT 1 FROM user_blocks b WHERE b.blocker_id = p.user_id AND b.blocked_id = "
                + viewerIdParam + ")");
    }
}

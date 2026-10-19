package com.muslimafree.dashboard.discovery;

/** Order of the discovery feed. Every order ends on the member id, so pages never overlap or skip members. */
public enum DiscoverySort {
    /** Best match first (default; see README "Matching"). */
    RELEVANCE,
    /** Most recently active first ("متصل الان" tab). */
    RECENTLY_ACTIVE,
    /** Closest first; needs the viewer's shared location, and lists only members who shared theirs. */
    NEAREST,
    /** Most recently joined first ("new members"). */
    NEWEST
}

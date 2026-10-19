package com.muslimafree.dashboard.member.photo;

import com.muslimafree.dashboard.member.CardPhoto;

/**
 * Turns a card photo into a link an {@code <img src>} can use, in exactly the format user-profile-managment issues, so
 * that the link is redeemed by the same place (its {@code /api/v1/photos/{id}/content} or CloudFront).
 * <p>
 * The link is the credential, so it is only produced for members that already passed the discovery eligibility check
 * (which mirrors the profile service's visibility rule), and only for APPROVED photos.
 */
public interface PhotoUrlSigner {

    String signedUrl(CardPhoto photo);
}

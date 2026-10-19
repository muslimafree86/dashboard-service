package com.muslimafree.dashboard.discovery;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.muslimafree.dashboard.common.security.CurrentUser;
import com.muslimafree.dashboard.member.MemberCard;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * Member discovery for the signed-in member. Whose dashboard it is always comes from the access token; no endpoint
 * takes a viewer id.
 */
@RestController
@RequestMapping("/api/v1/dashboard")
@Tag(name = "Discovery")
public class DiscoveryController {

    private final DiscoveryService discovery;

    public DiscoveryController(DiscoveryService discovery) {
        this.discovery = discovery;
    }

    @GetMapping("/members")
    @Operation(summary = "Member cards for the Home page and its tabs, best match first",
            description = "الرئيسية: no parameters. متصل الان: online=true. الشركاء: mutual=true. Search bar: ageMin, ageMax, "
                    + "country, city, distanceKm. Filters combine (AND). Keyset pagination: pass nextCursor back with "
                    + "the same filters. 409 ONBOARDING_INCOMPLETE before sign-up is finished; 409 LOCATION_REQUIRED for "
                    + "distanceKm / sort=NEAREST without a shared location.")
    public DiscoveryPage members(@Valid @ModelAttribute DiscoverySearchRequest request) {
        return discovery.feed(CurrentUser.require(), request);
    }

    @GetMapping("/members/{memberId}/similar")
    @Operation(summary = "اعضاء مشابهون - members similar to the one whose profile is open",
            description = "404 MEMBER_NOT_FOUND when the caller may not see that member (hidden, blocked, unknown ...).")
    public List<MemberCard> similar(@PathVariable UUID memberId) {
        return discovery.similar(CurrentUser.require(), memberId);
    }

    @GetMapping("/filters")
    @Operation(summary = "Initial values and limits of the Home search bar, from the caller's partner preferences")
    public SearchDefaults filters() {
        return discovery.searchDefaults(CurrentUser.require());
    }
}

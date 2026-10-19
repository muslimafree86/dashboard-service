package com.muslimafree.dashboard.dailypicks;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.muslimafree.dashboard.common.config.AppProperties;
import com.muslimafree.dashboard.common.security.CurrentUser;
import com.muslimafree.dashboard.discovery.CandidateRow;
import com.muslimafree.dashboard.discovery.DiscoveryQuery;
import com.muslimafree.dashboard.discovery.DiscoveryRepository;
import com.muslimafree.dashboard.discovery.DiscoveryService;
import com.muslimafree.dashboard.discovery.DiscoverySort;
import com.muslimafree.dashboard.hidden.HiddenMemberRepository;
import com.muslimafree.dashboard.member.MemberCard;
import com.muslimafree.dashboard.member.ProfileSnapshot;
import com.muslimafree.dashboard.member.ViewerService;

/**
 * "Today's picks": a short list chosen once a UTC day and kept all day, so it can be looked at again and talked
 * about. Chosen by the relevance score among members the viewer may see, excluding those already liked, marked "not
 * interested" or picked in the last {@code app.daily-picks.history} days; mutual matches (no preference contradicted in
 * either direction) come first, the best of the rest fill the list.
 */
@Service
public class DailyPickService {

    private final ViewerService viewers;
    private final DiscoveryService discovery;
    private final DiscoveryRepository candidates;
    private final DailyPickRepository picks;
    private final HiddenMemberRepository hidden;
    private final AppProperties properties;
    private final Clock clock;

    public DailyPickService(ViewerService viewers, DiscoveryService discovery, DiscoveryRepository candidates,
            DailyPickRepository picks, HiddenMemberRepository hidden, AppProperties properties, Clock clock) {
        this.viewers = viewers;
        this.discovery = discovery;
        this.candidates = candidates;
        this.picks = picks;
        this.hidden = hidden;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public DailyPicks today(CurrentUser user) {
        ProfileSnapshot viewer = viewers.requireDiscoverer(user);
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        LocalDate day = now.toLocalDate();
        List<UUID> chosen = picks.picks(viewer.id(), day);
        if (chosen.isEmpty()) {
            picks.save(viewer.id(), day, choose(viewer, now));
            chosen = picks.picks(viewer.id(), day);
        }
        Map<UUID, MemberCard> cards = discovery.visibleCards(viewer, Set.copyOf(chosen)).stream()
                .collect(Collectors.toMap(MemberCard::id, Function.identity()));
        // marked "not interested" after this morning's pick: gone from today's list at once
        Set<UUID> hiddenNow = hidden.hiddenAmong(viewer.id(), chosen);
        List<MemberCard> items = chosen.stream().filter(cards::containsKey).filter(id -> !hiddenNow.contains(id))
                .map(cards::get).toList();
        return new DailyPicks(day, items, day.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC));
    }

    private List<UUID> choose(ProfileSnapshot viewer, LocalDateTime now) {
        int size = properties.dailyPicks().size();
        LocalDate since = now.toLocalDate().minusDays(properties.dailyPicks().history());
        LinkedHashSet<UUID> chosen = new LinkedHashSet<>();
        for (boolean mutual : new boolean[] {true, false}) {
            if (chosen.size() >= size) {
                break;
            }
            DiscoveryQuery.Filters filters = DiscoveryQuery.Filters.builder()
                    .mutualOnly(mutual).notLiked(true).notPickedSince(since).build();
            List<CandidateRow> rows = candidates.find(new DiscoveryQuery(viewer, discovery.centerOf(viewer), now,
                    viewers.visibleGender(viewer), properties.discovery().requireVerifiedEmail(), filters,
                    DiscoverySort.RELEVANCE, null, size * 2, null));
            for (CandidateRow row : rows) {
                if (chosen.size() < size) {
                    chosen.add(row.id());
                }
            }
        }
        return new ArrayList<>(chosen);
    }

    /** Nightly: picks older than the history window are no longer needed. */
    public int cleanUp() {
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        return picks.deleteBefore(today.minusDays(properties.dailyPicks().history() + 1L));
    }
}

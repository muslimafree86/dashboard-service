package com.muslimafree.dashboard.dailypicks;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.muslimafree.dashboard.common.security.CurrentUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@Tag(name = "Daily picks")
public class DailyPickController {

    private final DailyPickService dailyPicks;

    public DailyPickController(DailyPickService dailyPicks) {
        this.dailyPicks = dailyPicks;
    }

    @GetMapping("/api/v1/dashboard/daily-picks")
    @Operation(summary = "Today's picks: up to 10 best matches, the same all (UTC) day",
            description = "Mutual matches first; never someone I already liked, hid, or was shown in the last 7 days.")
    public DailyPicks dailyPicks() {
        return dailyPicks.today(CurrentUser.require());
    }
}

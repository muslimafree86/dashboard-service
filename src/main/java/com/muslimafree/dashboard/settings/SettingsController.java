package com.muslimafree.dashboard.settings;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.muslimafree.dashboard.common.security.CurrentUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/** My dashboard settings (incognito browsing). Account settings stay in user-profile-managment. */
@RestController
@RequestMapping("/api/v1/dashboard/me/settings")
@Tag(name = "Settings")
public class SettingsController {

    private final MemberSettingsRepository settings;
    private final Clock clock;

    public SettingsController(MemberSettingsRepository settings, Clock clock) {
        this.settings = settings;
        this.clock = clock;
    }

    @GetMapping
    @Operation(summary = "My dashboard settings")
    public DashboardSettings get() {
        return new DashboardSettings(settings.incognito(CurrentUser.require().userId()));
    }

    @PutMapping
    @Operation(summary = "Turn incognito browsing on or off")
    @Transactional
    public DashboardSettings update(@Valid @RequestBody UpdateSettingsRequest request) {
        CurrentUser user = CurrentUser.require();
        settings.setIncognito(user.userId(), request.incognito(), LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
        return new DashboardSettings(request.incognito());
    }
}

package com.muslimafree.dashboard.location;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.muslimafree.dashboard.common.security.CurrentUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/** The caller's own opt-in location for distance search. */
@RestController
@RequestMapping("/api/v1/dashboard/me/location")
@Tag(name = "Location")
public class MemberLocationController {

    private final MemberLocationService locations;

    public MemberLocationController(MemberLocationService locations) {
        this.locations = locations;
    }

    @PutMapping
    @Operation(summary = "Share or update my location (snapped to ~1 km, never shown to others)")
    public MemberLocationResponse update(@Valid @RequestBody UpdateLocationRequest request) {
        return locations.update(CurrentUser.require(), request);
    }

    @GetMapping
    @Operation(summary = "My stored location; 204 when none is shared")
    public ResponseEntity<MemberLocationResponse> get() {
        return locations.find(CurrentUser.require()).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @DeleteMapping
    @Operation(summary = "Stop sharing my location (distance search and distances disappear)")
    public ResponseEntity<Void> delete() {
        locations.delete(CurrentUser.require());
        return ResponseEntity.noContent().build();
    }
}

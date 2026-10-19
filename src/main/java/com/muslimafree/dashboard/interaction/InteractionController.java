package com.muslimafree.dashboard.interaction;

import java.util.Locale;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.muslimafree.dashboard.common.security.CurrentUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** Like / favourite buttons of the member profile page, the Home counters and the Activity tab (النشاط). */
@RestController
@RequestMapping("/api/v1/dashboard")
@Tag(name = "Interactions")
public class InteractionController {

    private final InteractionService interactions;

    public InteractionController(InteractionService interactions) {
        this.interactions = interactions;
    }

    @PutMapping("/likes/{memberId}")
    @Operation(summary = "Like a member (idempotent); matched=true when they had already liked me",
            description = "The member sees the like. 404 MEMBER_NOT_FOUND for a member I cannot see; "
                    + "429 DAILY_LIKE_LIMIT_REACHED after app.interactions.daily-like-limit likes in a UTC day.")
    public LikeResponse like(@PathVariable UUID memberId) {
        return interactions.like(CurrentUser.require(), memberId);
    }

    @DeleteMapping("/likes/{memberId}")
    @Operation(summary = "Take back a like (idempotent); ends a match")
    public ResponseEntity<Void> unlike(@PathVariable UUID memberId) {
        interactions.unlike(CurrentUser.require(), memberId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/favourites/{memberId}")
    @Operation(summary = "Add a member to my favourites (private, idempotent)")
    public ResponseEntity<Void> favourite(@PathVariable UUID memberId) {
        interactions.favourite(CurrentUser.require(), memberId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/favourites/{memberId}")
    @Operation(summary = "Remove a member from my favourites (idempotent)")
    public ResponseEntity<Void> unfavourite(@PathVariable UUID memberId) {
        interactions.unfavourite(CurrentUser.require(), memberId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/members/{memberId}/interactions")
    @Operation(summary = "State of the like / favourite buttons on a member's profile")
    public InteractionStatus status(@PathVariable UUID memberId) {
        return interactions.status(CurrentUser.require(), memberId);
    }

    @GetMapping("/activity")
    @Operation(summary = "Home counters: favourites, profile views, likes received, matches")
    public ActivitySummary summary() {
        return interactions.summary(CurrentUser.require());
    }

    @GetMapping("/activity/{list}")
    @Operation(summary = "An Activity list, most recent first",
            description = "list = likes-received | likes-sent | matches | favourites | viewers")
    public ActivityPage list(@PathVariable String list, @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size) {
        return interactions.list(CurrentUser.require(), parse(list), page, size);
    }

    /** {@code likes-received} -> {@link ActivityList#LIKES_RECEIVED}; anything else is a 404. */
    private static ActivityList parse(String path) {
        try {
            return ActivityList.valueOf(path.toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }
}

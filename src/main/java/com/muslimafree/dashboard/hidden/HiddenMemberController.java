package com.muslimafree.dashboard.hidden;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.muslimafree.dashboard.common.security.CurrentUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** "Not interested" / pass: remove a member from my own discovery, and undo it. */
@RestController
@RequestMapping("/api/v1/dashboard/hidden-members")
@Tag(name = "Not interested")
public class HiddenMemberController {

    private final HiddenMemberService hidden;

    public HiddenMemberController(HiddenMemberService hidden) {
        this.hidden = hidden;
    }

    @PutMapping("/{memberId}")
    @Operation(summary = "Hide a member from my feed, tabs, searches and similar members (idempotent)",
            description = "One-way and silent, unlike a block. 404 MEMBER_NOT_FOUND for a member I cannot see.")
    public ResponseEntity<Void> hide(@PathVariable UUID memberId) {
        hidden.hide(CurrentUser.require(), memberId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{memberId}")
    @Operation(summary = "Show a hidden member again (idempotent)")
    public ResponseEntity<Void> unhide(@PathVariable UUID memberId) {
        hidden.unhide(CurrentUser.require(), memberId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping
    @Operation(summary = "Members I hid, most recent first")
    public HiddenMembersPage list(@RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size) {
        return hidden.list(CurrentUser.require(), page, size);
    }
}

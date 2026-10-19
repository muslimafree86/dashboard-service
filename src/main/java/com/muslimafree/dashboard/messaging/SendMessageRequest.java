package com.muslimafree.dashboard.messaging;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SendMessageRequest(
        @Schema(description = "اكتب رسالتك - plain text; clients must display it as text, never as HTML",
                example = "السلام عليكم و رحمة الله")
        @NotBlank @Size(max = 2000) String body) {
}

package com.muslimafree.dashboard.messaging;

import java.util.List;

public record ConversationsPage(List<ConversationSummary> items, int page, int size, boolean hasMore) {
}

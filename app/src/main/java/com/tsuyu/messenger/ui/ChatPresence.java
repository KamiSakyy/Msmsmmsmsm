package com.tsuyu.messenger.ui;

/** Tracks which chat is on screen, so the service can suppress its notification. */
public final class ChatPresence {
    public static volatile String activePeer;
    private ChatPresence() {}
}

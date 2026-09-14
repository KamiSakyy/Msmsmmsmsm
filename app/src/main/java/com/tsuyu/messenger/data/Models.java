package com.tsuyu.messenger.data;

import java.util.ArrayList;
import java.util.List;

public class Models {

    /** Message content types. */
    public static final String T_TEXT = "text";
    public static final String T_PHOTO = "photo";
    public static final String T_VIDEO = "video";
    public static final String T_VOICE = "voice";
    public static final String T_CIRCLE = "circle";
    public static final String T_AUDIO = "audio";
    public static final String T_ALBUM = "album";

    public static class User {
        public String uid;
        public String username;     // unique @
        public String name;
        public String bio;
        public String avatar;       // base64 (small) or null
        public String publicAvatar; // shown when privacy hides the real one
        public String ik, ed, spk, spkSig;
        public boolean online;
        public long lastSeen;
        public boolean ghost;

        // privacy: "all" | "none" | "contacts" | "username"
        public String pWrite = "all";
        public String pLastSeen = "all";
        public String pAvatar = "all";
        public String pBio = "all";
    }

    public static class Attachment {
        public String type;      // photo/video/voice/circle/audio
        public String data;      // base64 payload
        public String thumb;     // base64 preview for video
        public int width, height;
        public long durationMs;
        public String fileName;
        public String artist;
        public int[] waveform;
    }

    public static class Message {
        public String id;
        public String from;
        public String to;
        public long ts;
        public String type = T_TEXT;
        public String text;
        public List<Attachment> attachments = new ArrayList<>();
        public String replyTo;
        public String replyName;
        public String replyPreview;
        public boolean edited;
        public boolean read;
        public boolean deleted;
        public String forwardedFrom;
        /** uid -> true for heart reactions */
        public List<String> hearts = new ArrayList<>();
        /** true while the ciphertext could not (yet) be opened */
        public boolean failed;
        public boolean outgoing;
    }

    public static class Dialog {
        public String peerUid;
        public User peer;
        public Message last;
        public int unread;
        public boolean typing;
    }
}

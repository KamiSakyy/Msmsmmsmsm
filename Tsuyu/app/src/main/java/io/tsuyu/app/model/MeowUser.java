package io.tsuyu.app.model;

public class MeowUser {
    public String uid;
    public String username;   // display @handle
    public String firstName;
    public String lastName;
    public String name;       // full name
    public String bio;
    public String avatarB64;  // b64 JPEG (may be large)
    public boolean online;
    public Long lastSeen;
    public String typingIn;   // chatId this user is typing in
    public Long typingUntil;
    public boolean ghost;
    public String privacyWrite = "everyone";
    public String privacyLastSeen = "everyone";
    public String privacyPhoto = "everyone";
    public String privacyBio = "everyone";
    public Boolean notifyOn = true;
    public String notifySoundB64; // custom sound b64 (mp3)
    public String custom;         // encrypted customization JSON
    public String fp;             // fingerprint
    public String email;

    public String displayName() {
        if (name != null && !name.trim().isEmpty()) return name.trim();
        if (firstName != null && !firstName.trim().isEmpty()) return firstName.trim();
        return username != null ? "@" + username : "...";
    }

    public String letter() {
        String n = displayName();
        return n.isEmpty() ? "?" : String.valueOf(Character.toUpperCase(n.charAt(0)));
    }
}

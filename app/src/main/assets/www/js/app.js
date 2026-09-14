/**
 * Tsuyu Messenger - Main Application Controller
 * UI Interaction, RTDB Listeners, Double Ratchet Cryptography,
 * Realtime Presence & Typing, Media Players, Context Menus, Customization
 */

// Global State
let currentUser = null;
let currentPeer = null; // { uid, username, name, photo, bio, ... }
let currentChatId = null;
let dialogsData = [];
let messagesData = [];
let replyToMsg = null;
let editMsg = null;
let activeAudioPlayers = new Map();
let searchDebounceTimer = null;
let typingDebounceTimer = null;
let isGhostMode = localStorage.getItem("tsuyu_ghost_mode") === "true";
let isSoundEnabled = localStorage.getItem("tsuyu_sound_enabled") !== "false";
let customNotificationSoundB64 = localStorage.getItem("tsuyu_custom_sound") || null;

// Customization Config
let customConfig = JSON.parse(localStorage.getItem("tsuyu_custom_config") || JSON.stringify({
  fontSize: 15,
  fontStyle: "normal",
  fontName: "Системный шрифт",
  fontDataUrl: null,
  typingText: "печатает...",
  onlineText: "в сети",
  lastSeenText: "был(а) в {time}",
  lastSeenRecentText: "был(а) недавно",
  msgPlaceholder: "Сообщение..."
}));

// Privacy Config
let privacyConfig = JSON.parse(localStorage.getItem("tsuyu_privacy_config") || JSON.stringify({
  whoCanMessage: "all",
  whoCanSeeLastSeen: "all",
  whoCanSeePhoto: "all",
  whoCanSeeBio: "all"
}));

// --- App Initialization ---

document.addEventListener("DOMContentLoaded", async () => {
  applyCustomizationStyles();
  initPhotoViewerZoom();

  const savedUid = localStorage.getItem("tsuyu_uid");
  const savedToken = localStorage.getItem("tsuyu_id_token");

  if (savedUid && savedToken) {
    showUploadProgress("Синхронизация профиля и ключей E2EE...");
    try {
      const userProfile = await window.firebaseApp.dbGet(`users/${savedUid}`);
      if (userProfile && userProfile.uid) {
        currentUser = userProfile;
        const savedPass = localStorage.getItem("tsuyu_pass");
        if (savedPass) {
          await window.doubleRatchet.initUserCrypto(savedUid, savedPass);
        }
        enterApp();
        return;
      }
    } catch (e) {
      console.warn("Session restore failed:", e);
    } finally {
      hideUploadProgress();
    }
  }

  showAuthScreen();
});

function enterApp() {
  document.getElementById("loginScreen").classList.add("hidden");
  document.getElementById("dialogsScreen").classList.remove("hidden");
  updateDrawerUI();
  loadDialogsList();
  listenToGlobalMessages();
  listenToPresence();
  window.firebaseApp.startPresence(currentUser.uid, isGhostMode);

  // Inform Android Foreground Service
  if (window.AndroidBridge && window.AndroidBridge.startForegroundNotificationService) {
    window.AndroidBridge.startForegroundNotificationService(currentUser.uid);
  }
}

function showAuthScreen() {
  document.getElementById("loginScreen").classList.remove("hidden");
  document.getElementById("dialogsScreen").classList.add("hidden");
  document.getElementById("chatScreen").classList.add("hidden");
}

function switchAuthMode(mode) {
  if (mode === "signup") {
    document.getElementById("signInBox").classList.add("hidden");
    document.getElementById("signUpBox").classList.remove("hidden");
    document.getElementById("authSubtitle").textContent = "Регистрация нового пользователя Tsuyu с уникальным @username";
  } else {
    document.getElementById("signUpBox").classList.add("hidden");
    document.getElementById("signInBox").classList.remove("hidden");
    document.getElementById("authSubtitle").textContent = "Сквозное шифрование. RTDB реалтайм. Нулевой доступ сервера к вашим перепискам.";
  }
}

// --- Registration & Login ---

let regAvatarBase64 = "";

function previewRegAvatar(e) {
  const file = e.target.files[0];
  if (!file) return;
  window.mediaRecorderEngine.compressImageFile(file, 240, 0.8).then(res => {
    regAvatarBase64 = res.dataBase64;
    document.getElementById("regAvatarPreview").src = res.dataBase64;
  });
}

async function handleSignUp() {
  const name = document.getElementById("regName").value.trim();
  let username = document.getElementById("regUsername").value.trim().toLowerCase().replace(/^@/, "");
  const bio = document.getElementById("regBio").value.trim();
  const email = document.getElementById("regEmail").value.trim();
  const password = document.getElementById("regPassword").value.trim();

  if (!name || !username || !email || !password) {
    alert("Пожалуйста, заполните все обязательные поля");
    return;
  }

  if (username.length < 3) {
    alert("Юзернейм должен содержать не менее 3 символов");
    return;
  }

  showUploadProgress("Создание аккаунта...");
  try {
    // 1. Check if username is already taken in RTDB index
    const existingUser = await window.firebaseApp.dbGet(`usernames/${username}`);
    if (existingUser && existingUser.uid) {
      alert(`Юзернейм @${username} уже занят. Выберите другой!`);
      hideUploadProgress();
      return;
    }

    // 2. Firebase Auth signup
    const authRes = await window.firebaseApp.signUp(email, password);
    const uid = authRes.localId;

    // 3. Initialize Double Ratchet keys
    localStorage.setItem("tsuyu_pass", password);
    await window.doubleRatchet.initUserCrypto(uid, password, true);

    // 4. Encrypt zero-knowledge profile
    const profileData = {
      uid,
      name,
      username,
      bio,
      photo: regAvatarBase64 || "",
      createdAt: Date.now()
    };
    const zkProfile = await window.doubleRatchet.encryptProfileData(username, profileData);

    // 5. Store user and index in RTDB
    await window.firebaseApp.dbSet(`users/${uid}`, {
      ...profileData,
      zkProfile
    });
    await window.firebaseApp.dbSet(`usernames/${username}`, { uid, createdAt: Date.now() });

    currentUser = profileData;
    enterApp();
  } catch (err) {
    alert(err.message || "Ошибка при регистрации");
  } finally {
    hideUploadProgress();
  }
}

async function handleSignIn() {
  const email = document.getElementById("loginEmail").value.trim();
  const password = document.getElementById("loginPassword").value.trim();

  if (!email || !password) {
    alert("Введите почту и пароль");
    return;
  }

  showUploadProgress("Вход в Tsuyu...");
  try {
    const authRes = await window.firebaseApp.signIn(email, password);
    const uid = authRes.localId;

    localStorage.setItem("tsuyu_pass", password);
    await window.doubleRatchet.initUserCrypto(uid, password);

    let userProfile = await window.firebaseApp.dbGet(`users/${uid}`);
    if (!userProfile) {
      userProfile = { uid, email, name: email.split("@")[0], username: email.split("@")[0], photo: "" };
      await window.firebaseApp.dbSet(`users/${uid}`, userProfile);
    }

    currentUser = userProfile;
    enterApp();
  } catch (err) {
    alert(err.message || "Ошибка при входе");
  } finally {
    hideUploadProgress();
  }
}

function logout() {
  if (confirm("Выйти из аккаунта Tsuyu?")) {
    window.firebaseApp.setOffline(currentUser ? currentUser.uid : null, isGhostMode);
    window.firebaseApp.clearSession();
    localStorage.removeItem("tsuyu_pass");
    currentUser = null;
    currentPeer = null;
    showAuthScreen();
    closeDrawer();
  }
}

// --- Drawer & Profile Editing ---

function openDrawer() {
  updateDrawerUI();
  document.getElementById("drawerOverlay").classList.add("active");
  document.getElementById("drawer").classList.add("active");
}

function closeDrawer() {
  document.getElementById("drawerOverlay").classList.remove("active");
  document.getElementById("drawer").classList.remove("active");
}

function updateDrawerUI() {
  if (!currentUser) return;
  document.getElementById("drawerName").textContent = currentUser.name || "Пользователь Tsuyu";
  document.getElementById("drawerUsername").textContent = `@${currentUser.username || "username"}`;
  document.getElementById("drawerStatusText").textContent = currentUser.bio || "Нажмите, чтобы изменить описание...";

  const avatar = document.getElementById("drawerAvatar");
  avatar.src = currentUser.photo || "data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' width='60' height='60' fill='%23222'><rect width='60' height='60' rx='30'/></svg>";

  document.getElementById("ghostModeToggle").checked = isGhostMode;
  document.getElementById("soundToggle").checked = isSoundEnabled;
  updateCacheSizeDisplay();
}

function openProfileEditModal() {
  if (!currentUser) return;
  document.getElementById("editName").value = currentUser.name || "";
  document.getElementById("editUsername").value = currentUser.username || "";
  document.getElementById("editBio").value = currentUser.bio || "";
  document.getElementById("profileModal").classList.remove("hidden");
}

function closeProfileModal() {
  document.getElementById("profileModal").classList.add("hidden");
}

async function saveProfileChanges() {
  const newName = document.getElementById("editName").value.trim();
  const newUsername = document.getElementById("editUsername").value.trim().toLowerCase().replace(/^@/, "");
  const newBio = document.getElementById("editBio").value.trim();

  if (!newName || !newUsername) {
    alert("Имя и юзернейм обязательны");
    return;
  }

  showUploadProgress("Сохранение...");
  try {
    // If username changed, check uniqueness
    if (newUsername !== currentUser.username) {
      const existing = await window.firebaseApp.dbGet(`usernames/${newUsername}`);
      if (existing && existing.uid && existing.uid !== currentUser.uid) {
        alert("Этот юзернейм уже занят!");
        hideUploadProgress();
        return;
      }
      // Remove old username index
      if (currentUser.username) {
        await window.firebaseApp.dbDelete(`usernames/${currentUser.username}`);
      }
      await window.firebaseApp.dbSet(`usernames/${newUsername}`, { uid: currentUser.uid, createdAt: Date.now() });
    }

    currentUser.name = newName;
    currentUser.username = newUsername;
    currentUser.bio = newBio;

    const zkProfile = await window.doubleRatchet.encryptProfileData(newUsername, currentUser);
    await window.firebaseApp.dbUpdate(`users/${currentUser.uid}`, {
      name: newName,
      username: newUsername,
      bio: newBio,
      zkProfile,
      updatedAt: Date.now()
    });

    closeProfileModal();
    updateDrawerUI();
  } catch (e) {
    alert("Ошибка при сохранении профиля");
  } finally {
    hideUploadProgress();
  }
}

function triggerAvatarSelect() {
  document.getElementById("avatarFileInput").click();
}

async function uploadAvatarFile(e) {
  const file = e.target.files[0];
  if (!file) return;
  showUploadProgress("Обновление аватара...");
  try {
    const res = await window.mediaRecorderEngine.compressImageFile(file, 240, 0.82);
    currentUser.photo = res.dataBase64;
    const zkProfile = await window.doubleRatchet.encryptProfileData(currentUser.username, currentUser);
    await window.firebaseApp.dbUpdate(`users/${currentUser.uid}`, {
      photo: res.dataBase64,
      zkProfile,
      updatedAt: Date.now()
    });
    updateDrawerUI();
  } catch (e) {
    alert("Ошибка загрузки аватара");
  } finally {
    hideUploadProgress();
    e.target.value = "";
  }
}

// --- Ghost Mode & Sound Notifications ---

function toggleGhostMode() {
  isGhostMode = !isGhostMode;
  setGhostModeState(isGhostMode);
}

function setGhostModeState(val) {
  isGhostMode = val;
  localStorage.setItem("tsuyu_ghost_mode", val ? "true" : "false");
  document.getElementById("ghostModeToggle").checked = val;
  if (val) {
    window.firebaseApp.setOffline(currentUser ? currentUser.uid : null, true);
  } else {
    window.firebaseApp.startPresence(currentUser ? currentUser.uid : null, false);
  }
}

function toggleSoundNotifications() {
  isSoundEnabled = !isSoundEnabled;
  setSoundState(isSoundEnabled);
}

function setSoundState(val) {
  isSoundEnabled = val;
  localStorage.setItem("tsuyu_sound_enabled", val ? "true" : "false");
  document.getElementById("soundToggle").checked = val;
}

function handleCustomSoundUpload(e) {
  const file = e.target.files[0];
  if (!file) return;
  const reader = new FileReader();
  reader.onload = () => {
    customNotificationSoundB64 = reader.result;
    localStorage.setItem("tsuyu_custom_sound", customNotificationSoundB64);
    document.getElementById("customSoundLabel").textContent = `Свой звук (${file.name.substring(0, 16)}...)`;
    playNotificationSound();
  };
  reader.readAsDataURL(file);
}

function playNotificationSound() {
  if (!isSoundEnabled) return;

  // If Android Native Bridge is present, let native MediaPlayer play
  if (window.AndroidBridge && window.AndroidBridge.playNotificationSound) {
    window.AndroidBridge.playNotificationSound();
    return;
  }

  try {
    const audio = customNotificationSoundB64 ? new Audio(customNotificationSoundB64) : document.getElementById("notifSound");
    if (audio) {
      audio.currentTime = 0;
      audio.play().catch(() => {});
    }
  } catch (e) {}
}

// --- Realtime Global Search by @username ---

async function handleGlobalSearchInput() {
  const query = document.getElementById("dialogSearchInput").value.trim().toLowerCase();
  const searchResultsList = document.getElementById("searchResultsList");

  if (!query) {
    searchResultsList.classList.add("hidden");
    searchResultsList.innerHTML = "";
    return;
  }

  if (searchDebounceTimer) clearTimeout(searchDebounceTimer);
  searchDebounceTimer = setTimeout(async () => {
    searchResultsList.innerHTML = `<div style="padding:12px;font-size:13px;color:#888;text-align:center">Поиск...</div>`;
    searchResultsList.classList.remove("hidden");

    try {
      const cleanQ = query.replace(/^@/, "");
      // Fetch all usernames starting with query or matching exactly
      const allUsernames = await window.firebaseApp.dbGet("usernames");
      const matchedUids = [];

      if (allUsernames) {
        for (const [uName, val] of Object.entries(allUsernames)) {
          if (uName.toLowerCase().includes(cleanQ) && val.uid !== (currentUser ? currentUser.uid : null)) {
            matchedUids.push(val.uid);
          }
        }
      }

      if (matchedUids.length === 0) {
        searchResultsList.innerHTML = `<div style="padding:12px;font-size:13px;color:#888;text-align:center">Ничего не найдено</div>`;
        return;
      }

      searchResultsList.innerHTML = "";
      for (const peerUid of matchedUids.slice(0, 10)) {
        const peer = await window.firebaseApp.dbGet(`users/${peerUid}`);
        if (peer) {
          renderSearchResultItem(searchResultsList, peer);
        }
      }
    } catch (e) {
      console.error("Search error:", e);
    }
  }, 250);
}

function renderSearchResultItem(container, peer) {
  const item = document.createElement("div");
  item.className = "dialog";
  item.onclick = () => {
    document.getElementById("searchResultsList").classList.add("hidden");
    document.getElementById("dialogSearchInput").value = "";
    openChatWithPeer(peer);
  };

  const avatarSrc = peer.photo || "data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' width='50' height='50' fill='%23222'><rect width='50' height='50' rx='25'/></svg>";
  item.innerHTML = `
    <div class="dialog-avatar-wrap">
      <img class="dialog-avatar" src="${avatarSrc}" alt="">
      <div class="dialog-online-dot hidden" id="onlineDot_search_${peer.uid}"></div>
    </div>
    <div class="dialog-info">
      <div class="dialog-top">
        <div class="dialog-name">${escapeHtml(peer.name || peer.username)}</div>
        <div class="ratchet-badge"><svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><rect x="3" y="11" width="18" height="11" rx="2"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/></svg>E2EE</div>
      </div>
      <div class="dialog-bottom">
        <div class="dialog-preview" style="color:#0a84ff">@${escapeHtml(peer.username)}</div>
      </div>
    </div>
  `;
  container.appendChild(item);
  checkPeerOnlineStatus(peer.uid, `onlineDot_search_${peer.uid}`);
}

// --- Dialogs List Management ---

async function loadDialogsList() {
  if (!currentUser) return;
  const dialogsList = document.getElementById("dialogsList");
  dialogsList.innerHTML = `<div style="padding:20px;text-align:center;color:#666">Загрузка диалогов...</div>`;

  window.firebaseApp.dbListen(`dialogs/${currentUser.uid}`, async (path, data) => {
    if (!data) {
      dialogsList.innerHTML = `
        <div style="padding:40px 20px;text-align:center;color:#888">
          <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="#444" stroke-width="1.5" style="margin-bottom:12px"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/></svg>
          <div style="font-size:16px;font-weight:600;color:#fff;margin-bottom:6px">У вас пока нет сообщений</div>
          <div style="font-size:13px">Найдите собеседника по @юзернейму в строке поиска выше</div>
        </div>
      `;
      return;
    }

    dialogsData = Object.values(data).sort((a, b) => (b.lastTime || 0) - (a.lastTime || 0));
    renderDialogs();
  });
}

async function renderDialogs() {
  const dialogsList = document.getElementById("dialogsList");
  dialogsList.innerHTML = "";

  for (const d of dialogsData) {
    const peer = await window.firebaseApp.dbGet(`users/${d.peerUid}`);
    if (!peer) continue;

    const div = document.createElement("div");
    div.className = "dialog";
    div.id = `dialog_item_${d.peerUid}`;
    div.onclick = () => openChatWithPeer(peer);

    const avatarSrc = peer.photo || "data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' width='50' height='50' fill='%23222'><rect width='50' height='50' rx='25'/></svg>";
    const timeStr = formatRelativeTime(d.lastTime || Date.now());

    div.innerHTML = `
      <div class="dialog-avatar-wrap">
        <img class="dialog-avatar" src="${avatarSrc}" alt="">
        <div class="dialog-online-dot hidden" id="onlineDot_${d.peerUid}"></div>
      </div>
      <div class="dialog-info">
        <div class="dialog-top">
          <div class="dialog-name">${escapeHtml(peer.name || peer.username)}</div>
          <div class="dialog-time">${timeStr}</div>
        </div>
        <div class="dialog-bottom">
          <div class="dialog-preview" id="dialogPreview_${d.peerUid}">${buildDialogPreviewText(d)}</div>
          ${d.unreadCount > 0 ? `<div class="dialog-unread-blue">${d.unreadCount}</div>` : ""}
        </div>
      </div>
    `;

    dialogsList.appendChild(div);
    checkPeerOnlineStatus(d.peerUid, `onlineDot_${d.peerUid}`);
    listenToDialogTyping(d.peerUid);
  }
}

function buildDialogPreviewText(d) {
  if (d.lastType === "photo") {
    return `<div class="dialog-preview-wrap"><span class="dialog-preview-thumb">📷</span> Фотография</div>`;
  } else if (d.lastType === "video") {
    return `<div class="dialog-preview-wrap"><span class="dialog-preview-thumb">🎥</span> Видеозапись</div>`;
  } else if (d.lastType === "circle") {
    return `<div class="dialog-preview-wrap"><span class="dialog-preview-thumb">📹</span> Видеосообщение</div>`;
  } else if (d.lastType === "voice") {
    return `<div class="dialog-preview-wrap"><span class="dialog-preview-thumb">🎙</span> Голосовое сообщение</div>`;
  } else if (d.lastType === "audio") {
    return `<div class="dialog-preview-wrap"><span class="dialog-preview-thumb">🎵</span> Аудиозапись</div>`;
  }
  return escapeHtml(d.lastText || "Зашифрованное сообщение...");
}

function showDialogsTab() {
  document.getElementById("chatScreen").classList.add("hidden");
  document.getElementById("dialogsScreen").classList.remove("hidden");
  currentPeer = null;
  currentChatId = null;
}

// --- Chat Screen Operations ---

function getChatId(uid1, uid2) {
  return [uid1, uid2].sort().join("_");
}

async function openChatWithPeer(peer) {
  currentPeer = peer;
  currentChatId = getChatId(currentUser.uid, peer.uid);

  document.getElementById("dialogsScreen").classList.add("hidden");
  document.getElementById("chatScreen").classList.remove("hidden");

  document.getElementById("chatTitle").textContent = peer.name || peer.username;
  document.getElementById("chatAvatar").src = peer.photo || "data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' width='40' height='40' fill='%23222'><rect width='40' height='40' rx='20'/></svg>";
  document.getElementById("msgInput").placeholder = customConfig.msgPlaceholder || "Сообщение...";

  cancelReplyOrEdit();
  loadChatMessages();
  listenToChatTyping();
  checkPeerChatSubtitle();
}

function backToDialogs() {
  showDialogsTab();
  if (currentChatId) {
    window.firebaseApp.dbUnlisten(`chats/${currentChatId}/messages`);
    window.firebaseApp.dbUnlisten(`typing/${currentChatId}`);
  }
}

// --- Messages Realtime Engine ---

function loadChatMessages() {
  const container = document.getElementById("messages");
  container.innerHTML = `<div style="padding:20px;text-align:center;color:#666">Расшифровка Signal Double Ratchet...</div>`;

  window.firebaseApp.dbListen(`chats/${currentChatId}/messages`, async (path, data) => {
    if (!data) {
      container.innerHTML = `
        <div style="padding:40px 20px;text-align:center;color:#888">
          <div class="badge-e2e" style="margin:0 auto 12px">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><rect x="3" y="11" width="18" height="11" rx="2"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/></svg>
            Signal Double Ratchet E2EE
          </div>
          <div style="font-size:14px;color:#aaa">Сообщения в этом чате защищены сквозным шифрованием.</div>
        </div>
      `;
      return;
    }

    const msgs = Object.values(data).sort((a, b) => (a.timestamp || 0) - (b.timestamp || 0));
    messagesData = msgs;
    container.innerHTML = "";

    for (const msg of msgs) {
      await renderSingleMessage(container, msg);
    }

    scrollToBottomChat();
  });
}

async function renderSingleMessage(container, msg) {
  const isOut = msg.fromId === currentUser.uid;
  const containerDiv = document.createElement("div");
  containerDiv.className = "msg-container";
  containerDiv.id = `msg_container_${msg.id}`;
  containerDiv.style.justifyContent = isOut ? "flex-end" : "flex-start";

  // Decrypt payload with Double Ratchet
  let decrypted = null;
  if (msg.encPayload) {
    try {
      decrypted = await window.doubleRatchet.decryptMessage(isOut ? msg.toId : msg.fromId, msg.encPayload);
    } catch (e) {
      decrypted = { text: "⚠️ Не удалось расшифровать сообщение" };
    }
  } else {
    decrypted = { text: msg.text || "" };
  }

  const msgDiv = document.createElement("div");
  msgDiv.className = `msg ${isOut ? "msg-out" : "msg-in"}`;
  msgDiv.id = `msg_${msg.id}`;

  // Double tap heart reaction
  let lastTap = 0;
  msgDiv.addEventListener("touchend", (e) => {
    const now = Date.now();
    if (now - lastTap < 280) {
      triggerMessageReaction("❤️", msg.id);
    }
    lastTap = now;
  });

  // Long press / 3-dots context menu
  msgDiv.addEventListener("contextmenu", (e) => {
    e.preventDefault();
    openMsgContextMenu(msg, decrypted);
  });

  // Build message content HTML
  let contentHTML = "";

  // Reply Quote if any
  if (msg.replyTo) {
    contentHTML += `
      <div class="msg-reply-quote" onclick="scrollToMessage('${msg.replyTo.id}')">
        <div class="msg-reply-name">${escapeHtml(msg.replyTo.name)}</div>
        <div class="msg-reply-text">${escapeHtml(msg.replyTo.text)}</div>
      </div>
    `;
  }

  // Media contents: Photo, Collage, Video, Circle, Voice, Music
  if (decrypted && decrypted.media) {
    contentHTML += renderDecryptedMediaBlock(decrypted.media, msg.id);
  }

  if (decrypted && decrypted.text) {
    contentHTML += `<div class="msg-text">${escapeHtml(decrypted.text)}</div>`;
  }

  // Time & Status Checkmarks
  const timeFormatted = formatTime(msg.timestamp || Date.now());
  contentHTML += `
    <div class="msg-time">
      ${msg.edited ? '<span style="font-size:10px;margin-right:3px;color:#8e8e93">изм.</span>' : ""}
      ${timeFormatted}
      ${isOut ? `<svg class="msg-status-sent" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#fff" stroke-width="2.5"><polyline points="20 6 9 17 4 12"/></svg>` : ""}
    </div>
  `;

  // Heart Reactions Badge
  if (msg.reactions && Object.keys(msg.reactions).length > 0) {
    contentHTML += buildReactionsBadgeHTML(msg.reactions);
  }

  msgDiv.innerHTML = contentHTML;
  containerDiv.appendChild(msgDiv);
  container.appendChild(containerDiv);
}

function renderDecryptedMediaBlock(media, msgId) {
  if (media.type === "photo") {
    return `<img class="msg-photo" src="${media.dataBase64}" onclick="openPhotoViewer('${media.dataBase64}')" alt="">`;
  } else if (media.type === "collage" && Array.isArray(media.items)) {
    const count = media.items.length;
    const gridClass = count === 2 ? "tg-photo-grid-2" : (count === 3 ? "tg-photo-grid-3" : "tg-photo-grid-4");
    let gridHTML = `<div class="photo-grid ${gridClass}">`;
    for (const item of media.items) {
      gridHTML += `<img src="${item.dataBase64}" onclick="openPhotoViewer('${item.dataBase64}')" alt="">`;
    }
    gridHTML += `</div>`;
    return gridHTML;
  } else if (media.type === "video") {
    return `
      <div class="msg-video" style="position:relative">
        <video id="vid_${msgId}" src="${media.dataBase64}" poster="${media.thumbBase64 || ''}" playsinline controls style="max-width:100%;border-radius:12px"></video>
      </div>
    `;
  } else if (media.type === "circle") {
    return `
      <div class="tg-circle-container">
        <video class="tg-circle-video" src="${media.dataBase64}" playsinline loop onclick="togglePlayCircleVideo(this)"></video>
        <div class="tg-circle-overlay"></div>
      </div>
    `;
  } else if (media.type === "voice") {
    return buildVoicePlayerHTML(media, msgId);
  } else if (media.type === "audio") {
    return buildMusicPlayerHTML(media, msgId);
  }
  return "";
}

function buildVoicePlayerHTML(media, msgId) {
  const durationStr = formatSecondsToMMSS(media.duration || 1);
  let wavesHTML = "";
  const peaks = media.waveform || Array(30).fill(0.3);
  for (let i = 0; i < peaks.length; i++) {
    const h = Math.round(Math.max(4, peaks[i] * 24));
    wavesHTML += `<div class="tg-voice-bar" id="vbar_${msgId}_${i}" style="height:${h}px"></div>`;
  }

  return `
    <div class="tg-voice-container" id="voice_cont_${msgId}">
      <button class="tg-voice-play-btn" onclick="toggleVoiceAudioPlay('${msgId}', '${media.dataBase64}')">
        <svg id="vicon_${msgId}" width="16" height="16" viewBox="0 0 24 24" fill="currentColor"><polygon points="5 3 19 12 5 21 5 3"/></svg>
      </button>
      <div class="tg-voice-wave-wrap">
        <div class="tg-voice-waveform">${wavesHTML}</div>
        <div class="tg-voice-info">
          <span id="vtime_${msgId}">${durationStr}</span>
          <span class="voice-speed-badge" onclick="toggleAudioSpeed('${msgId}')">1X</span>
        </div>
      </div>
    </div>
  `;
}

function buildMusicPlayerHTML(media, msgId) {
  const durationStr = formatSecondsToMMSS(media.duration || 1);
  return `
    <div class="tg-music-container">
      <button class="tg-music-play-btn" onclick="toggleMusicAudioPlay('${msgId}', '${media.dataBase64}')">
        <svg id="micon_${msgId}" width="16" height="16" viewBox="0 0 24 24" fill="currentColor"><polygon points="5 3 19 12 5 21 5 3"/></svg>
      </button>
      <div class="tg-music-info">
        <div class="tg-music-title">${escapeHtml(media.name || "Аудиозапись")}</div>
        <div class="tg-music-progress" onclick="seekMusicPlayer(event, '${msgId}')">
          <div class="tg-music-progress-bar" id="mprog_${msgId}"></div>
        </div>
        <div class="tg-music-time" id="mtime_${msgId}">0:00 / ${durationStr}</div>
      </div>
    </div>
  `;
}

function buildReactionsBadgeHTML(reactions) {
  const users = Object.values(reactions);
  let avatarsHTML = "";
  for (const u of users.slice(0, 3)) {
    const photo = u.photo || "data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' width='14' height='14' fill='%23555'><rect width='14' height='14' rx='7'/></svg>";
    avatarsHTML += `<img class="msg-reaction-avatar" src="${photo}" alt="">`;
  }

  return `
    <div class="msg-reaction-badge">
      <span class="reaction-heart">❤️</span>
      <div class="msg-reaction-avatars">${avatarsHTML}</div>
    </div>
  `;
}

// --- Audio Playback Controllers ---

function toggleVoiceAudioPlay(msgId, dataBase64) {
  let audio = activeAudioPlayers.get(msgId);
  const icon = document.getElementById(`vicon_${msgId}`);

  if (audio && !audio.paused) {
    audio.pause();
    if (icon) icon.innerHTML = `<polygon points="5 3 19 12 5 21 5 3"/>`;
    return;
  }

  if (!audio) {
    audio = new Audio(dataBase64);
    activeAudioPlayers.set(msgId, audio);

    audio.ontimeupdate = () => {
      const timeElem = document.getElementById(`vtime_${msgId}`);
      if (timeElem) timeElem.textContent = formatSecondsToMMSS(audio.currentTime);
    };

    audio.onended = () => {
      if (icon) icon.innerHTML = `<polygon points="5 3 19 12 5 21 5 3"/>`;
    };
  }

  audio.play().catch(() => {});
  if (icon) icon.innerHTML = `<rect x="6" y="4" width="4" height="16"/><rect x="14" y="4" width="4" height="16"/>`;
}

function toggleMusicAudioPlay(msgId, dataBase64) {
  let audio = activeAudioPlayers.get(msgId);
  const icon = document.getElementById(`micon_${msgId}`);

  if (audio && !audio.paused) {
    audio.pause();
    if (icon) icon.innerHTML = `<polygon points="5 3 19 12 5 21 5 3"/>`;
    return;
  }

  if (!audio) {
    audio = new Audio(dataBase64);
    activeAudioPlayers.set(msgId, audio);

    audio.ontimeupdate = () => {
      const prog = document.getElementById(`mprog_${msgId}`);
      const timeElem = document.getElementById(`mtime_${msgId}`);
      if (audio.duration) {
        const pct = (audio.currentTime / audio.duration) * 100;
        if (prog) prog.style.width = `${pct}%`;
        if (timeElem) timeElem.textContent = `${formatSecondsToMMSS(audio.currentTime)} / ${formatSecondsToMMSS(audio.duration)}`;
      }
    };

    audio.onended = () => {
      if (icon) icon.innerHTML = `<polygon points="5 3 19 12 5 21 5 3"/>`;
    };
  }

  audio.play().catch(() => {});
  if (icon) icon.innerHTML = `<rect x="6" y="4" width="4" height="16"/><rect x="14" y="4" width="4" height="16"/>`;
}

function togglePlayCircleVideo(videoElem) {
  if (videoElem.paused) {
    videoElem.muted = false;
    videoElem.play().catch(() => {});
  } else {
    videoElem.pause();
  }
}

// --- Sending Messages & Media ---

async function handleSendMessage() {
  const input = document.getElementById("msgInput");
  const text = input.value.trim();

  if (!text && !editMsg) return;

  if (editMsg) {
    // Edit existing message
    showUploadProgress("Редактирование...");
    try {
      const enc = await window.doubleRatchet.encryptMessage(currentPeer.uid, { text });
      await window.firebaseApp.dbUpdate(`chats/${currentChatId}/messages/${editMsg.id}`, {
        encPayload: enc.fullEncryptedPayload,
        edited: true,
        updatedAt: Date.now()
      });
      cancelReplyOrEdit();
    } catch (e) {
      alert("Ошибка при редактировании");
    } finally {
      hideUploadProgress();
    }
    return;
  }

  input.value = "";
  input.style.height = "auto";
  document.getElementById("sendBtn").classList.add("hidden");
  document.getElementById("voiceRecBtn").classList.remove("hidden");
  document.getElementById("circleRecBtn").classList.remove("hidden");

  await dispatchMessagePayload({ text });
}

async function dispatchMessagePayload(payloadObj, previewType = "text") {
  if (!currentPeer || !currentUser) return;
  const msgId = `msg_${currentUser.uid}_${Date.now()}_${Math.floor(Math.random()*1000)}`;

  try {
    // Encrypt using Signal Double Ratchet
    const encResult = await window.doubleRatchet.encryptMessage(currentPeer.uid, payloadObj);

    const msgObj = {
      id: msgId,
      fromId: currentUser.uid,
      toId: currentPeer.uid,
      encPayload: encResult.fullEncryptedPayload,
      replyTo: replyToMsg ? { id: replyToMsg.id, text: replyToMsg.text, name: replyToMsg.name } : null,
      timestamp: Date.now(),
      status: "sent"
    };

    cancelReplyOrEdit();

    // 1. Write message to RTDB
    await window.firebaseApp.dbSet(`chats/${currentChatId}/messages/${msgId}`, msgObj);

    // 2. Update Dialogs for both users
    const dialogPreview = previewType === "text" ? (payloadObj.text || "Сообщение") : "";
    await window.firebaseApp.dbSet(`dialogs/${currentUser.uid}/${currentPeer.uid}`, {
      peerUid: currentPeer.uid,
      lastText: dialogPreview,
      lastType: previewType,
      lastTime: Date.now(),
      unreadCount: 0
    });

    await window.firebaseApp.dbSet(`dialogs/${currentPeer.uid}/${currentUser.uid}`, {
      peerUid: currentUser.uid,
      lastText: dialogPreview,
      lastType: previewType,
      lastTime: Date.now(),
      unreadCount: 1
    });

    playNotificationSound();
  } catch (e) {
    console.error("Message send failed:", e);
    alert("Ошибка при отправке зашифрованного сообщения: " + e.message);
  }
}

// --- Attachment Handlers ---

async function handleFileInput(e) {
  const files = Array.from(e.target.files);
  if (!files || files.length === 0) return;

  showUploadProgress("Обработка файлов...");
  try {
    if (files.length === 1) {
      const file = files[0];
      if (file.type.startsWith("image/")) {
        const compressed = await window.mediaRecorderEngine.compressImageFile(file, 1280, 0.82);
        await dispatchMessagePayload({ media: { type: "photo", dataBase64: compressed.dataBase64 } }, "photo");
      } else if (file.type.startsWith("video/")) {
        const thumb = await window.mediaRecorderEngine.extractVideoThumbnail(file);
        const base64 = await window.mediaRecorderEngine.blobToBase64(file);
        await dispatchMessagePayload({
          media: { type: "video", dataBase64: base64, thumbBase64: thumb.thumbBase64, duration: thumb.duration }
        }, "video");
      } else if (file.type.startsWith("audio/")) {
        const base64 = await window.mediaRecorderEngine.blobToBase64(file);
        await dispatchMessagePayload({
          media: { type: "audio", name: file.name, dataBase64: base64, duration: 60 }
        }, "audio");
      }
    } else {
      // Multiple items -> Collage
      const items = [];
      for (const file of files) {
        if (file.type.startsWith("image/")) {
          const comp = await window.mediaRecorderEngine.compressImageFile(file, 800, 0.75);
          items.push({ type: "photo", dataBase64: comp.dataBase64 });
        }
      }
      if (items.length > 0) {
        await dispatchMessagePayload({ media: { type: "collage", items } }, "photo");
      }
    }
  } catch (err) {
    alert("Ошибка при прикреплении файла");
  } finally {
    hideUploadProgress();
    e.target.value = "";
  }
}

// --- Voice & Video Circle Handlers ---

function triggerVoiceRecording() {
  window.mediaRecorderEngine.startVoiceRecording();
}

function cancelVoiceRecording() {
  window.mediaRecorderEngine.cancelVoiceRecording();
}

function stopAndSendVoiceRecording() {
  window.mediaRecorderEngine.stopAndSendVoiceRecording((voiceData) => {
    dispatchMessagePayload({ media: voiceData }, "voice");
  });
}

function triggerCircleRecording() {
  window.mediaRecorderEngine.startCircleRecording();
}

function stopAndSendCircleRecording() {
  window.mediaRecorderEngine.stopAndSendCircleRecording((circleData) => {
    dispatchMessagePayload({ media: circleData }, "circle");
  });
}

// --- Realtime Typing Tracker (1 Char / 1 Sec) ---

function handleInputTyping(e) {
  const input = document.getElementById("msgInput");
  input.style.height = "auto";
  input.style.height = Math.min(input.scrollHeight, 120) + "px";

  const hasText = input.value.trim().length > 0;
  document.getElementById("sendBtn").classList.toggle("hidden", !hasText);
  document.getElementById("voiceRecBtn").classList.toggle("hidden", hasText);
  document.getElementById("circleRecBtn").classList.toggle("hidden", hasText);

  if (isGhostMode || !currentChatId) return;

  if (typingDebounceTimer) clearTimeout(typingDebounceTimer);
  window.firebaseApp.dbSet(`typing/${currentChatId}/${currentUser.uid}`, {
    typing: true,
    text: customConfig.typingText || "печатает...",
    timestamp: Date.now()
  });

  typingDebounceTimer = setTimeout(() => {
    window.firebaseApp.dbDelete(`typing/${currentChatId}/${currentUser.uid}`);
  }, 2500);
}

function listenToChatTyping() {
  if (!currentChatId || !currentPeer) return;
  window.firebaseApp.dbListen(`typing/${currentChatId}/${currentPeer.uid}`, (path, data) => {
    const subtitle = document.getElementById("chatSubtitle");
    if (data && data.typing && (Date.now() - (data.timestamp || 0) < 3000)) {
      subtitle.textContent = data.text || customConfig.typingText || "печатает...";
      subtitle.style.color = "#0a84ff";
    } else {
      checkPeerChatSubtitle();
    }
  });
}

function listenToDialogTyping(peerUid) {
  const chatId = getChatId(currentUser.uid, peerUid);
  window.firebaseApp.dbListen(`typing/${chatId}/${peerUid}`, (path, data) => {
    const preview = document.getElementById(`dialogPreview_${peerUid}`);
    if (preview) {
      if (data && data.typing && (Date.now() - (data.timestamp || 0) < 3000)) {
        preview.textContent = data.text || customConfig.typingText || "печатает...";
        preview.style.color = "#0a84ff";
      }
    }
  });
}

// --- Presence & Online Status Indicators ---

function checkPeerOnlineStatus(peerUid, dotElemId) {
  window.firebaseApp.dbListen(`presence/${peerUid}`, (path, data) => {
    const dot = document.getElementById(dotElemId);
    if (!dot) return;
    if (data && data.online && !data.ghost) {
      dot.classList.remove("hidden");
    } else {
      dot.classList.add("hidden");
    }
  });
}

async function checkPeerChatSubtitle() {
  if (!currentPeer) return;
  const subtitle = document.getElementById("chatSubtitle");
  subtitle.style.color = "#8e8e93";

  try {
    const pres = await window.firebaseApp.dbGet(`presence/${currentPeer.uid}`);
    if (pres && pres.online && !pres.ghost) {
      subtitle.textContent = customConfig.onlineText || "в сети";
    } else if (pres && pres.lastSeen && !pres.ghost) {
      const timeStr = formatTime(pres.lastSeen);
      const template = customConfig.lastSeenText || "был(а) в {time}";
      subtitle.textContent = template.replace("{time}", timeStr);
    } else {
      subtitle.textContent = customConfig.lastSeenRecentText || "был(а) недавно";
    }
  } catch (e) {
    subtitle.textContent = "был(а) недавно";
  }
}

function listenToPresence() {
  window.firebaseApp.dbListen("presence", (path, data) => {
    if (currentPeer && path.includes(currentPeer.uid)) {
      checkPeerChatSubtitle();
    }
  });
}

// --- Global Incoming Messages Listener ---

function listenToGlobalMessages() {
  window.firebaseApp.dbListen(`dialogs/${currentUser.uid}`, (path, data) => {
    // Notify on new messages
  });
}

// --- Message Context Menu, Reactions & Actions ---

let selectedContextMsg = null;
let selectedDecryptedPayload = null;

function openMsgContextMenu(msg, decrypted) {
  selectedContextMsg = msg;
  selectedDecryptedPayload = decrypted;

  const isOwn = msg.fromId === currentUser.uid;
  document.getElementById("contextEditItem").style.display = isOwn ? "flex" : "none";
  document.getElementById("msgContextOverlay").classList.remove("hidden");
}

function closeMsgContext() {
  document.getElementById("msgContextOverlay").classList.add("hidden");
  selectedContextMsg = null;
  selectedDecryptedPayload = null;
}

async function triggerMessageReaction(emoji, msgId = null) {
  const targetId = msgId || (selectedContextMsg ? selectedContextMsg.id : null);
  if (!targetId || !currentChatId || !currentUser) return;

  closeMsgContext();
  const reactionObj = {
    emoji,
    uid: currentUser.uid,
    name: currentUser.name || "User",
    photo: currentUser.photo || "",
    timestamp: Date.now()
  };

  await window.firebaseApp.dbSet(`chats/${currentChatId}/messages/${targetId}/reactions/${currentUser.uid}`, reactionObj);
}

function triggerReplyFromContext() {
  if (!selectedContextMsg) return;
  replyToMsg = {
    id: selectedContextMsg.id,
    text: selectedDecryptedPayload ? (selectedDecryptedPayload.text || "Медиафайл") : "Сообщение",
    name: selectedContextMsg.fromId === currentUser.uid ? "Вы" : (currentPeer ? currentPeer.name : "Собеседник")
  };

  document.getElementById("replyPreviewTitle").textContent = `Ответ для ${replyToMsg.name}`;
  document.getElementById("replyPreviewText").textContent = replyToMsg.text;
  document.getElementById("replyPreviewBar").classList.remove("hidden");
  closeMsgContext();
  document.getElementById("msgInput").focus();
}

function triggerEditFromContext() {
  if (!selectedContextMsg) return;
  editMsg = selectedContextMsg;
  const input = document.getElementById("msgInput");
  input.value = selectedDecryptedPayload ? (selectedDecryptedPayload.text || "") : "";
  input.focus();

  document.getElementById("replyPreviewTitle").textContent = "Редактирование";
  document.getElementById("replyPreviewText").textContent = input.value;
  document.getElementById("replyPreviewBar").classList.remove("hidden");
  document.getElementById("sendBtn").classList.remove("hidden");
  document.getElementById("voiceRecBtn").classList.add("hidden");
  document.getElementById("circleRecBtn").classList.add("hidden");
  closeMsgContext();
}

function cancelReplyOrEdit() {
  replyToMsg = null;
  editMsg = null;
  document.getElementById("replyPreviewBar").classList.add("hidden");
  const input = document.getElementById("msgInput");
  input.value = "";
  document.getElementById("sendBtn").classList.add("hidden");
  document.getElementById("voiceRecBtn").classList.remove("hidden");
  document.getElementById("circleRecBtn").classList.remove("hidden");
}

function triggerDeleteFromContext() {
  closeMsgContext();
  document.getElementById("deleteModal").classList.remove("hidden");
}

function closeDeleteModal() {
  document.getElementById("deleteModal").classList.add("hidden");
}

async function confirmDeleteMessage() {
  if (!selectedContextMsg || !currentChatId) return;
  const deleteForAll = document.getElementById("deleteForAllCheck").checked;
  const targetId = selectedContextMsg.id;

  showUploadProgress("Удаление...");
  try {
    if (deleteForAll) {
      await window.firebaseApp.dbDelete(`chats/${currentChatId}/messages/${targetId}`);
    } else {
      await window.firebaseApp.dbDelete(`chats/${currentChatId}/messages/${targetId}`);
    }
    closeDeleteModal();
  } catch (e) {
    alert("Ошибка удаления");
  } finally {
    hideUploadProgress();
  }
}

function triggerForwardFromContext() {
  closeMsgContext();
  const list = document.getElementById("forwardList");
  list.innerHTML = "";

  for (const d of dialogsData) {
    const div = document.createElement("div");
    div.className = "forward-item";
    div.textContent = d.peerUid;
    div.onclick = async () => {
      closeForwardModal();
      showUploadProgress("Пересылка...");
      try {
        const destChatId = getChatId(currentUser.uid, d.peerUid);
        const enc = await window.doubleRatchet.encryptMessage(d.peerUid, selectedDecryptedPayload);
        const fwdId = `msg_fwd_${Date.now()}`;
        await window.firebaseApp.dbSet(`chats/${destChatId}/messages/${fwdId}`, {
          id: fwdId,
          fromId: currentUser.uid,
          toId: d.peerUid,
          encPayload: enc.fullEncryptedPayload,
          timestamp: Date.now(),
          status: "sent"
        });
        alert("Сообщение переслано!");
      } catch (e) {
        alert("Ошибка пересылки");
      } finally {
        hideUploadProgress();
      }
    };
    list.appendChild(div);
  }

  document.getElementById("forwardModal").classList.remove("hidden");
}

function closeForwardModal() {
  document.getElementById("forwardModal").classList.add("hidden");
}

// --- 3-Dots Chat Top Menu & Key Rotation ---

function toggleChatTopMenu() {
  document.getElementById("chatTopMenuOverlay").classList.toggle("hidden");
}

function closeChatTopMenu() {
  document.getElementById("chatTopMenuOverlay").classList.add("hidden");
}

async function triggerRotateKey() {
  closeChatTopMenu();
  if (!currentPeer) return;
  if (confirm("Сменить приватный ключ для этого чата? Следующее сообщение запустит новый цикл DH-рачета.")) {
    await window.doubleRatchet.rotateChatKey(currentPeer.uid);
    alert("Приватный ключ чата успешно обновлен! Следующее отправленное сообщение автоматически обновит E2EE рачет у собеседника.");
  }
}

async function confirmClearChatHistory() {
  closeChatTopMenu();
  if (!currentChatId) return;
  if (confirm("Очистить историю сообщений для обоих участников?")) {
    showUploadProgress("Очистка чата...");
    await window.firebaseApp.dbDelete(`chats/${currentChatId}/messages`);
    hideUploadProgress();
  }
}

// --- Export Decrypted Chat History ---

function openExportChatModal() {
  closeChatTopMenu();
  document.getElementById("exportChatModal").classList.remove("hidden");
}

function closeExportChatModal() {
  document.getElementById("exportChatModal").classList.add("hidden");
}

async function performExportDecryptedChat() {
  const incText = document.getElementById("exportCheckText").checked;
  const incPhotos = document.getElementById("exportCheckPhotos").checked;
  const incCircles = document.getElementById("exportCheckCircles").checked;
  const incVoice = document.getElementById("exportCheckVoice").checked;
  const incAudio = document.getElementById("exportCheckAudio").checked;

  showUploadProgress("Расшифровка и экспорт...");
  closeExportChatModal();

  try {
    let htmlContent = `
      <!DOCTYPE html>
      <html>
      <head>
        <meta charset="UTF-8">
        <title>Экспорт чата Tsuyu - ${escapeHtml(currentPeer.name || currentPeer.username)}</title>
        <style>
          body { font-family: sans-serif; background: #111; color: #fff; padding: 20px; }
          .msg { margin-bottom: 12px; padding: 10px 14px; border-radius: 12px; max-width: 70%; }
          .msg-in { background: #222; }
          .msg-out { background: #2b5278; margin-left: auto; }
          .time { font-size: 11px; color: #888; margin-top: 4px; }
          img { max-width: 300px; border-radius: 8px; }
          video { max-width: 300px; border-radius: 8px; }
        </style>
      </head>
      <body>
        <h2>История переписки с ${escapeHtml(currentPeer.name || currentPeer.username)}</h2>
        <p>Экспортировано: ${new Date().toLocaleString()}</p>
        <hr style="border:1px solid #333;margin-bottom:20px">
    `;

    for (const m of messagesData) {
      let dec = null;
      try {
        dec = await window.doubleRatchet.decryptMessage(m.fromId === currentUser.uid ? m.toId : m.fromId, m.encPayload);
      } catch (e) {}

      const isOut = m.fromId === currentUser.uid;
      const author = isOut ? "Вы" : (currentPeer.name || "Собеседник");
      const time = new Date(m.timestamp).toLocaleTimeString();

      htmlContent += `<div class="msg ${isOut ? 'msg-out' : 'msg-in'}"><b>${escapeHtml(author)}</b><br>`;

      if (dec && dec.text && incText) {
        htmlContent += `<div>${escapeHtml(dec.text)}</div>`;
      }
      if (dec && dec.media) {
        if (dec.media.type === "photo" && incPhotos) {
          htmlContent += `<img src="${dec.media.dataBase64}"><br>`;
        } else if (dec.media.type === "circle" && incCircles) {
          htmlContent += `<video src="${dec.media.dataBase64}" controls></video><br>`;
        } else if (dec.media.type === "voice" && incVoice) {
          htmlContent += `<audio src="${dec.media.dataBase64}" controls></audio><br>`;
        } else if (dec.media.type === "audio" && incAudio) {
          htmlContent += `<audio src="${dec.media.dataBase64}" controls></audio><br>`;
        }
      }

      htmlContent += `<div class="time">${time}</div></div>`;
    }

    htmlContent += `</body></html>`;

    const blob = new Blob([htmlContent], { type: "text/html" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = `tsuyu_chat_${currentPeer.username}_${Date.now()}.html`;
    a.click();
    URL.revokeObjectURL(url);
  } catch (e) {
    alert("Ошибка экспорта");
  } finally {
    hideUploadProgress();
  }
}

// --- Calls Triggering ---

function triggerAudioCall() {
  if (!currentPeer) return;
  window.webrtcCall.startCall(currentPeer.uid, currentPeer.name, currentPeer.photo, false);
}

function triggerVideoCall() {
  if (!currentPeer) return;
  window.webrtcCall.startCall(currentPeer.uid, currentPeer.name, currentPeer.photo, true);
}

// --- Peer Profile View ---

function openPeerProfileView() {
  if (!currentPeer) return;
  document.getElementById("peerProfileViewAvatar").src = currentPeer.photo || "data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' width='100' height='100' fill='%23222'><rect width='100' height='100' rx='50'/></svg>";
  document.getElementById("peerProfileViewName").textContent = currentPeer.name || currentPeer.username;
  document.getElementById("peerProfileViewUsername").textContent = `@${currentPeer.username || ""}`;
  document.getElementById("peerProfileViewBio").textContent = currentPeer.bio || "Нет описания";
  document.getElementById("profileViewModal").classList.add("active");
}

function closePeerProfileView() {
  document.getElementById("profileViewModal").classList.remove("active");
}

// --- Double Ratchet Keys Modal UI ---

async function openEncryptModal() {
  const fp = await window.doubleRatchet.generateDelfanFingerprint();
  document.getElementById("delfanFingerprint").textContent = fp;
  document.getElementById("modalPubKey").textContent = window.doubleRatchet.identityKeyPair ? window.doubleRatchet.identityKeyPair.pubB64 : "Недоступен";
  document.getElementById("encryptModal").classList.remove("hidden");
}

function closeEncryptModal() {
  document.getElementById("encryptModal").classList.add("hidden");
}

async function exportKeysBackup() {
  try {
    await window.doubleRatchet.exportEncryptedKeysBackup();
  } catch (e) {
    alert(e.message);
  }
}

async function importKeysBackup(e) {
  const file = e.target.files[0];
  if (!file) return;
  const password = prompt("Введите мастер-пароль для расшифровки файла ключей:");
  if (!password) return;

  const reader = new FileReader();
  reader.onload = async () => {
    showUploadProgress("Импорт ключей...");
    try {
      await window.doubleRatchet.importEncryptedKeysBackup(reader.result, password);
      alert("Ключи Signal Double Ratchet успешно восстановлены!");
      location.reload();
    } catch (err) {
      alert(err.message);
    } finally {
      hideUploadProgress();
    }
  };
  reader.readAsText(file);
  e.target.value = "";
}

async function regenerateKeysPrompt() {
  if (confirm("Вы уверены, что хотите сменить Identity Key и мастер-пароль? Это пересоздаст E2EE ключи устройства.")) {
    const newPass = prompt("Введите новый мастер-пароль:");
    if (!newPass) return;
    showUploadProgress("Генерация новых ключей...");
    localStorage.setItem("tsuyu_pass", newPass);
    await window.doubleRatchet.initUserCrypto(currentUser.uid, newPass, true);
    hideUploadProgress();
    openEncryptModal();
  }
}

// --- Customization & Privacy Settings UI ---

function openCustomizationModal() {
  document.getElementById("fontSizeSlider").value = customConfig.fontSize || 15;
  document.getElementById("fontSizeDisplay").textContent = `${customConfig.fontSize || 15}px`;
  document.getElementById("fontStyleSelect").value = customConfig.fontStyle || "normal";
  document.getElementById("currentFontName").textContent = customConfig.fontName || "Системный шрифт";
  document.getElementById("customTypingStatusInput").value = customConfig.typingText || "";
  document.getElementById("customOnlineStatusInput").value = customConfig.onlineText || "";
  document.getElementById("customLastSeenStatusInput").value = customConfig.lastSeenText || "";
  document.getElementById("customMsgPlaceholderInput").value = customConfig.msgPlaceholder || "";
  document.getElementById("customizationModal").classList.remove("hidden");
}

function closeCustomizationModal() {
  document.getElementById("customizationModal").classList.add("hidden");
}

function handleFontSizeChange(val) {
  document.getElementById("fontSizeDisplay").textContent = `${val}px`;
  document.body.style.fontSize = `${val}px`;
}

function handleFontStyleChange(val) {
  document.body.style.fontStyle = val === "italic" ? "italic" : "normal";
  document.body.style.fontWeight = val === "bold" ? "bold" : "normal";
}

function handleFontUpload(e) {
  const file = e.target.files[0];
  if (!file) return;
  const reader = new FileReader();
  reader.onload = () => {
    customConfig.fontDataUrl = reader.result;
    customConfig.fontName = file.name;
    document.getElementById("currentFontName").textContent = file.name;
    applyCustomizationStyles();
  };
  reader.readAsDataURL(file);
}

function saveCustomizationSettings() {
  customConfig.fontSize = parseInt(document.getElementById("fontSizeSlider").value, 10);
  customConfig.fontStyle = document.getElementById("fontStyleSelect").value;
  customConfig.typingText = document.getElementById("customTypingStatusInput").value.trim() || "печатает...";
  customConfig.onlineText = document.getElementById("customOnlineStatusInput").value.trim() || "в сети";
  customConfig.lastSeenText = document.getElementById("customLastSeenStatusInput").value.trim() || "был(а) в {time}";
  customConfig.msgPlaceholder = document.getElementById("customMsgPlaceholderInput").value.trim() || "Сообщение...";

  localStorage.setItem("tsuyu_custom_config", JSON.stringify(customConfig));
  applyCustomizationStyles();
  closeCustomizationModal();
}

function applyCustomizationStyles() {
  const fontStyleTag = document.getElementById("customFontStyle");
  if (customConfig.fontDataUrl) {
    fontStyleTag.innerHTML = `
      @font-face {
        font-family: 'TsuyuCustomFont';
        src: url('${customConfig.fontDataUrl}');
      }
      body, input, textarea, button {
        font-family: 'TsuyuCustomFont', -apple-system, sans-serif !important;
      }
    `;
  }
  document.body.style.fontSize = `${customConfig.fontSize || 15}px`;
  document.body.style.fontStyle = customConfig.fontStyle === "italic" ? "italic" : "normal";
  document.body.style.fontWeight = customConfig.fontStyle === "bold" ? "bold" : "normal";
}

function openPrivacyModal() {
  document.getElementById("privacyWhoCanMessage").value = privacyConfig.whoCanMessage || "all";
  document.getElementById("privacyWhoCanSeeLastSeen").value = privacyConfig.whoCanSeeLastSeen || "all";
  document.getElementById("privacyWhoCanSeePhoto").value = privacyConfig.whoCanSeePhoto || "all";
  document.getElementById("privacyWhoCanSeeBio").value = privacyConfig.whoCanSeeBio || "all";
  document.getElementById("privacyModal").classList.remove("hidden");
}

function closePrivacyModal() {
  document.getElementById("privacyModal").classList.add("hidden");
}

async function savePrivacySettings() {
  privacyConfig.whoCanMessage = document.getElementById("privacyWhoCanMessage").value;
  privacyConfig.whoCanSeeLastSeen = document.getElementById("privacyWhoCanSeeLastSeen").value;
  privacyConfig.whoCanSeePhoto = document.getElementById("privacyWhoCanSeePhoto").value;
  privacyConfig.whoCanSeeBio = document.getElementById("privacyWhoCanSeeBio").value;

  localStorage.setItem("tsuyu_privacy_config", JSON.stringify(privacyConfig));
  if (currentUser) {
    await window.firebaseApp.dbUpdate(`users/${currentUser.uid}`, { privacy: privacyConfig });
  }
  closePrivacyModal();
}

// --- Photo Viewer with Pinch-to-Zoom & Drag ---

function openPhotoViewer(url) {
  if (!url) return;
  const modal = document.getElementById("photoViewerModal");
  const img = document.getElementById("photoViewerImg");
  const downloadBtn = document.getElementById("photoDownloadBtn");
  img.src = url;
  downloadBtn.href = url;
  modal.classList.add("active");
}

function closePhotoViewer() {
  document.getElementById("photoViewerModal").classList.remove("active");
}

function initPhotoViewerZoom() {
  const img = document.getElementById("photoViewerImg");
  const modal = document.getElementById("photoViewerModal");
  let scale = 1;
  let lastScale = 1;
  let startX = 0, startY = 0;
  let translateX = 0, translateY = 0;
  let isDragging = false;

  modal.addEventListener("touchstart", (e) => {
    if (e.touches.length === 2) {
      const dx = e.touches[0].clientX - e.touches[1].clientX;
      const dy = e.touches[0].clientY - e.touches[1].clientY;
      lastScale = Math.sqrt(dx * dx + dy * dy);
    } else if (e.touches.length === 1 && scale > 1) {
      isDragging = true;
      startX = e.touches[0].clientX - translateX;
      startY = e.touches[0].clientY - translateY;
    }
  }, { passive: true });

  modal.addEventListener("touchmove", (e) => {
    if (e.touches.length === 2) {
      e.preventDefault();
      const dx = e.touches[0].clientX - e.touches[1].clientX;
      const dy = e.touches[0].clientY - e.touches[1].clientY;
      const dist = Math.sqrt(dx * dx + dy * dy);
      scale = Math.min(Math.max(1, (dist / lastScale) * scale), 5);
      lastScale = dist;
      img.style.transform = `translate(${translateX}px, ${translateY}px) scale(${scale})`;
    } else if (isDragging && scale > 1) {
      e.preventDefault();
      translateX = e.touches[0].clientX - startX;
      translateY = e.touches[0].clientY - startY;
      img.style.transform = `translate(${translateX}px, ${translateY}px) scale(${scale})`;
    }
  }, { passive: false });

  modal.addEventListener("touchend", () => {
    isDragging = false;
    if (scale < 1) {
      scale = 1;
      translateX = 0;
      translateY = 0;
      img.style.transform = `translate(0px, 0px) scale(1)`;
    }
  });
}

// --- Utilities & Cache Management ---

function scrollToBottomChat() {
  const wrap = document.getElementById("messagesWrapper");
  if (wrap) wrap.scrollTop = wrap.scrollHeight;
}

function scrollToMessage(msgId) {
  const el = document.getElementById(`msg_${msgId}`);
  if (el) el.scrollIntoView({ behavior: "smooth", block: "center" });
}

function showUploadProgress(text) {
  const toast = document.getElementById("uploadToast");
  const toastText = document.getElementById("uploadToastText");
  if (toast && toastText) {
    toastText.textContent = text || "Загрузка...";
    toast.classList.remove("hidden");
  }
}

function hideUploadProgress() {
  const toast = document.getElementById("uploadToast");
  if (toast) toast.classList.add("hidden");
}

function formatTime(timestamp) {
  const d = new Date(timestamp);
  const h = d.getHours().toString().padStart(2, "0");
  const m = d.getMinutes().toString().padStart(2, "0");
  return `${h}:${m}`;
}

function formatRelativeTime(timestamp) {
  const now = new Date();
  const d = new Date(timestamp);
  if (now.toDateString() === d.toDateString()) {
    return formatTime(timestamp);
  }
  const months = ["янв", "фев", "мар", "апр", "май", "июн", "июл", "авг", "сен", "окт", "ноя", "дек"];
  return `${d.getDate()} ${months[d.getMonth()]}`;
}

function formatSecondsToMMSS(seconds) {
  const m = Math.floor(seconds / 60);
  const s = Math.floor(seconds % 60);
  return `${m}:${s < 10 ? "0" : ""}${s}`;
}

function escapeHtml(str) {
  if (!str) return "";
  return str.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;").replace(/'/g, "&#039;");
}

async function updateCacheSizeDisplay() {
  let totalBytes = 0;
  for (let i = 0; i < localStorage.length; i++) {
    const k = localStorage.key(i);
    totalBytes += (k.length + localStorage.getItem(k).length) * 2;
  }
  const mb = (totalBytes / (1024 * 1024)).toFixed(1);
  const elem = document.getElementById("cacheSizeText");
  if (elem) elem.textContent = `Очистить кэш (${mb} MB)`;
}

function clearAppCache() {
  if (confirm("Очистить кэш диалогов и временных данных?")) {
    const pass = localStorage.getItem("tsuyu_pass");
    const uid = localStorage.getItem("tsuyu_uid");
    const token = localStorage.getItem("tsuyu_id_token");
    const rToken = localStorage.getItem("tsuyu_refresh_token");

    localStorage.clear();

    if (uid) localStorage.setItem("tsuyu_uid", uid);
    if (token) localStorage.setItem("tsuyu_id_token", token);
    if (rToken) localStorage.setItem("tsuyu_refresh_token", rToken);
    if (pass) localStorage.setItem("tsuyu_pass", pass);

    alert("Кэш успешно очищен!");
    location.reload();
  }
}

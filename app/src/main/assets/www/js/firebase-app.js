/**
 * Tsuyu Messenger - Firebase Realtime Database & Authentication Client
 * Pure Lightweight REST & SSE implementation (No heavy external SDKs)
 */

const firebaseConfig = {
  apiKey: "AIzaSyBm0mIvHVznIeF2PoFk6dtdaiT5r877wyA",
  authDomain: "meow-874ce.firebaseapp.com",
  databaseURL: "https://meow-874ce-default-rtdb.europe-west1.firebasedatabase.app",
  projectId: "meow-874ce",
  storageBucket: "meow-874ce.firebasestorage.app",
  messagingSenderId: "471541334599",
  appId: "1:471541334599:web:567af3e7dbe70a37572762"
};

class FirebaseClient {
  constructor(config) {
    this.config = config;
    this.apiKey = config.apiKey;
    this.dbUrl = config.databaseURL.replace(/\/$/, "");
    this.idToken = localStorage.getItem("tsuyu_id_token") || null;
    this.refreshToken = localStorage.getItem("tsuyu_refresh_token") || null;
    this.uid = localStorage.getItem("tsuyu_uid") || null;
    this.listeners = new Map();
    this.presenceTimer = null;
  }

  getAuthQuery() {
    return this.idToken ? `?auth=${this.idToken}` : "";
  }

  async signUp(email, password) {
    const res = await fetch(`https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=${this.apiKey}`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ email, password, returnSecureToken: true })
    });
    const data = await res.json();
    if (data.error) {
      throw new Error(this.formatAuthError(data.error.message));
    }
    this.setSession(data);
    return data;
  }

  async signIn(email, password) {
    const res = await fetch(`https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=${this.apiKey}`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ email, password, returnSecureToken: true })
    });
    const data = await res.json();
    if (data.error) {
      throw new Error(this.formatAuthError(data.error.message));
    }
    this.setSession(data);
    return data;
  }

  setSession(data) {
    this.idToken = data.idToken;
    this.refreshToken = data.refreshToken;
    this.uid = data.localId;
    localStorage.setItem("tsuyu_id_token", this.idToken);
    localStorage.setItem("tsuyu_refresh_token", this.refreshToken);
    localStorage.setItem("tsuyu_uid", this.uid);
  }

  clearSession() {
    this.idToken = null;
    this.refreshToken = null;
    this.uid = null;
    localStorage.removeItem("tsuyu_id_token");
    localStorage.removeItem("tsuyu_refresh_token");
    localStorage.removeItem("tsuyu_uid");
    this.closeAllListeners();
    if (this.presenceTimer) clearInterval(this.presenceTimer);
  }

  async refreshAuthToken() {
    if (!this.refreshToken) return null;
    try {
      const res = await fetch(`https://securetoken.googleapis.com/v1/token?key=${this.apiKey}`, {
        method: "POST",
        headers: { "Content-Type": "application/x-www-form-urlencoded" },
        body: `grant_type=refresh_token&refresh_token=${this.refreshToken}`
      });
      const data = await res.json();
      if (data.id_token) {
        this.idToken = data.id_token;
        this.refreshToken = data.refresh_token;
        localStorage.setItem("tsuyu_id_token", this.idToken);
        localStorage.setItem("tsuyu_refresh_token", this.refreshToken);
        return this.idToken;
      }
    } catch (e) {
      console.warn("Failed to refresh token:", e);
    }
    return null;
  }

  formatAuthError(err) {
    if (!err) return "Ошибка аутентификации";
    if (err.includes("EMAIL_EXISTS")) return "Пользователь с такой почтой уже зарегистрирован";
    if (err.includes("INVALID_LOGIN_CREDENTIALS") || err.includes("EMAIL_NOT_FOUND") || err.includes("INVALID_PASSWORD"))
      return "Неверная почта или пароль";
    if (err.includes("WEAK_PASSWORD")) return "Пароль должен содержать минимум 6 символов";
    if (err.includes("INVALID_EMAIL")) return "Некорректный формат почты";
    return err;
  }

  // --- Realtime Database REST Operations ---

  async dbGet(path) {
    const cleanPath = path.replace(/^\//, "");
    const url = `${this.dbUrl}/${cleanPath}.json${this.getAuthQuery()}`;
    const res = await fetch(url);
    if (res.status === 401) {
      await this.refreshAuthToken();
      return this.dbGet(path);
    }
    return await res.json();
  }

  async dbSet(path, data) {
    const cleanPath = path.replace(/^\//, "");
    const url = `${this.dbUrl}/${cleanPath}.json${this.getAuthQuery()}`;
    const res = await fetch(url, {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(data)
    });
    if (res.status === 401) {
      await this.refreshAuthToken();
      return this.dbSet(path, data);
    }
    return await res.json();
  }

  async dbUpdate(path, data) {
    const cleanPath = path.replace(/^\//, "");
    const url = `${this.dbUrl}/${cleanPath}.json${this.getAuthQuery()}`;
    const res = await fetch(url, {
      method: "PATCH",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(data)
    });
    if (res.status === 401) {
      await this.refreshAuthToken();
      return this.dbUpdate(path, data);
    }
    return await res.json();
  }

  async dbPush(path, data) {
    const cleanPath = path.replace(/^\//, "");
    const url = `${this.dbUrl}/${cleanPath}.json${this.getAuthQuery()}`;
    const res = await fetch(url, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(data)
    });
    if (res.status === 401) {
      await this.refreshAuthToken();
      return this.dbPush(path, data);
    }
    const result = await res.json();
    return result && result.name ? result.name : null;
  }

  async dbDelete(path) {
    const cleanPath = path.replace(/^\//, "");
    const url = `${this.dbUrl}/${cleanPath}.json${this.getAuthQuery()}`;
    const res = await fetch(url, { method: "DELETE" });
    if (res.status === 401) {
      await this.refreshAuthToken();
      return this.dbDelete(path);
    }
    return await res.json();
  }

  // --- Realtime SSE EventSource Streaming Listener ---

  dbListen(path, onData, onError) {
    const cleanPath = path.replace(/^\//, "");
    const listenerKey = cleanPath;

    // Close previous listener on same path if any
    if (this.listeners.has(listenerKey)) {
      try { this.listeners.get(listenerKey).close(); } catch(e){}
    }

    const url = `${this.dbUrl}/${cleanPath}.json${this.getAuthQuery()}`;
    let evtSource;
    try {
      evtSource = new EventSource(url);
    } catch (e) {
      if (onError) onError(e);
      return null;
    }

    evtSource.addEventListener("put", (e) => {
      try {
        const payload = JSON.parse(e.data);
        if (onData) onData(payload.path, payload.data, "put");
      } catch (err) {
        console.error("SSE put parse error:", err);
      }
    });

    evtSource.addEventListener("patch", (e) => {
      try {
        const payload = JSON.parse(e.data);
        if (onData) onData(payload.path, payload.data, "patch");
      } catch (err) {
        console.error("SSE patch parse error:", err);
      }
    });

    evtSource.addEventListener("keep-alive", () => {});

    evtSource.onerror = (err) => {
      console.warn(`SSE error on ${cleanPath}:`, err);
      if (onError) onError(err);
    };

    this.listeners.set(listenerKey, evtSource);
    return evtSource;
  }

  dbUnlisten(path) {
    const cleanPath = path.replace(/^\//, "");
    if (this.listeners.has(cleanPath)) {
      try { this.listeners.get(cleanPath).close(); } catch(e){}
      this.listeners.delete(cleanPath);
    }
  }

  closeAllListeners() {
    for (const [k, es] of this.listeners) {
      try { es.close(); } catch(e){}
    }
    this.listeners.clear();
  }

  // --- Presence & Ghost Mode Heartbeat ---

  startPresence(uid, isGhost = false) {
    if (!uid) return;
    if (this.presenceTimer) clearInterval(this.presenceTimer);

    const updatePresence = async () => {
      if (isGhost) {
        // In ghost mode, do not update lastSeen or online status
        return;
      }
      try {
        await this.dbSet(`presence/${uid}`, {
          online: true,
          lastSeen: Date.now(),
          ghost: false
        });
      } catch (e) {}
    };

    updatePresence();
    this.presenceTimer = setInterval(updatePresence, 30000); // Heartbeat every 30s

    window.addEventListener("beforeunload", () => {
      if (!isGhost) {
        navigator.sendBeacon(`${this.dbUrl}/presence/${uid}.json`, JSON.stringify({
          online: false,
          lastSeen: Date.now(),
          ghost: false
        }));
      }
    });
  }

  async setOffline(uid, isGhost = false) {
    if (this.presenceTimer) clearInterval(this.presenceTimer);
    if (!isGhost && uid) {
      try {
        await this.dbSet(`presence/${uid}`, {
          online: false,
          lastSeen: Date.now(),
          ghost: false
        });
      } catch (e) {}
    }
  }
}

window.firebaseApp = new FirebaseClient(firebaseConfig);

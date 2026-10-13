const state = {
  username: sessionStorage.getItem("filmUsername"),
  token: sessionStorage.getItem("filmToken"),
  films: [],
  filter: "All",
  search: "",
  authMode: "login"
};

const colors = [
  ["#557865", "#dbb984"], ["#607e8a", "#d4c294"], ["#7e5b58", "#e3b183"],
  ["#596e50", "#e4c786"], ["#76607f", "#d6ac98"], ["#416970", "#d8b078"],
  ["#8a674e", "#e1bd84"], ["#596c80", "#dfb68a"], ["#746b48", "#e3d19e"],
  ["#9a6658", "#e1bf92"]
];

const views = {
  discover: document.getElementById("discover-view"),
  diary: document.getElementById("diary-view"),
  feed: document.getElementById("feed-view"),
  people: document.getElementById("people-view")
};

async function api(type, fields = {}) {
  const response = await fetch("/api", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ type, ...fields })
  });
  const result = await response.json();
  if (!response.ok || result.status !== "OK") {
    throw new Error(result.message || "The request could not be completed.");
  }
  return result;
}

function showToast(message, isError = false) {
  const toast = document.getElementById("toast");
  toast.textContent = message;
  toast.style.background = isError ? "#8a4439" : "#293b30";
  toast.classList.add("show");
  clearTimeout(showToast.timer);
  showToast.timer = setTimeout(() => toast.classList.remove("show"), 3200);
}

function setBusy(button, busy, label) {
  button.disabled = busy;
  if (label) button.textContent = label;
}

function syncProfile() {
  const loggedIn = Boolean(state.username && state.token);
  document.getElementById("profile-card").classList.toggle("hidden", !loggedIn);
  document.getElementById("open-auth").classList.toggle("hidden", loggedIn);
  document.getElementById("profile-name").textContent = state.username || "Guest";
  document.getElementById("profile-avatar").textContent = (state.username || "F")[0].toUpperCase();
  document.getElementById("quick-log").disabled = !loggedIn;
  document.getElementById("hero-log").disabled = !loggedIn;
  document.getElementById("follow-button").disabled = !loggedIn;
  document.getElementById("unfollow-button").disabled = !loggedIn;
  document.getElementById("people-message").textContent = loggedIn
    ? `Signed in as ${state.username}. Public diaries can be viewed by username.`
    : "Sign in to follow accounts. Public diaries can be viewed below.";
}

function filmColors(film) {
  return colors[Math.abs(Number(film.id || 0)) % colors.length];
}

function makeFilmCard(film) {
  const card = document.createElement("article");
  const [base, glow] = filmColors(film);
  card.className = "film-card";
  card.innerHTML = `
    <div class="cover" style="--cover:radial-gradient(ellipse at 48% 35%,${glow},transparent 35%),linear-gradient(155deg,${base},#252e2c)">
      <div class="cover-art" style="--art:linear-gradient(135deg,${glow},${base} 55%,#24352e)"></div>
      <span class="cover-label"></span>
      <button class="film-quick-log" type="button" title="Log this film" aria-label="Log this film">＋</button>
    </div>
    <div class="film-info"><div class="film-title"></div><div class="film-meta"></div><span class="film-genre"></span></div>`;
  card.querySelector(".cover-label").textContent = film.title;
  card.querySelector(".film-title").textContent = film.title;
  card.querySelector(".film-meta").textContent = `${film.year} · ${film.director}`;
  card.querySelector(".film-genre").textContent = film.genre;
  card.querySelector(".film-quick-log").addEventListener("click", event => {
    event.stopPropagation();
    openLogModal(film.id);
  });
  card.querySelector(".cover").addEventListener("click", () => openLogModal(film.id));
  return card;
}

function renderFilms() {
  const query = state.search.trim().toLowerCase();
  const visible = state.films.filter(film => {
    const matchesGenre = state.filter === "All" || film.genre.toLowerCase().includes(state.filter.toLowerCase());
    const matchesSearch = !query || `${film.title} ${film.director} ${film.genre} ${film.year}`.toLowerCase().includes(query);
    return matchesGenre && matchesSearch;
  });
  const grid = document.getElementById("film-grid");
  grid.replaceChildren(...visible.map(makeFilmCard));
  document.getElementById("film-count").innerHTML = `${visible.length} FILM${visible.length === 1 ? "" : "S"} <span>↗</span>`;
  document.getElementById("film-empty").classList.toggle("hidden", visible.length > 0);
  const select = document.getElementById("log-film");
  const selected = select.value;
  select.replaceChildren(...state.films.map(film => {
    const option = document.createElement("option");
    option.value = film.id;
    option.textContent = `${film.title} (${film.year})`;
    return option;
  }));
  if (selected) select.value = selected;
}

function renderEntries(container, entries, emptyTitle, emptyCopy, showUsername = false) {
  container.replaceChildren();
  if (!entries.length) {
    const empty = document.createElement("div");
    empty.className = "empty-panel";
    empty.innerHTML = `<strong></strong><span></span>`;
    empty.querySelector("strong").textContent = emptyTitle;
    empty.querySelector("span").textContent = emptyCopy;
    container.append(empty);
    return;
  }
  entries.forEach(entry => {
    const [base, glow] = filmColors({ id: entry.filmId });
    const row = document.createElement("article");
    row.className = "diary-entry";
    row.innerHTML = `
      <div class="mini-cover" style="--cover:radial-gradient(ellipse at 48% 35%,${glow},transparent 35%),linear-gradient(155deg,${base},#252e2c)"></div>
      <div class="entry-details"><span class="entry-user"></span><h3></h3><p></p></div>
      <span class="entry-stars"></span><time class="entry-date"></time>`;
    row.querySelector(".entry-user").textContent = `@${entry.username}`;
    row.querySelector(".entry-user").classList.toggle("hidden", !showUsername);
    row.querySelector("h3").textContent = entry.filmTitle;
    row.querySelector(".entry-details p").textContent = entry.review || `Watched by ${entry.username}`;
    row.querySelector(".entry-stars").textContent = `${"★".repeat(Math.floor(entry.rating))}${entry.rating % 1 ? "½" : ""}`;
    row.querySelector(".entry-date").textContent = entry.date;
    container.append(row);
  });
}

async function loadDiary() {
  const result = await api("GET_DIARY", { username: state.username, token: state.token });
  const entries = result.diary || [];
  renderEntries(document.getElementById("diary-list"), entries, "Your diary starts here.", "Log a film and it will appear on this page.");
  document.getElementById("diary-total").textContent = entries.length;
  const currentYear = new Date().getFullYear().toString();
  document.getElementById("diary-year").textContent = entries.filter(entry => entry.date.startsWith(currentYear)).length;
  const average = entries.length ? entries.reduce((sum, entry) => sum + Number(entry.rating), 0) / entries.length : 0;
  document.getElementById("diary-average").textContent = average ? average.toFixed(1) : "—";
}

async function loadFeed() {
  const result = await api("GET_FEED", { username: state.username, token: state.token });
  const list = document.getElementById("feed-list");
  renderEntries(list, result.feed || [], "Your feed is quiet for now.", "Follow a film friend to see their recent watches here.", true);
}

async function loadPublicDiary(username) {
  const result = await api("GET_USER_DIARY", { targetUser: username });
  renderEntries(document.getElementById("public-diary-list"), result.diary || [], "No entries yet.", `${username} has not logged any films.`);
}

function switchView(name) {
  Object.entries(views).forEach(([viewName, element]) => element.classList.toggle("active", viewName === name));
  document.querySelectorAll(".nav-item").forEach(button => button.classList.toggle("active", button.dataset.view === name));
  const titles = { discover: "Discover", diary: "My diary", feed: "Activity", people: "People" };
  document.getElementById("topbar-title").textContent = titles[name];
  if (name === "diary") {
    if (state.token) loadDiary().catch(error => showToast(error.message, true));
    else showToast("Sign in to view your diary.", true);
  }
  if (name === "feed") {
    if (state.token) loadFeed().catch(error => showToast(error.message, true));
    else showToast("Sign in to view your activity feed.", true);
  }
}

function setAuthMode(mode) {
  state.authMode = mode;
  const registering = mode === "register";
  document.getElementById("auth-title").innerHTML = registering ? "Start your own<br>film journal." : "Your next chapter<br>starts here.";
  document.getElementById("auth-description").textContent = registering
    ? "Create an account to start collecting your cinema memories."
    : "Sign in to keep a personal record of the films you watch.";
  document.getElementById("auth-submit").innerHTML = registering ? "Create account <span>→</span>" : "Sign in <span>→</span>";
  document.getElementById("auth-switch").textContent = registering
    ? "Already have an account? Sign in"
    : "New here? Create an account";
  document.getElementById("auth-password").autocomplete = registering ? "new-password" : "current-password";
}

function openLogModal(filmId) {
  if (!state.token) {
    document.getElementById("auth-modal").showModal();
    return;
  }
  if (filmId) document.getElementById("log-film").value = filmId;
  document.getElementById("log-date").value = new Date().toISOString().slice(0, 10);
  document.getElementById("log-modal").showModal();
}

document.querySelectorAll(".nav-item").forEach(button => {
  button.addEventListener("click", () => switchView(button.dataset.view));
});
document.querySelectorAll("[data-close]").forEach(button => {
  button.addEventListener("click", () => document.getElementById(button.dataset.close).close());
});
document.querySelectorAll("dialog").forEach(dialog => {
  dialog.addEventListener("click", event => {
    if (event.target === dialog) dialog.close();
  });
});
document.querySelectorAll(".filter-pill").forEach(button => {
  button.addEventListener("click", () => {
    state.filter = button.dataset.filter;
    document.querySelectorAll(".filter-pill").forEach(pill => pill.classList.toggle("selected", pill === button));
    renderFilms();
  });
});
document.getElementById("search-input").addEventListener("input", event => {
  state.search = event.target.value;
  renderFilms();
});
document.getElementById("open-auth").addEventListener("click", () => {
  setAuthMode("login");
  document.getElementById("auth-modal").showModal();
});
document.getElementById("auth-switch").addEventListener("click", () => {
  setAuthMode(state.authMode === "login" ? "register" : "login");
});
document.getElementById("quick-log").addEventListener("click", () => openLogModal());
document.getElementById("hero-log").addEventListener("click", () => openLogModal());
document.getElementById("auth-form").addEventListener("submit", async event => {
  event.preventDefault();
  const button = document.getElementById("auth-submit");
  const username = document.getElementById("auth-username").value.trim();
  const password = document.getElementById("auth-password").value;
  setBusy(button, true, "Please wait...");
  try {
    if (state.authMode === "register") {
      await api("REGISTER", { username, password });
      showToast("Account created. You can now sign in.");
      setAuthMode("login");
      document.getElementById("auth-password").value = "";
      return;
    }
    const result = await api("LOGIN", { username, password });
    state.username = username;
    state.token = result.token;
    sessionStorage.setItem("filmUsername", username);
    sessionStorage.setItem("filmToken", result.token);
    syncProfile();
    document.getElementById("auth-modal").close();
    showToast(`Welcome back, ${username}.`);
  } catch (error) {
    showToast(error.message, true);
  } finally {
    button.disabled = false;
    setAuthMode(state.authMode);
  }
});
document.getElementById("log-form").addEventListener("submit", async event => {
  event.preventDefault();
  const button = event.submitter;
  setBusy(button, true, "Saving...");
  try {
    await api("LOG_FILM", {
      username: state.username,
      token: state.token,
      filmId: Number(document.getElementById("log-film").value),
      rating: Number(document.getElementById("log-rating").value),
      date: document.getElementById("log-date").value,
      review: document.getElementById("log-review").value.trim()
    });
    document.getElementById("log-modal").close();
    document.getElementById("log-review").value = "";
    showToast("Added to your diary. Nice choice!");
    if (views.diary.classList.contains("active")) loadDiary().catch(error => showToast(error.message, true));
  } catch (error) {
    showToast(error.message, true);
  } finally {
    button.disabled = false;
    button.innerHTML = "Save to diary <span>→</span>";
  }
});
document.getElementById("logout-button").addEventListener("click", async () => {
  try {
    await api("LOGOUT", { token: state.token });
  } catch (error) {
    showToast(error.message, true);
  }
  state.username = null;
  state.token = null;
  sessionStorage.removeItem("filmUsername");
  sessionStorage.removeItem("filmToken");
  syncProfile();
  showToast("You have been signed out.");
  switchView("discover");
});
document.getElementById("follow-button").addEventListener("click", async () => {
  const followee = document.getElementById("person-input").value.trim();
  if (!followee) return showToast("Enter a username first.", true);
  try {
    const result = await api("FOLLOW", { follower: state.username, followee, token: state.token });
    showToast(result.message);
  } catch (error) {
    showToast(error.message, true);
  }
});
document.getElementById("unfollow-button").addEventListener("click", async () => {
  const followee = document.getElementById("person-input").value.trim();
  if (!followee) return showToast("Enter a username first.", true);
  try {
    const result = await api("UNFOLLOW", { follower: state.username, followee, token: state.token });
    showToast(result.message);
  } catch (error) {
    showToast(error.message, true);
  }
});
document.getElementById("public-diary-button").addEventListener("click", async () => {
  const username = document.getElementById("public-user-input").value.trim();
  if (!username) return showToast("Enter a username first.", true);
  try {
    await loadPublicDiary(username);
  } catch (error) {
    showToast(error.message, true);
  }
});
document.getElementById("search-input").addEventListener("keydown", event => {
  if (event.key === "Enter") switchView("discover");
});
document.addEventListener("keydown", event => {
  if (event.key === "/" && !["INPUT", "TEXTAREA", "SELECT"].includes(document.activeElement.tagName)) {
    event.preventDefault();
    document.getElementById("search-input").focus();
  }
});

async function startApp() {
  syncProfile();
  try {
    const result = await api("LIST_FILMS");
    state.films = result.films || [];
    renderFilms();
    if (state.token) {
      try {
        await api("GET_DIARY", { username: state.username, token: state.token });
      } catch (error) {
        state.username = null;
        state.token = null;
        sessionStorage.removeItem("filmUsername");
        sessionStorage.removeItem("filmToken");
        syncProfile();
        showToast("Your session ended. Please sign in again.", true);
      }
    }
  } catch (error) {
    document.getElementById("film-count").textContent = "SERVER UNAVAILABLE";
    showToast(error.message, true);
  }
}

setAuthMode("login");
startApp();

const gate = document.querySelector("#gate");
const desk = document.querySelector("#desk");
const tools = document.querySelector("#tools");
const authStatus = document.querySelector("#auth-status");
const list = document.querySelector("#list");
const seats = document.querySelector("#seats");
const reserve = document.querySelector("#reserve");
const reserveStatus = document.querySelector("#reserve-status");
const seatForm = document.querySelector("#seat");
const seatStatus = document.querySelector("#status");
const hoursForm = document.querySelector("#hours");
const hoursStatus = document.querySelector("#hours-status");
const totals = document.querySelector("#totals");
let latest = null;
let lastPaint = "";
let seenNotice = localStorage.getItem("mezzayn-notice") || "";

function token() {
  return localStorage.getItem("mezzayn-token") || "";
}

function say(node, message, bad) {
  node.hidden = !message;
  node.textContent = message || "";
  node.className = bad ? "status bad" : "status";
}

async function api(path, options = {}) {
  const headers = Object.assign({ Authorization: "Bearer " + token() }, options.headers || {});
  const response = await fetch(path, Object.assign({}, options, { headers }));
  if (response.status === 401) {
    localStorage.removeItem("mezzayn-token");
    showGate("Sign in to continue.");
    throw new Error("Sign in to continue.");
  }
  const type = response.headers.get("content-type") || "";
  if (!type.includes("json")) return response;
  const body = await response.json();
  if (!response.ok) throw new Error(body.error || "The desk could not finish that.");
  return body;
}

function showGate(message) {
  gate.hidden = false;
  desk.hidden = true;
  tools.hidden = true;
  say(authStatus, message || "", Boolean(message));
}

function showDesk() {
  gate.hidden = true;
  desk.hidden = false;
  tools.hidden = false;
}

function line(className, text) {
  const node = document.createElement("p");
  node.className = className;
  node.textContent = text;
  return node;
}

function bars(series) {
  const row = document.createElement("div");
  row.className = "bars";
  const peak = Math.max(1, ...series.map((item) => item.amount));
  series.forEach((item) => {
    const bar = document.createElement("b");
    bar.style.height = Math.max(2, Math.round((item.amount / peak) * 72)) + "px";
    bar.title = item.label + " · " + item.amount;
    row.append(bar);
  });
  return row;
}

function fillHours(hours) {
  const options = [];
  for (let minute = 0; minute < 24 * 60; minute += 30) {
    options.push(String(Math.floor(minute / 60)).padStart(2, "0") + ":" + String(minute % 60).padStart(2, "0"));
  }
  ["open", "close"].forEach((name) => {
    const select = hoursForm.elements[name];
    const current = select.value;
    select.replaceChildren(...options.map((value) => {
      const option = document.createElement("option");
      option.value = value;
      option.textContent = value;
      return option;
    }));
    select.value = options.includes(hours[name]) ? hours[name] : current;
  });
}

function fillReserve() {
  if (!latest) return;
  const seatSelect = reserve.elements.seat;
  const slotSelect = reserve.elements.slot;
  const serviceSelect = reserve.elements.service;
  const seatValue = seatSelect.value;
  const slotValue = slotSelect.value;
  const serviceValue = serviceSelect.value;
  seatSelect.replaceChildren(...latest.seats.map((seat) => {
    const option = document.createElement("option");
    option.value = seat.id;
    option.textContent = seat.name + " · " + seat.barber;
    return option;
  }));
  if ([...seatSelect.options].some((option) => option.value === seatValue)) seatSelect.value = seatValue;
  const seat = latest.seats.find((item) => item.id === seatSelect.value) || latest.seats[0];
  const free = seat ? seat.free : [];
  slotSelect.replaceChildren(...free.map((slot) => {
    const option = document.createElement("option");
    option.value = slot;
    option.textContent = slot;
    return option;
  }));
  if (free.includes(slotValue)) slotSelect.value = slotValue;
  serviceSelect.replaceChildren(...latest.services.map((service) => {
    const option = document.createElement("option");
    option.value = service.name;
    option.textContent = service.name + " · " + service.price;
    return option;
  }));
  if ([...serviceSelect.options].some((option) => option.value === serviceValue)) serviceSelect.value = serviceValue;
}

function paint(board) {
  latest = board;
  document.querySelector("#day").textContent = board.day;
  fillHours(board.hours);
  const periods = [
    ["Today", board.totals.day, board.charts.day],
    ["7 days", board.totals.week, board.charts.week],
    ["This month", board.totals.month, board.charts.month],
  ];
  totals.replaceChildren(...periods.map(([label, amount, series]) => {
    const card = document.createElement("article");
    card.className = "stat";
    const strong = document.createElement("strong");
    strong.textContent = String(amount);
    const span = document.createElement("span");
    span.textContent = label;
    card.append(strong, span, bars(series));
    return card;
  }));
  const bookings = board.bookings.slice().sort((a, b) => a.slot.localeCompare(b.slot));
  list.replaceChildren(...bookings.map((booking) => {
    const item = document.createElement("li");
    item.className = "booking";
    const when = line("when", booking.slot.slice(-5));
    const body = document.createElement("div");
    const actions = document.createElement("div");
    actions.className = "actions";
    const seat = board.seats.find((item) => item.id === booking.seatId);
    const choices = [booking.slot.slice(-5)].concat((seat ? seat.free : []).filter((slot) => slot !== booking.slot.slice(-5)));
    const select = document.createElement("select");
    select.replaceChildren(...choices.map((slot) => {
      const option = document.createElement("option");
      option.value = slot;
      option.textContent = slot;
      return option;
    }));
    const move = document.createElement("button");
    move.type = "button";
    move.className = "quiet";
    move.textContent = "Move";
    move.addEventListener("click", async () => {
      try {
        paint(await api("/api/bookings/" + booking.id, {
          method: "PATCH",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ slot: select.value, seatId: booking.seatId }),
        }));
        say(reserveStatus, "Moved to " + select.value + ".", false);
      } catch (error) {
        say(reserveStatus, error.message, true);
      }
    });
    const cancel = document.createElement("button");
    cancel.type = "button";
    cancel.className = "quiet";
    cancel.textContent = "Cancel reservation";
    cancel.addEventListener("click", async () => {
      try {
        paint(await api("/api/bookings/" + booking.id, { method: "DELETE" }));
      } catch (error) {
        say(reserveStatus, error.message, true);
      }
    });
    actions.append(select, move, cancel);
    body.append(line("who", booking.customer), line("muted", booking.service + " · " + booking.seat + " · " + booking.barber), actions);
    const price = line("price", String(booking.price));
    item.append(when, body, price);
    return item;
  }));
  if (bookings.length === 0) {
    const item = document.createElement("li");
    item.append(line("empty", "No chairs booked today. The floor is open."));
    list.replaceChildren(item);
  }
  seats.replaceChildren(...board.seats.map((seat) => {
    const item = document.createElement("li");
    item.className = "chair";
    const body = document.createElement("div");
    body.append(line("who", seat.name), line("muted", seat.barber));
    const next = document.createElement("p");
    next.className = seat.free.length ? "open" : "full";
    next.textContent = seat.free.length ? seat.free[0] + " · " + seat.free.length + " open" : "Fully booked";
    item.append(body, next);
    return item;
  }));
  fillReserve();
}

async function refresh() {
  if (!token()) return;
  try {
    const board = await api("/api/board");
    if (board.role) return;
    if (!board.totals) {
      showGate("This desk is for the owner account.");
      return;
    }
    showDesk();
    const key = JSON.stringify(board);
    if (key === lastPaint) return;
    lastPaint = key;
    paint(board);
  } catch (error) {
    if (token()) say(authStatus, error.message, true);
  }
}

async function notices() {
  if (!token() || desk.hidden) return;
  try {
    const body = await api("/api/notices?since=" + encodeURIComponent(seenNotice));
    const fresh = body.notices || [];
    if (!seenNotice) {
      seenNotice = new Date().toISOString();
      localStorage.setItem("mezzayn-notice", seenNotice);
      return;
    }
    fresh.forEach((item) => {
      seenNotice = item.at;
      localStorage.setItem("mezzayn-notice", item.at);
      if (Notification.permission === "granted") {
        new Notification("Al-mezzayn", { body: item.text });
      }
    });
  } catch (_) {}
}

document.querySelector("#login").addEventListener("submit", async (event) => {
  event.preventDefault();
  const data = new FormData(event.currentTarget);
  try {
    const body = await fetch("/api/auth/login", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ name: data.get("name"), password: data.get("password") }),
    }).then((response) => response.json());
    if (!body.token) {
      say(authStatus, body.error || "That name or password does not match.", true);
      return;
    }
    if (body.role !== "owner") {
      say(authStatus, "That account is for the phone. Sign in here with an owner.", true);
      return;
    }
    localStorage.setItem("mezzayn-token", body.token);
    say(authStatus, "", false);
    refresh();
  } catch (error) {
    say(authStatus, error.message, true);
  }
});

document.querySelector("#register").addEventListener("submit", async (event) => {
  event.preventDefault();
  const data = new FormData(event.currentTarget);
  try {
    const response = await fetch("/api/auth/register", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        name: data.get("name"),
        password: data.get("password"),
        role: data.get("role"),
      }),
    });
    const body = await response.json();
    if (!response.ok) {
      say(authStatus, body.error || "That account could not be created.", true);
      return;
    }
    if (body.role !== "owner") {
      say(authStatus, "Account created. Open the phone app and sign in with that name.", false);
      return;
    }
    localStorage.setItem("mezzayn-token", body.token);
    refresh();
  } catch (error) {
    say(authStatus, error.message, true);
  }
});

document.querySelector("#leave").addEventListener("click", async () => {
  try { await api("/api/auth/logout", { method: "POST" }); } catch (_) {}
  localStorage.removeItem("mezzayn-token");
  showGate("");
});

document.querySelector("#alerts").addEventListener("click", () => {
  if ("Notification" in window) Notification.requestPermission();
});

async function download(path, name) {
  const response = await fetch(path, { headers: { Authorization: "Bearer " + token() } });
  if (!response.ok) {
    say(reserveStatus, "The export did not start.", true);
    return;
  }
  const url = URL.createObjectURL(await response.blob());
  const link = document.createElement("a");
  link.href = url;
  link.download = name;
  link.click();
  URL.revokeObjectURL(url);
}

document.querySelector("#csv").addEventListener("click", () => download("/api/report.csv", "mezzayn.csv"));
document.querySelector("#pdf").addEventListener("click", () => download("/api/report.pdf", "mezzayn.pdf"));

reserve.elements.seat.addEventListener("change", fillReserve);
reserve.addEventListener("submit", async (event) => {
  event.preventDefault();
  const data = new FormData(reserve);
  try {
    paint(await api("/api/bookings", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        seatId: data.get("seat"),
        slot: data.get("slot"),
        service: data.get("service"),
        customer: data.get("customer"),
      }),
    }));
    reserve.elements.customer.value = "";
    say(reserveStatus, "Reserved.", false);
  } catch (error) {
    say(reserveStatus, error.message, true);
  }
});

hoursForm.addEventListener("submit", async (event) => {
  event.preventDefault();
  const data = new FormData(hoursForm);
  try {
    paint(await api("/api/settings", {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ open: data.get("open"), close: data.get("close") }),
    }));
    say(hoursStatus, "Hours saved.", false);
  } catch (error) {
    say(hoursStatus, error.message, true);
  }
});

seatForm.addEventListener("submit", async (event) => {
  event.preventDefault();
  const data = new FormData(seatForm);
  try {
    paint(await api("/api/seats", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ name: data.get("name"), barber: data.get("barber") }),
    }));
    seatForm.reset();
    say(seatStatus, "Chair added.", false);
  } catch (error) {
    say(seatStatus, error.message, true);
  }
});

if (token()) refresh();
setInterval(() => { refresh(); notices(); }, 4000);

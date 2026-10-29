const crypto = require("crypto");
const { MongoClient, ObjectId } = require("mongodb");

const services = [
  ["Haircut", 25],
  ["Beard", 15],
  ["Haircut and beard", 35],
];
const roles = ["customer", "staff", "owner"];

function today() {
  return new Intl.DateTimeFormat("en-CA", {
    timeZone: "Africa/Cairo",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).format(new Date());
}

function clock(value) {
  return /^([01]\d|2[0-3]):(00|30)$/.test(value);
}

function slots(open, close) {
  const [oh, om] = open.split(":").map(Number);
  const [ch, cm] = close.split(":").map(Number);
  const end = ch * 60 + cm;
  const out = [];
  for (let cur = oh * 60 + om; cur < end; cur += 30) {
    out.push(String(Math.floor(cur / 60)).padStart(2, "0") + ":" + String(cur % 60).padStart(2, "0"));
  }
  return out;
}

function hashPassword(password, salt = crypto.randomBytes(16).toString("hex")) {
  const hash = crypto.pbkdf2Sync(password, salt, 120000, 32, "sha256").toString("hex");
  return salt + ":" + hash;
}

function checkPassword(password, stored) {
  const [salt, hash] = String(stored || "").split(":");
  if (!salt || !hash) return false;
  const got = crypto.pbkdf2Sync(password, salt, 120000, 32, "sha256");
  const have = Buffer.from(hash, "hex");
  return have.length === got.length && crypto.timingSafeEqual(have, got);
}

async function database() {
  if (!global._mezzaynDb) {
    const client = new MongoClient(process.env.MONGODB_URI || "mongodb://127.0.0.1:27017", {
      serverSelectionTimeoutMS: 8000,
    });
    await client.connect();
    const db = client.db(process.env.MONGODB_DB || "mezzayn");
    await db.collection("users").createIndex({ name: 1 }, { unique: true });
    await db.collection("sessions").createIndex({ token: 1 }, { unique: true });
    await db.collection("bookings").createIndex(
      { seatId: 1, slot: 1 },
      { unique: true, partialFilterExpression: { status: "booked" } },
    );
    await db.collection("notices").createIndex({ at: 1 });
    if ((await db.collection("seats").countDocuments()) === 0) {
      await db.collection("seats").insertMany([
        { name: "Seat 1", barber: "Barber 1" },
        { name: "Seat 2", barber: "Barber 2" },
      ]);
    }
    if (!(await db.collection("settings").findOne({ _id: "shop" }))) {
      await db.collection("settings").insertOne({ _id: "shop", open: "09:00", close: "18:00" });
    }
    global._mezzaynDb = db;
  }
  return global._mezzaynDb;
}

async function hoursOf(db) {
  const doc = await db.collection("settings").findOne({ _id: "shop" });
  return { open: doc?.open || "09:00", close: doc?.close || "18:00" };
}

function bookingOf(row) {
  return {
    id: row._id.toHexString(),
    seatId: row.seatId,
    seat: row.seat,
    barber: row.barber,
    service: row.service,
    customer: row.customer,
    slot: row.slot,
    price: Number(row.price),
    status: row.status,
  };
}

function charts(rows, day, hours) {
  const dayBars = slots(hours.open, hours.close).map((label) => ({
    label,
    amount: rows.filter((row) => row.slot === day + " " + label).reduce((sum, row) => sum + Number(row.price), 0),
  }));
  const start = new Date(day + "T12:00:00Z");
  const week = [];
  for (let i = 6; i >= 0; i -= 1) {
    const date = new Date(start.getTime() - i * 86400000).toISOString().slice(0, 10);
    week.push({
      label: date.slice(5),
      amount: rows.filter((row) => row.slot.startsWith(date)).reduce((sum, row) => sum + Number(row.price), 0),
    });
  }
  const monthStart = day.slice(0, 8) + "01";
  const last = new Date(Date.UTC(Number(day.slice(0, 4)), Number(day.slice(5, 7)), 0)).getUTCDate();
  const month = [];
  for (let n = 1; n <= last; n += 1) {
    const date = monthStart.slice(0, 8) + String(n).padStart(2, "0");
    if (date > day) break;
    month.push({
      label: String(n),
      amount: rows.filter((row) => row.slot.startsWith(date)).reduce((sum, row) => sum + Number(row.price), 0),
    });
  }
  return { day: dayBars, week, month };
}

async function board(db, user) {
  const day = today();
  const hours = await hoursOf(db);
  const openSlots = slots(hours.open, hours.close);
  const taken = await db.collection("bookings").find({ status: "booked", slot: new RegExp("^" + day) }).toArray();
  const seats = await db.collection("seats").find().sort({ name: 1 }).toArray();
  const seatRows = seats.map((seat) => {
    const id = seat._id.toHexString();
    const busy = new Set(taken.filter((row) => row.seatId === id).map((row) => row.slot.slice(-5)));
    return {
      id,
      name: seat.name,
      barber: seat.barber,
      free: openSlots.filter((slot) => !busy.has(slot)),
    };
  });
  const booked = await db.collection("bookings").find({ status: "booked" }).toArray();
  const sum = (from) => booked.filter((row) => {
    const when = row.slot.slice(0, 10);
    return when >= from && when <= day;
  }).reduce((total, row) => total + Number(row.price), 0);
  const weekFrom = new Date(day + "T12:00:00Z");
  weekFrom.setUTCDate(weekFrom.getUTCDate() - 6);
  const payload = {
    day,
    hours,
    services: services.map(([name, price]) => ({ name, price })),
    seats: seatRows,
    bookings: taken.map(bookingOf),
  };
  if (user.role === "customer") {
    payload.bookings = payload.bookings.filter((row) => row.customer === user.name);
    return payload;
  }
  if (user.role === "owner") {
    payload.totals = {
      day: sum(day),
      week: sum(weekFrom.toISOString().slice(0, 10)),
      month: sum(day.slice(0, 8) + "01"),
    };
    payload.charts = charts(booked, day, hours);
  }
  return payload;
}

async function notice(db, kind, text, customer) {
  await db.collection("notices").insertOne({
    at: new Date().toISOString(),
    kind,
    text,
    customer,
  });
}

async function readJson(req) {
  if (req.body && typeof req.body === "object") return req.body;
  if (typeof req.body === "string" && req.body) {
    try { return JSON.parse(req.body); } catch { return {}; }
  }
  const chunks = [];
  for await (const chunk of req) chunks.push(chunk);
  const raw = Buffer.concat(chunks).toString("utf8");
  if (!raw) return {};
  try { return JSON.parse(raw); } catch { return {}; }
}

function tokenOf(req) {
  const header = req.headers.authorization || "";
  return header.startsWith("Bearer ") ? header.slice(7).trim() : "";
}

async function userFrom(db, req) {
  const token = tokenOf(req);
  if (!token) return null;
  const session = await db.collection("sessions").findOne({ token });
  if (!session || session.expires < new Date().toISOString()) return null;
  return { id: session.userId, name: session.name, role: session.role, token };
}

function send(res, status, body, type) {
  res.statusCode = status;
  if (type === "csv") {
    res.setHeader("Content-Type", "text/csv; charset=utf-8");
    res.setHeader("Content-Disposition", "attachment; filename=\"mezzayn.csv\"");
    res.end(body);
    return;
  }
  if (type === "pdf") {
    res.setHeader("Content-Type", "application/pdf");
    res.setHeader("Content-Disposition", "attachment; filename=\"mezzayn.pdf\"");
    res.end(body);
    return;
  }
  res.setHeader("Content-Type", "application/json; charset=utf-8");
  res.end(JSON.stringify(body));
}

function idOf(raw) {
  return ObjectId.isValid(raw) ? new ObjectId(raw) : null;
}

async function handle(req, res) {
  try {
    const db = await database();
    const url = new URL(req.url, "http://desk.local");
    const path = url.pathname.replace(/\/$/, "") || "/";
    const method = req.method || "GET";
    if (method === "POST" && path === "/api/auth/register") return register(db, req, res);
    if (method === "POST" && path === "/api/auth/login") return login(db, req, res);
    const user = await userFrom(db, req);
    if (!user) return send(res, 401, { error: "Sign in to continue." });
    if (method === "POST" && path === "/api/auth/logout") {
      await db.collection("sessions").deleteOne({ token: user.token });
      return send(res, 200, { ok: true });
    }
    if (method === "GET" && path === "/api/board") return send(res, 200, await board(db, user));
    if (method === "GET" && path === "/api/notices") return listNotices(db, user, url, res);
    if (method === "PUT" && path === "/api/settings") return saveHours(db, user, req, res);
    if (method === "POST" && path === "/api/seats") return addSeat(db, user, req, res);
    if (method === "POST" && path === "/api/bookings") return book(db, user, req, res);
    const booking = path.match(/^\/api\/bookings\/([a-f0-9]{24})$/);
    if (booking && method === "PATCH") return move(db, user, booking[1], req, res);
    if (booking && method === "DELETE") return cancel(db, user, booking[1], res);
    if (method === "GET" && path === "/api/report.csv") return report(db, user, res, "csv");
    if (method === "GET" && path === "/api/report.pdf") return report(db, user, res, "pdf");
    return send(res, 404, { error: "That page does not exist." });
  } catch (error) {
    return send(res, 500, { error: "The desk could not finish that." });
  }
}

async function register(db, req, res) {
  const body = await readJson(req);
  const name = String(body.name || "").trim();
  const password = String(body.password || "");
  const role = String(body.role || "");
  if (name.length < 2 || password.length < 8 || !roles.includes(role)) {
    return send(res, 400, { error: "Use your name, a role, and a password of 8 characters." });
  }
  try {
    const inserted = await db.collection("users").insertOne({
      name,
      role,
      password: hashPassword(password),
    });
    return send(res, 200, await openSession(db, { _id: inserted.insertedId, name, role }));
  } catch (error) {
    if (error && error.code === 11000) return send(res, 409, { error: "That name is already registered." });
    throw error;
  }
}

async function login(db, req, res) {
  const body = await readJson(req);
  const name = String(body.name || "").trim();
  const user = await db.collection("users").findOne({ name });
  if (!user || !checkPassword(String(body.password || ""), user.password)) {
    return send(res, 401, { error: "That name or password does not match." });
  }
  return send(res, 200, await openSession(db, user));
}

async function openSession(db, user) {
  const token = crypto.randomBytes(32).toString("hex");
  const expires = new Date(Date.now() + 30 * 86400000).toISOString();
  await db.collection("sessions").insertOne({
    token,
    userId: user._id.toHexString(),
    name: user.name,
    role: user.role,
    expires,
  });
  return { token, name: user.name, role: user.role };
}

async function listNotices(db, user, url, res) {
  const since = url.searchParams.get("since") || "";
  const query = since ? { at: { $gt: since } } : {};
  let rows = await db.collection("notices").find(query).sort({ at: 1 }).limit(40).toArray();
  if (user.role === "customer") rows = rows.filter((row) => row.customer === user.name);
  return send(res, 200, {
    notices: rows.map((row) => ({
      id: row._id.toHexString(),
      at: row.at,
      kind: row.kind,
      text: row.text,
    })),
  });
}

async function saveHours(db, user, req, res) {
  if (user.role !== "owner") return send(res, 403, { error: "Only the owner changes the hours." });
  const body = await readJson(req);
  const open = String(body.open || "");
  const close = String(body.close || "");
  if (!clock(open) || !clock(close) || open >= close) {
    return send(res, 400, { error: "Opening has to be earlier than closing, on a half hour." });
  }
  await db.collection("settings").updateOne({ _id: "shop" }, { $set: { open, close } }, { upsert: true });
  return send(res, 200, await board(db, user));
}

async function addSeat(db, user, req, res) {
  if (user.role !== "owner") return send(res, 403, { error: "Only the owner adds a chair." });
  const body = await readJson(req);
  const name = String(body.name || "").trim();
  const barber = String(body.barber || "").trim();
  if (!name || !barber) return send(res, 400, { error: "A seat needs a name and a barber." });
  await db.collection("seats").insertOne({ name, barber });
  return send(res, 200, await board(db, user));
}

async function book(db, user, req, res) {
  const body = await readJson(req);
  const hours = await hoursOf(db);
  const seat = await db.collection("seats").findOne({ _id: idOf(String(body.seatId || "")) });
  const service = services.find(([name]) => name === body.service);
  const customer = user.role === "customer" ? user.name : String(body.customer || "").trim();
  const slot = String(body.slot || "").trim();
  if (!seat || !service || !customer || !slots(hours.open, hours.close).includes(slot)) {
    return send(res, 400, { error: "Choose a seat, a service, and an open time." });
  }
  const doc = {
    seatId: seat._id.toHexString(),
    seat: seat.name,
    barber: seat.barber,
    service: service[0],
    customer,
    slot: today() + " " + slot,
    price: service[1],
    status: "booked",
  };
  try {
    await db.collection("bookings").insertOne(doc);
  } catch (error) {
    if (error && error.code === 11000) return send(res, 409, { error: "That seat is already booked." });
    throw error;
  }
  await notice(db, "confirmed", "Confirmed: " + customer + ", " + service[0] + ", " + seat.name + " at " + slot, customer);
  return send(res, 200, await board(db, user));
}

async function owned(db, user, raw) {
  const row = await db.collection("bookings").findOne({ _id: idOf(raw), status: "booked" });
  if (!row) return { error: [404, "That booking does not exist."] };
  if (user.role === "customer" && row.customer !== user.name) return { error: [403, "That booking is not yours."] };
  return { row };
}

async function move(db, user, raw, req, res) {
  const found = await owned(db, user, raw);
  if (found.error) return send(res, found.error[0], { error: found.error[1] });
  const body = await readJson(req);
  const hours = await hoursOf(db);
  const slot = String(body.slot || "").trim();
  const seat = await db.collection("seats").findOne({ _id: idOf(String(body.seatId || found.row.seatId)) });
  if (!seat || !slots(hours.open, hours.close).includes(slot)) {
    return send(res, 400, { error: "Choose an open time." });
  }
  const next = found.row.slot.slice(0, 10) + " " + slot;
  try {
    await db.collection("bookings").updateOne(
      { _id: found.row._id, status: "booked" },
      { $set: { slot: next, seatId: seat._id.toHexString(), seat: seat.name, barber: seat.barber } },
    );
  } catch (error) {
    if (error && error.code === 11000) return send(res, 409, { error: "That seat is already booked." });
    throw error;
  }
  await notice(db, "rescheduled", "Rescheduled: " + found.row.customer + " to " + slot + " at " + seat.name, found.row.customer);
  return send(res, 200, await board(db, user));
}

async function cancel(db, user, raw, res) {
  const found = await owned(db, user, raw);
  if (found.error) return send(res, found.error[0], { error: found.error[1] });
  await db.collection("bookings").updateOne({ _id: found.row._id }, { $set: { status: "cancelled" } });
  await notice(
    db,
    "cancelled",
    "Cancelled: " + found.row.customer + ", " + found.row.seat + " at " + found.row.slot.slice(-5),
    found.row.customer,
  );
  return send(res, 200, await board(db, user));
}

async function report(db, user, res, kind) {
  if (user.role !== "owner") return send(res, 403, { error: "Only the owner exports the day." });
  const rows = await db.collection("bookings").find().sort({ slot: 1 }).toArray();
  if (kind === "csv") {
    const lines = ["date,seat,barber,service,customer,amount,status"];
    rows.forEach((row) => {
      lines.push([row.slot, row.seat, row.barber, row.service, row.customer, String(row.price), row.status]
        .map((part) => String(part).replace(/,/g, " ")).join(","));
    });
    return send(res, 200, lines.join("\n"), "csv");
  }
  const view = await board(db, user);
  const lines = [
    "Al-mezzayn",
    "Day " + view.totals.day,
    "Week " + view.totals.week,
    "Month " + view.totals.month,
  ];
  rows.filter((row) => row.status === "booked").forEach((row) => {
    lines.push(row.slot + "  " + row.seat + "  " + row.service + "  " + row.price);
  });
  return send(res, 200, pdf(lines.slice(0, 40)), "pdf");
}

function pdf(lines) {
  let stream = "BT\n/F1 12 Tf\n72 740 Td\n16 TL\n";
  lines.forEach((line, index) => {
    const safe = line.replace(/\\/g, "\\\\").replace(/\(/g, "\\(").replace(/\)/g, "\\)");
    if (index > 0) stream += "T*\n";
    stream += "(" + safe + ") Tj\n";
  });
  stream += "ET\n";
  const objects = [
    "<< /Type /Catalog /Pages 2 0 R >>",
    "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
    "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>",
    "<< /Length " + Buffer.byteLength(stream) + " >>\nstream\n" + stream + "endstream",
    "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
  ];
  let body = "%PDF-1.4\n";
  const offsets = [0];
  objects.forEach((obj, index) => {
    offsets.push(Buffer.byteLength(body));
    body += (index + 1) + " 0 obj\n" + obj + "\nendobj\n";
  });
  const xref = Buffer.byteLength(body);
  body += "xref\n0 " + (objects.length + 1) + "\n";
  body += "0000000000 65535 f \n";
  offsets.slice(1).forEach((offset) => {
    body += String(offset).padStart(10, "0") + " 00000 n \n";
  });
  body += "trailer << /Size " + (objects.length + 1) + " /Root 1 0 R >>\nstartxref\n" + xref + "\n%%EOF\n";
  return Buffer.from(body);
}

module.exports = { handle };

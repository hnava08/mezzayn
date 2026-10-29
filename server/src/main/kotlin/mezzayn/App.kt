package mezzayn

import com.fasterxml.jackson.module.kotlin.kotlinModule
import com.mongodb.MongoWriteException
import com.mongodb.client.MongoCollection
import com.mongodb.client.model.Filters
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import com.mongodb.client.model.Sorts
import com.mongodb.client.model.Updates
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.jackson.jackson
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticResources
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import org.bson.Document
import org.bson.types.ObjectId
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

data class AuthIn(val name: String = "", val password: String = "", val role: String = "")
data class SeatIn(val name: String = "", val barber: String = "")
data class BookingIn(val seatId: String = "", val service: String = "", val customer: String = "", val slot: String = "")
data class MoveIn(val slot: String = "", val seatId: String = "")
data class HoursIn(val open: String = "", val close: String = "")

private val services = listOf("Haircut" to 25, "Beard" to 15, "Haircut and beard" to 35)
private val roles = setOf("customer", "staff", "owner")
private val cairo = ZoneId.of("Africa/Cairo")
private val clock = DateTimeFormatter.ofPattern("HH:mm")
private val random = SecureRandom()

fun main() {
    val db = com.mongodb.client.MongoClients.create(System.getenv("MONGODB_URI") ?: "mongodb://127.0.0.1:27017")
        .getDatabase(System.getenv("MONGODB_DB") ?: "mezzayn")
    val seats = db.getCollection("seats")
    val bookings = db.getCollection("bookings")
    val users = db.getCollection("users")
    val sessions = db.getCollection("sessions")
    val settings = db.getCollection("settings")
    val notices = db.getCollection("notices")
    users.createIndex(Indexes.ascending("name"), IndexOptions().unique(true))
    sessions.createIndex(Indexes.ascending("token"), IndexOptions().unique(true))
    bookings.createIndex(
        Indexes.compoundIndex(Indexes.ascending("seatId"), Indexes.ascending("slot")),
        IndexOptions().unique(true).partialFilterExpression(Filters.eq("status", "booked")),
    )
    if (seats.countDocuments() == 0L) {
        seats.insertMany(listOf(
            Document("name", "Seat 1").append("barber", "Barber 1"),
            Document("name", "Seat 2").append("barber", "Barber 2"),
        ))
    }
    if (settings.find(Filters.eq("_id", "shop")).firstOrNull() == null) {
        settings.insertOne(Document("_id", "shop").append("open", "09:00").append("close", "18:00"))
    }
    embeddedServer(Netty, port = 8092, host = "0.0.0.0") {
        install(ContentNegotiation) { jackson { registerModule(kotlinModule()) } }
        routing {
            post("/api/auth/register") {
                val body = call.receive<AuthIn>()
                val name = body.name.trim()
                if (name.length < 2 || body.password.length < 8 || body.role !in roles) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Use your name, a role, and a password of 8 characters."))
                    return@post
                }
                val doc = Document("name", name).append("role", body.role).append("password", hashPassword(body.password))
                val id = try {
                    users.insertOne(doc)
                    doc.getObjectId("_id")
                } catch (_: MongoWriteException) {
                    call.respond(HttpStatusCode.Conflict, mapOf("error" to "That name is already registered."))
                    return@post
                }
                call.respond(openSession(sessions, id, name, body.role))
            }
            post("/api/auth/login") {
                val body = call.receive<AuthIn>()
                val user = users.find(Filters.eq("name", body.name.trim())).firstOrNull()
                if (user == null || !checkPassword(body.password, user.getString("password"))) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "That name or password does not match."))
                    return@post
                }
                call.respond(openSession(sessions, user.getObjectId("_id"), user.getString("name"), user.getString("role")))
            }
            post("/api/auth/logout") {
                val user = call.user(sessions) ?: return@post
                sessions.deleteOne(Filters.eq("token", user.getString("token")))
                call.respond(mapOf("ok" to true))
            }
            get("/api/board") {
                val user = call.user(sessions) ?: return@get
                call.respond(board(seats, bookings, settings, user))
            }
            get("/api/notices") {
                val user = call.user(sessions) ?: return@get
                val since = call.request.queryParameters["since"].orEmpty()
                val query = if (since.isEmpty()) Filters.exists("at") else Filters.gt("at", since)
                var rows = notices.find(query).sort(Sorts.ascending("at")).limit(40).toList()
                if (user.getString("role") == "customer") rows = rows.filter { it.getString("customer") == user.getString("name") }
                call.respond(mapOf("notices" to rows.map {
                    mapOf("id" to it.getObjectId("_id").toHexString(), "at" to it.getString("at"), "kind" to it.getString("kind"), "text" to it.getString("text"))
                }))
            }
            put("/api/settings") {
                val user = call.user(sessions) ?: return@put
                if (user.getString("role") != "owner") {
                    call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Only the owner changes the hours."))
                    return@put
                }
                val body = call.receive<HoursIn>()
                if (!clock(body.open) || !clock(body.close) || body.open >= body.close) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Opening has to be earlier than closing, on a half hour."))
                    return@put
                }
                settings.updateOne(Filters.eq("_id", "shop"), Updates.combine(Updates.set("open", body.open), Updates.set("close", body.close)))
                call.respond(board(seats, bookings, settings, user))
            }
            post("/api/seats") {
                val user = call.user(sessions) ?: return@post
                if (user.getString("role") != "owner") {
                    call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Only the owner adds a chair."))
                    return@post
                }
                val body = call.receive<SeatIn>()
                if (body.name.trim().isEmpty() || body.barber.trim().isEmpty()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "A seat needs a name and a barber."))
                    return@post
                }
                seats.insertOne(Document("name", body.name.trim()).append("barber", body.barber.trim()))
                call.respond(board(seats, bookings, settings, user))
            }
            post("/api/bookings") {
                val user = call.user(sessions) ?: return@post
                val body = call.receive<BookingIn>()
                val hours = hoursOf(settings)
                val seat = seats.find(Filters.eq("_id", objectId(body.seatId))).firstOrNull()
                val price = services.firstOrNull { it.first == body.service }?.second
                val customer = if (user.getString("role") == "customer") user.getString("name") else body.customer.trim()
                if (seat == null || price == null || customer.isEmpty() || body.slot !in daySlots(hours)) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Choose a seat, a service, and an open time."))
                    return@post
                }
                val doc = Document("seatId", seat.getObjectId("_id").toHexString())
                    .append("seat", seat.getString("name"))
                    .append("barber", seat.getString("barber"))
                    .append("service", body.service)
                    .append("customer", customer)
                    .append("slot", today() + " " + body.slot)
                    .append("price", price)
                    .append("status", "booked")
                try {
                    bookings.insertOne(doc)
                } catch (_: MongoWriteException) {
                    call.respond(HttpStatusCode.Conflict, mapOf("error" to "That seat is already booked."))
                    return@post
                }
                notice(notices, "confirmed", "Confirmed: $customer, ${body.service}, ${seat.getString("name")} at ${body.slot}", customer)
                call.respond(board(seats, bookings, settings, user))
            }
            patch("/api/bookings/{id}") {
                val user = call.user(sessions) ?: return@patch
                val found = owned(bookings, user, call.parameters["id"].orEmpty())
                if (found.second != null) {
                    call.respond(found.second!!.first, mapOf("error" to found.second!!.second))
                    return@patch
                }
                val row = found.first!!
                val body = call.receive<MoveIn>()
                val hours = hoursOf(settings)
                val seat = seats.find(Filters.eq("_id", objectId(body.seatId.ifEmpty { row.getString("seatId") }))).firstOrNull()
                if (seat == null || body.slot !in daySlots(hours)) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Choose an open time."))
                    return@patch
                }
                try {
                    bookings.updateOne(
                        Filters.eq("_id", row.getObjectId("_id")),
                        Updates.combine(
                            Updates.set("slot", row.getString("slot").substringBefore(" ") + " " + body.slot),
                            Updates.set("seatId", seat.getObjectId("_id").toHexString()),
                            Updates.set("seat", seat.getString("name")),
                            Updates.set("barber", seat.getString("barber")),
                        ),
                    )
                } catch (_: MongoWriteException) {
                    call.respond(HttpStatusCode.Conflict, mapOf("error" to "That seat is already booked."))
                    return@patch
                }
                notice(notices, "rescheduled", "Rescheduled: ${row.getString("customer")} to ${body.slot} at ${seat.getString("name")}", row.getString("customer"))
                call.respond(board(seats, bookings, settings, user))
            }
            delete("/api/bookings/{id}") {
                val user = call.user(sessions) ?: return@delete
                val found = owned(bookings, user, call.parameters["id"].orEmpty())
                if (found.second != null) {
                    call.respond(found.second!!.first, mapOf("error" to found.second!!.second))
                    return@delete
                }
                val row = found.first!!
                bookings.updateOne(Filters.eq("_id", row.getObjectId("_id")), Updates.set("status", "cancelled"))
                notice(notices, "cancelled", "Cancelled: ${row.getString("customer")}, ${row.getString("seat")} at ${row.getString("slot").takeLast(5)}", row.getString("customer"))
                call.respond(board(seats, bookings, settings, user))
            }
            get("/api/report.csv") {
                val user = call.user(sessions) ?: return@get
                if (user.getString("role") != "owner") {
                    call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Only the owner exports the day."))
                    return@get
                }
                call.respondText(csv(bookings), ContentType.Text.CSV)
            }
            get("/api/report.pdf") {
                val user = call.user(sessions) ?: return@get
                if (user.getString("role") != "owner") {
                    call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Only the owner exports the day."))
                    return@get
                }
                call.respondBytes(pdf(bookings, seats, settings, user), ContentType.Application.Pdf)
            }
            staticResources("/", "static")
        }
    }.start(wait = true)
}

private suspend fun ApplicationCall.user(sessions: MongoCollection<Document>): Document? {
    val header = request.headers["Authorization"].orEmpty()
    if (!header.startsWith("Bearer ")) {
        respond(HttpStatusCode.Unauthorized, mapOf("error" to "Sign in to continue."))
        return null
    }
    val session = sessions.find(Filters.eq("token", header.removePrefix("Bearer ").trim())).firstOrNull()
    if (session == null || session.getString("expires") < Instant.now().toString()) {
        respond(HttpStatusCode.Unauthorized, mapOf("error" to "Sign in to continue."))
        return null
    }
    return session
}

private fun openSession(sessions: MongoCollection<Document>, id: ObjectId, name: String, role: String): Map<String, String> {
    val token = ByteArray(32).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
    val expires = Instant.now().plusSeconds(30L * 86400).toString()
    sessions.insertOne(Document("token", token).append("userId", id.toHexString()).append("name", name).append("role", role).append("expires", expires))
    return mapOf("token" to token, "name" to name, "role" to role)
}

private fun owned(bookings: MongoCollection<Document>, user: Document, raw: String): Pair<Document?, Pair<HttpStatusCode, String>?> {
    val row = bookings.find(Filters.and(Filters.eq("_id", objectId(raw)), Filters.eq("status", "booked"))).firstOrNull()
        ?: return null to (HttpStatusCode.NotFound to "That booking does not exist.")
    if (user.getString("role") == "customer" && row.getString("customer") != user.getString("name")) {
        return null to (HttpStatusCode.Forbidden to "That booking is not yours.")
    }
    return row to null
}

private fun notice(notices: MongoCollection<Document>, kind: String, text: String, customer: String) {
    notices.insertOne(Document("at", Instant.now().toString()).append("kind", kind).append("text", text).append("customer", customer))
}

private fun hoursOf(settings: MongoCollection<Document>): Pair<String, String> {
    val doc = settings.find(Filters.eq("_id", "shop")).firstOrNull()
    return (doc?.getString("open") ?: "09:00") to (doc?.getString("close") ?: "18:00")
}

private fun board(
    seats: MongoCollection<Document>,
    bookings: MongoCollection<Document>,
    settings: MongoCollection<Document>,
    user: Document,
): Map<String, Any> {
    val day = today()
    val hours = hoursOf(settings)
    val openSlots = daySlots(hours)
    val taken = bookings.find(Filters.and(Filters.eq("status", "booked"), Filters.regex("slot", "^$day"))).toList()
    val seatRows = seats.find().sort(Sorts.ascending("name")).map { seat ->
        val id = seat.getObjectId("_id").toHexString()
        val busy = taken.filter { it.getString("seatId") == id }.map { it.getString("slot").takeLast(5) }.toSet()
        mapOf("id" to id, "name" to seat.getString("name"), "barber" to seat.getString("barber"), "free" to openSlots.filter { it !in busy })
    }.toList()
    val payload = mutableMapOf<String, Any>(
        "day" to day,
        "hours" to mapOf("open" to hours.first, "close" to hours.second),
        "services" to services.map { mapOf("name" to it.first, "price" to it.second) },
        "seats" to seatRows,
        "bookings" to taken.map { it.toBooking() },
    )
    if (user.getString("role") == "customer") {
        payload["bookings"] = taken.filter { it.getString("customer") == user.getString("name") }.map { it.toBooking() }
        return payload
    }
    if (user.getString("role") == "owner") {
        val booked = bookings.find(Filters.eq("status", "booked")).toList()
        fun sum(from: String) = booked.filter { it.getString("slot").substringBefore(" ") in from..day }.sumOf { it.getInteger("price") }
        payload["totals"] = mapOf(
            "day" to sum(day),
            "week" to sum(LocalDate.parse(day).minusDays(6).toString()),
            "month" to sum(day.take(8) + "01"),
        )
        payload["charts"] = mapOf(
            "day" to openSlots.map { slot -> mapOf("label" to slot, "amount" to booked.filter { it.getString("slot") == "$day $slot" }.sumOf { it.getInteger("price") }) },
            "week" to (0..6).reversed().map { offset ->
                val date = LocalDate.parse(day).minusDays(offset.toLong()).toString()
                mapOf("label" to date.drop(5), "amount" to booked.filter { it.getString("slot").startsWith(date) }.sumOf { it.getInteger("price") })
            },
            "month" to (1..LocalDate.parse(day).dayOfMonth).map { n ->
                val date = day.take(8) + n.toString().padStart(2, '0')
                mapOf("label" to n.toString(), "amount" to booked.filter { it.getString("slot").startsWith(date) }.sumOf { it.getInteger("price") })
            },
        )
    }
    return payload
}

private fun csv(bookings: MongoCollection<Document>): String {
    val lines = mutableListOf("date,seat,barber,service,customer,amount,status")
    bookings.find().sort(Sorts.ascending("slot")).forEach { row ->
        lines.add(listOf(row.getString("slot"), row.getString("seat"), row.getString("barber"), row.getString("service"), row.getString("customer"), row.getInteger("price").toString(), row.getString("status")).joinToString(",") { it.replace(",", " ") })
    }
    return lines.joinToString("\n")
}

private fun pdf(bookings: MongoCollection<Document>, seats: MongoCollection<Document>, settings: MongoCollection<Document>, user: Document): ByteArray {
    val view = board(seats, bookings, settings, user)
    @Suppress("UNCHECKED_CAST")
    val totals = view["totals"] as Map<String, Int>
    val lines = mutableListOf("Al-mezzayn", "Day ${totals["day"]}", "Week ${totals["week"]}", "Month ${totals["month"]}")
    bookings.find(Filters.eq("status", "booked")).sort(Sorts.ascending("slot")).forEach { row ->
        lines.add("${row.getString("slot")}  ${row.getString("seat")}  ${row.getString("service")}  ${row.getInteger("price")}")
    }
    return page(lines.take(40))
}

private fun page(lines: List<String>): ByteArray {
    val stream = buildString {
        append("BT\n/F1 12 Tf\n72 740 Td\n16 TL\n")
        lines.forEachIndexed { index, line ->
            val safe = line.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")
            if (index > 0) append("T*\n")
            append("($safe) Tj\n")
        }
        append("ET\n")
    }.toByteArray()
    val objects = listOf(
        "<< /Type /Catalog /Pages 2 0 R >>",
        "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>",
        "<< /Length ${stream.size} >>\nstream\n${String(stream)}endstream",
        "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
    )
    val body = StringBuilder("%PDF-1.4\n")
    val offsets = mutableListOf(0)
    objects.forEachIndexed { index, obj ->
        offsets.add(body.length)
        body.append("${index + 1} 0 obj\n$obj\nendobj\n")
    }
    val xref = body.length
    body.append("xref\n0 ${objects.size + 1}\n")
    body.append("0000000000 65535 f \n")
    offsets.drop(1).forEach { body.append(it.toString().padStart(10, '0') + " 00000 n \n") }
    body.append("trailer << /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
    return body.toString().toByteArray()
}

private fun daySlots(hours: Pair<String, String>): List<String> {
    val slots = mutableListOf<String>()
    var time = java.time.LocalTime.parse(hours.first)
    val close = java.time.LocalTime.parse(hours.second)
    while (time.isBefore(close)) {
        slots.add(time.format(clock))
        time = time.plusMinutes(30)
    }
    return slots
}

private fun clock(value: String) = value.matches(Regex("([01]\\d|2[0-3]):(00|30)"))
private fun today() = LocalDate.now(cairo).toString()
private fun objectId(raw: String): ObjectId? = if (ObjectId.isValid(raw)) ObjectId(raw) else null

private fun hashPassword(password: String, salt: String = ByteArray(16).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }): String {
    val spec = PBEKeySpec(password.toCharArray(), salt.toByteArray(), 120000, 256)
    val hash = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded.joinToString("") { "%02x".format(it) }
    return "$salt:$hash"
}

private fun checkPassword(password: String, stored: String): Boolean {
    val salt = stored.substringBefore(":")
    val have = stored.substringAfter(":")
    val got = hashPassword(password, salt).substringAfter(":")
    return MessageDigest.isEqual(got.toByteArray(), have.toByteArray())
}

private fun Document.toBooking(): Map<String, Any> = mapOf(
    "id" to getObjectId("_id").toHexString(),
    "seatId" to getString("seatId"),
    "seat" to getString("seat"),
    "barber" to getString("barber"),
    "service" to getString("service"),
    "customer" to getString("customer"),
    "slot" to getString("slot"),
    "price" to getInteger("price"),
    "status" to getString("status"),
)

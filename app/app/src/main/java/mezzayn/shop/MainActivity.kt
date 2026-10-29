package mezzayn.shop

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.util.concurrent.Executors

class MainActivity : android.app.Activity() {
    private val paper = 0xFFF6F6F4.toInt()
    private val ink = 0xFF111111.toInt()
    private val muted = 0xFF3A3A3A.toInt()
    private val white = 0xFFFFFFFF.toInt()
    private val line = 0xFFC8C8C8.toInt()
    private val desk = "http://192.168.1.207:8093"
    private val io = Executors.newSingleThreadExecutor()
    private val ticker = Handler(Looper.getMainLooper())
    private lateinit var page: LinearLayout
    private var since = Instant.now().toString()
    private val poll = object : Runnable {
        override fun run() {
            if (token().isNotEmpty()) pullNotices()
            ticker.postDelayed(this, 8000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        val channel = NotificationChannel("bookings", "Bookings", NotificationManager.IMPORTANCE_DEFAULT)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        val scroll = ScrollView(this)
        scroll.setBackgroundColor(paper)
        page = column(20)
        scroll.addView(page)
        setContentView(scroll)
        if (token().isEmpty()) signIn() else load()
    }

    override fun onStart() {
        super.onStart()
        ticker.post(poll)
    }

    override fun onStop() {
        ticker.removeCallbacks(poll)
        super.onStop()
    }

    private fun signIn() {
        page.removeAllViews()
        page.addView(title("Sign in"))
        page.addView(note("The same account opens the chair list."))
        val name = field("Name")
        val password = field("Password")
        password.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        val role = android.widget.Spinner(this)
        role.adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("customer", "staff", "owner"))
        page.addView(note("Role for a new account"))
        page.addView(role)
        val enter = button("Sign in")
        val create = quiet("Create account")
        val hint = note("")
        enter.setOnClickListener { account("/api/auth/login", name, password, "customer", hint) }
        create.setOnClickListener { account("/api/auth/register", name, password, role.selectedItem.toString(), hint) }
        page.addView(enter)
        page.addView(create)
        page.addView(hint)
    }

    private fun account(path: String, name: EditText, password: EditText, role: String, hint: TextView) {
        val raw = name.text.toString().trim()
        val secret = password.text.toString()
        if (raw.length < 2 || secret.length < 8) {
            hint.text = "Use your name and a password of 8 characters."
            hint.setTextColor(ink)
            return
        }
        io.execute {
            val response = request("POST", path, JSONObject().put("name", raw).put("password", secret).put("role", role).toString())
            runOnUiThread {
                val body = if (response.startsWith("{")) JSONObject(response) else JSONObject()
                val session = body.optString("token")
                if (session.isEmpty()) {
                    hint.text = body.optString("error", "The desk did not accept that.")
                    return@runOnUiThread
                }
                prefs().edit().putString("token", session).putString("name", body.getString("name")).putString("role", body.getString("role")).apply()
                since = Instant.now().toString()
                load()
            }
        }
    }

    private fun load() {
        page.removeAllViews()
        page.addView(title("Al-mezzayn"))
        val heading = note(if (role() == "customer") "Open chairs" else "Today's chairs")
        page.addView(heading)
        page.addView(note(name()))
        val leave = quiet("Leave")
        leave.setOnClickListener {
            prefs().edit().clear().apply()
            signIn()
        }
        page.addView(leave)
        io.execute {
            val body = request("GET", "/api/board", null)
            runOnUiThread {
                if (body.startsWith("{") && !JSONObject(body).has("error")) paint(JSONObject(body))
                else heading.text = if (body.startsWith("{")) JSONObject(body).optString("error") else "The desk is not running."
            }
        }
    }

    private fun paint(board: JSONObject) {
        val seats = board.getJSONArray("seats")
        val services = board.getJSONArray("services")
        val bookings = board.getJSONArray("bookings")
        if (role() != "customer") {
            if (bookings.length() == 0) page.addView(note("No chairs booked today. The floor is open."))
            for (i in 0 until bookings.length()) bookingCard(bookings.getJSONObject(i), seats)
            return
        }
        page.addView(note("Your reservations"))
        var mine = 0
        for (i in 0 until bookings.length()) {
            mine += 1
            bookingCard(bookings.getJSONObject(i), seats)
        }
        if (mine == 0) page.addView(note("No reservation yet."))
        for (i in 0 until seats.length()) {
            val seat = seats.getJSONObject(i)
            page.addView(card(seat.getString("name")))
            page.addView(note(seat.getString("barber")))
            val free = seat.getJSONArray("free")
            if (free.length() == 0) {
                page.addView(note("Fully booked"))
                continue
            }
            page.addView(note("${free.length()} open"))
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            for (t in 0 until free.length()) {
                val slot = free.getString(t)
                val book = quiet(slot)
                book.setOnClickListener { chooseService(seat.getString("id"), slot, services) }
                row.addView(book)
            }
            val scroller = android.widget.HorizontalScrollView(this)
            scroller.addView(row)
            page.addView(scroller)
        }
    }

    private fun bookingCard(booking: JSONObject, seats: JSONArray) {
        page.addView(card(booking.getString("slot").substringAfter(" ") + "  " + booking.getString("customer")))
        page.addView(note("${booking.getString("service")} · ${booking.getString("seat")} · ${booking.getInt("price")}"))
        val times = JSONArray()
        times.put(booking.getString("slot").substringAfter(" "))
        for (i in 0 until seats.length()) {
            val seat = seats.getJSONObject(i)
            if (seat.getString("id") != booking.optString("seatId")) continue
            val free = seat.getJSONArray("free")
            for (t in 0 until free.length()) if (free.getString(t) != times.getString(0)) times.put(free.getString(t))
        }
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        for (t in 0 until times.length()) {
            val slot = times.getString(t)
            val move = quiet(slot)
            move.setOnClickListener {
                io.execute {
                    request("PATCH", "/api/bookings/" + booking.getString("id"), JSONObject().put("slot", slot).put("seatId", booking.optString("seatId")).toString())
                    runOnUiThread {
                        notify("Rescheduled: ${booking.getString("customer")} to $slot")
                        load()
                    }
                }
            }
            row.addView(move)
        }
        val scroller = android.widget.HorizontalScrollView(this)
        scroller.addView(row)
        page.addView(scroller)
        val cancel = button("Cancel booking")
        cancel.setOnClickListener {
            io.execute {
                request("DELETE", "/api/bookings/" + booking.getString("id"), null)
                runOnUiThread {
                    notify("Cancelled: ${booking.getString("customer")}")
                    load()
                }
            }
        }
        page.addView(cancel)
    }

    private fun chooseService(seatId: String, slot: String, services: JSONArray) {
        page.removeAllViews()
        page.addView(title(slot))
        page.addView(note("Choose a service, then reserve."))
        for (s in 0 until services.length()) {
            val service = services.getJSONObject(s)
            val book = button("${service.getString("name")} · ${service.getInt("price")}")
            book.setOnClickListener {
                io.execute {
                    val response = request("POST", "/api/bookings", JSONObject()
                        .put("seatId", seatId)
                        .put("service", service.getString("name"))
                        .put("customer", name())
                        .put("slot", slot)
                        .toString())
                    runOnUiThread {
                        val error = if (response.startsWith("{")) JSONObject(response).optString("error") else response
                        notify(if (error.isEmpty()) "Confirmed: ${name()} at $slot" else error)
                        load()
                    }
                }
            }
            page.addView(book)
        }
    }

    private fun pullNotices() {
        io.execute {
            val body = request("GET", "/api/notices?since=" + URLEncoder.encode(since, "UTF-8"), null)
            if (!body.startsWith("{")) return@execute
            val notes = JSONObject(body).optJSONArray("notices") ?: return@execute
            for (i in 0 until notes.length()) {
                val note = notes.getJSONObject(i)
                since = note.getString("at")
                runOnUiThread { notify(note.getString("text")) }
            }
        }
    }

    private fun request(method: String, path: String, body: String?): String {
        return try {
            val connection = URL(desk + path).openConnection() as HttpURLConnection
            connection.requestMethod = method
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            val session = token()
            if (session.isNotEmpty()) connection.setRequestProperty("Authorization", "Bearer $session")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray()) }
            }
            val stream = if (connection.responseCode >= 400) connection.errorStream else connection.inputStream
            stream?.bufferedReader()?.readText() ?: "{\"error\":\"The desk is not running.\"}"
        } catch (_: Exception) {
            "{\"error\":\"The desk is not running.\"}"
        }
    }

    private fun notify(text: String) {
        val notice = Notification.Builder(this, "bookings")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Al-mezzayn")
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(text.hashCode(), notice)
    }

    private fun prefs() = getSharedPreferences("mezzayn", MODE_PRIVATE)
    private fun token() = prefs().getString("token", "").orEmpty()
    private fun name() = prefs().getString("name", "").orEmpty()
    private fun role() = prefs().getString("role", "customer").orEmpty()

    private fun column(pad: Int): LinearLayout {
        val view = LinearLayout(this)
        view.orientation = LinearLayout.VERTICAL
        view.setPadding(pad, pad, pad, pad)
        return view
    }

    private fun title(text: String): TextView {
        val view = TextView(this)
        view.text = text
        view.textSize = 32f
        view.setTextColor(ink)
        view.typeface = Typeface.DEFAULT_BOLD
        view.setPadding(0, 12, 0, 8)
        return view
    }

    private fun note(text: String): TextView {
        val view = TextView(this)
        view.text = text
        view.textSize = 15f
        view.setTextColor(muted)
        view.setPadding(0, 4, 0, 8)
        return view
    }

    private fun card(text: String): TextView {
        val view = TextView(this)
        view.text = text
        view.textSize = 20f
        view.setTextColor(ink)
        view.setBackgroundColor(white)
        view.setPadding(28, 28, 28, 12)
        return view
    }

    private fun button(text: String) = control(text, ink, white)
    private fun quiet(text: String) = control(text, white, ink)

    private fun control(text: String, fill: Int, label: Int): Button {
        val view = Button(this)
        view.text = text
        view.setTextColor(label)
        view.setBackgroundColor(fill)
        view.isAllCaps = false
        view.gravity = Gravity.CENTER
        return view
    }

    private fun field(hint: String): EditText {
        val field = EditText(this)
        field.hint = hint
        field.setTextColor(ink)
        field.setHintTextColor(muted)
        field.setBackgroundColor(white)
        field.setPadding(28, 24, 28, 24)
        page.addView(field)
        return field
    }
}

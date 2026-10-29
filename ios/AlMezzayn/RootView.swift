import SwiftUI
import UserNotifications

private let paper = Color(red: 0.965, green: 0.965, blue: 0.955)
private let ink = Color(red: 0.07, green: 0.07, blue: 0.07)
private let burgundy = Color(red: 0.61, green: 0.11, blue: 0.18)
private let muted = Color(red: 0.23, green: 0.23, blue: 0.23)
private let line = Color(red: 0.78, green: 0.78, blue: 0.78)

struct RootView: View {
    @AppStorage("name") private var name = ""
    @AppStorage("role") private var role = ""
    @AppStorage("token") private var token = ""

    var body: some View {
        NavigationStack {
            if token.isEmpty || name.isEmpty || role.isEmpty {
                SignInView(name: $name, role: $role, token: $token)
            } else if role == "customer" {
                CustomerView()
            } else {
                StaffView()
            }
        }
        .tint(ink)
        .task(id: token) { await watchNotices() }
    }

    private func watchNotices() async {
        guard !token.isEmpty else { return }
        Desk.token = token
        let center = UNUserNotificationCenter.current()
        _ = try? await center.requestAuthorization(options: [.alert, .sound])
        var since = ISO8601DateFormatter().string(from: Date())
        while !Task.isCancelled {
            if let notes = try? await Desk.notices(since: since) {
                for note in notes {
                    since = note.at
                    let content = UNMutableNotificationContent()
                    content.title = "Al-mezzayn"
                    content.body = note.text
                    try? await center.add(UNNotificationRequest(identifier: note.id, content: content, trigger: nil))
                }
            }
            try? await Task.sleep(nanoseconds: 8_000_000_000)
        }
    }
}

struct SignInView: View {
    @Binding var name: String
    @Binding var role: String
    @Binding var token: String
    @State private var draft = ""
    @State private var password = ""
    @State private var picked = "customer"
    @State private var creating = false
    @State private var hint = ""

    var body: some View {
        VStack(spacing: 0) {
            ShopBar()
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    Text(creating ? "Create an account" : "Sign in")
                        .font(.system(size: 34, weight: .bold))
                        .foregroundStyle(ink)
                    Text("The same account opens the chair list.")
                        .foregroundStyle(muted)
                    field("Name", text: $draft, secure: false)
                    field("Password", text: $password, secure: true)
                    if creating {
                        roleRow("customer", "Customer", "Book an open chair")
                        roleRow("staff", "Staff", "See today's bookings")
                        roleRow("owner", "Owner", "Hours, sales, and exports")
                    }
                    if !hint.isEmpty {
                        Text(hint).foregroundStyle(ink).fontWeight(.semibold)
                    }
                    Button(creating ? "Create account" : "Sign in") { Task { await enter() } }
                        .buttonStyle(InkButton())
                    Button(creating ? "I already have an account" : "Create an account") {
                        creating.toggle()
                        hint = ""
                    }
                    .buttonStyle(OutlineButton())
                }
                .padding(20)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(paper)
    }

    private func field(_ title: String, text: Binding<String>, secure: Bool) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title).font(.subheadline.weight(.semibold)).foregroundStyle(ink)
            Group {
                if secure {
                    SecureField("", text: text, prompt: Text("At least 8 characters").foregroundStyle(muted))
                } else {
                    TextField("", text: text, prompt: Text("Name on the booking").foregroundStyle(muted))
                }
            }
            .textInputAutocapitalization(secure ? .never : .words)
            .autocorrectionDisabled()
            .foregroundStyle(ink)
            .padding()
            .background(.white, in: RoundedRectangle(cornerRadius: 8))
            .overlay(RoundedRectangle(cornerRadius: 8).stroke(ink, lineWidth: 1.5))
        }
    }

    private func roleRow(_ value: String, _ title: String, _ detail: String) -> some View {
        let on = picked == value
        return Button { picked = value } label: {
            VStack(alignment: .leading, spacing: 4) {
                Text(title).font(.headline).foregroundStyle(ink)
                Text(detail).foregroundStyle(muted)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(14)
            .background(.white, in: RoundedRectangle(cornerRadius: 8))
            .overlay(RoundedRectangle(cornerRadius: 8).stroke(on ? ink : line, lineWidth: on ? 2 : 1))
        }
        .buttonStyle(.plain)
    }

    private func enter() async {
        let trimmed = draft.trimmingCharacters(in: .whitespaces)
        guard trimmed.count >= 2, password.count >= 8 else {
            hint = "Use your name and a password of 8 characters."
            return
        }
        do {
            let session = try await (creating
                ? Desk.register(name: trimmed, password: password, role: picked)
                : Desk.login(name: trimmed, password: password))
            Desk.token = session.token
            hint = ""
            name = session.name
            role = session.role
            token = session.token
        } catch let DeskError.message(message) {
            hint = message
        } catch {
            hint = "The desk is not running."
        }
    }
}

struct CustomerView: View {
    @AppStorage("name") private var name = ""
    @AppStorage("role") private var role = ""
    @AppStorage("token") private var token = ""
    @State private var board: Board?
    @State private var error = ""
    @State private var notice = ""
    @State private var choice: Choice?

    var body: some View {
        VStack(spacing: 0) {
            ShopBar { leave() }
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    screenTitle("Open chairs", day: board?.day ?? "")
                    VStack(alignment: .leading, spacing: 2) {
                        Text(name).font(.headline).foregroundStyle(ink)
                        Text("This name goes on the booking.").font(.subheadline).foregroundStyle(muted)
                    }
                    if !notice.isEmpty { banner(notice) }
                    if !error.isEmpty { banner(error) }
                    if board == nil && error.isEmpty {
                        Text("Loading today's chairs.").foregroundStyle(muted)
                    }
                    let mine = (board?.bookings ?? []).filter { $0.customer == name }.sorted { $0.slot < $1.slot }
                    if !mine.isEmpty {
                        Text("Your reservations")
                            .font(.title3.weight(.bold))
                            .foregroundStyle(ink)
                        ForEach(mine) { booking in
                            VStack(alignment: .leading, spacing: 8) {
                                Text(String(booking.slot.suffix(5)))
                                    .font(.title3.weight(.bold))
                                    .monospacedDigit()
                                    .foregroundStyle(ink)
                                Text(booking.service).foregroundStyle(ink)
                                Text("\(booking.seat) · \(booking.barber) · \(booking.price)")
                                    .foregroundStyle(muted)
                                moveMenu(booking)
                                Button("Cancel booking") { Task { await cancel(booking) } }
                                    .buttonStyle(OutlineButton())
                            }
                            .padding(16)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .background(.white, in: RoundedRectangle(cornerRadius: 12))
                            .overlay(RoundedRectangle(cornerRadius: 12).stroke(line))
                        }
                    }
                    ForEach(board?.seats ?? []) { seat in
                        seatCard(seat)
                    }
                }
                .padding(20)
            }
        }
        .background(paper)
        .task { await reload() }
        .sheet(item: $choice) { item in
            ServiceSheet(seat: item.seat, slot: item.slot, services: board?.services ?? []) { service in
                Task { await book(item, service) }
            }
        }
    }

    private func seatCard(_ seat: Seat) -> some View {
        let morning = seat.free.filter { $0 < "12:00" }
        let afternoon = seat.free.filter { $0 >= "12:00" }
        return VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .firstTextBaseline) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(seat.name).font(.headline).foregroundStyle(ink)
                    Text(seat.barber).foregroundStyle(muted)
                }
                Spacer()
                Text(seat.free.isEmpty ? "Fully booked" : "\(seat.free.count) open")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(ink)
            }
            if seat.free.isEmpty {
                Text("No open time today.").foregroundStyle(muted)
            } else {
                timeGroup("Morning", morning, seat: seat)
                timeGroup("Afternoon", afternoon, seat: seat)
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(.white, in: RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(line))
    }

    @ViewBuilder
    private func timeGroup(_ title: String, _ slots: [String], seat: Seat) -> some View {
        if !slots.isEmpty {
            VStack(alignment: .leading, spacing: 8) {
                Text(title).font(.caption.weight(.semibold)).foregroundStyle(muted)
                Flow(spacing: 8) {
                    ForEach(slots, id: \.self) { slot in
                        Button(slot) { choice = Choice(seat: seat, slot: slot) }
                            .buttonStyle(TimeChip())
                    }
                }
            }
        }
    }

    private func reload() async {
        Desk.token = token
        do {
            board = try await Desk.board()
            error = ""
        } catch {
            self.error = "The desk is not running."
        }
    }

    private func book(_ item: Choice, _ service: Service) async {
        do {
            board = try await Desk.book(seatId: item.seat.id, service: service.name, customer: name, slot: item.slot)
            notice = "\(service.name) booked at \(item.slot)."
            error = ""
            choice = nil
        } catch let DeskError.message(message) {
            error = message
            notice = ""
            choice = nil
        } catch {
            self.error = "The desk is not running."
            choice = nil
        }
    }

    private func cancel(_ booking: Booking) async {
        do {
            board = try await Desk.cancel(id: booking.id)
            notice = "Cancelled."
        } catch let DeskError.message(message) {
            error = message
        } catch {
            self.error = "That booking could not be cancelled."
        }
    }

    private func move(_ booking: Booking, _ slot: String) async {
        do {
            board = try await Desk.move(id: booking.id, slot: slot, seatId: booking.seatId)
            notice = "Moved to \(slot)."
        } catch let DeskError.message(message) {
            error = message
        } catch {
            self.error = "That booking could not be moved."
        }
    }

    @ViewBuilder
    private func moveMenu(_ booking: Booking) -> some View {
        let seat = board?.seats.first { $0.id == booking.seatId }
        let current = String(booking.slot.suffix(5))
        let times = [current] + (seat?.free ?? []).filter { $0 != current }
        Menu("Move") {
            ForEach(times, id: \.self) { slot in
                Button(slot) { Task { await move(booking, slot) } }
            }
        }
        .foregroundStyle(ink)
    }

    private func leave() {
        name = ""
        role = ""
        token = ""
        Desk.token = ""
    }
}

struct StaffView: View {
    @AppStorage("name") private var name = ""
    @AppStorage("role") private var role = ""
    @AppStorage("token") private var token = ""
    @State private var board: Board?
    @State private var error = ""
    @State private var pending: Booking?

    private var bookings: [Booking] {
        (board?.bookings ?? []).sorted { $0.slot < $1.slot }
    }

    var body: some View {
        VStack(spacing: 0) {
            ShopBar { leave() }
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    screenTitle("Today's chairs", day: board?.day ?? "")
                    VStack(alignment: .leading, spacing: 2) {
                        Text(name).font(.headline).foregroundStyle(ink)
                        Text(role == "owner" ? "Owner" : "Staff").font(.subheadline).foregroundStyle(muted)
                    }
                    if !error.isEmpty { banner(error) }
                    if board == nil && error.isEmpty {
                        Text("Loading today's chairs.").foregroundStyle(muted)
                    }
                    if board != nil && bookings.isEmpty {
                        Text("No chairs booked today. The floor is open.").foregroundStyle(muted)
                    }
                    ForEach(bookings) { booking in
                        VStack(alignment: .leading, spacing: 8) {
                            HStack(alignment: .firstTextBaseline) {
                                Text(String(booking.slot.suffix(5)))
                                    .font(.title3.weight(.bold))
                                    .monospacedDigit()
                                Spacer()
                                Text("\(booking.price)")
                                    .font(.headline)
                                    .monospacedDigit()
                            }
                            Text(booking.customer).font(.headline)
                            Text("\(booking.service) · \(booking.seat) · \(booking.barber)")
                                .foregroundStyle(muted)
                            Button("Cancel booking") { pending = booking }
                                .buttonStyle(OutlineButton())
                            moveMenu(booking)
                        }
                        .padding(16)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(.white, in: RoundedRectangle(cornerRadius: 12))
                        .overlay(RoundedRectangle(cornerRadius: 12).stroke(line))
                    }
                }
                .padding(20)
            }
        }
        .background(paper)
        .task { await reload() }
        .confirmationDialog(
            "Cancel this booking?",
            isPresented: Binding(get: { pending != nil }, set: { if !$0 { pending = nil } }),
            titleVisibility: .visible
        ) {
            Button("Cancel booking", role: .destructive) {
                if let pending { Task { await cancel(pending) } }
            }
            Button("Keep it", role: .cancel) {}
        } message: {
            Text(pending.map { "\($0.customer) at \($0.slot.suffix(5))" } ?? "")
        }
    }

    private func reload() async {
        Desk.token = token
        do {
            board = try await Desk.board()
            error = ""
        } catch {
            self.error = "The desk is not running."
        }
    }

    private func cancel(_ booking: Booking) async {
        do {
            board = try await Desk.cancel(id: booking.id)
            pending = nil
        } catch {
            self.error = "That booking could not be cancelled."
            pending = nil
        }
    }

    private func leave() {
        name = ""
        role = ""
        token = ""
        Desk.token = ""
    }

    @ViewBuilder
    private func moveMenu(_ booking: Booking) -> some View {
        let seat = board?.seats.first { $0.id == booking.seatId }
        let current = String(booking.slot.suffix(5))
        let times = [current] + (seat?.free ?? []).filter { $0 != current }
        Menu("Move") {
            ForEach(times, id: \.self) { slot in
                Button(slot) { Task { await move(booking, slot) } }
            }
        }
        .foregroundStyle(ink)
    }

    private func move(_ booking: Booking, _ slot: String) async {
        do {
            board = try await Desk.move(id: booking.id, slot: slot, seatId: booking.seatId)
            pending = nil
        } catch {
            self.error = "That booking could not be moved."
        }
    }
}

private struct Choice: Identifiable {
    let seat: Seat
    let slot: String
    var id: String { seat.id + slot }
}

private struct ServiceSheet: View {
    let seat: Seat
    let slot: String
    let services: [Service]
    let pick: (Service) -> Void
    @State private var selected = ""
    @State private var hint = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(slot).font(.system(size: 32, weight: .bold)).foregroundStyle(ink)
            Text("\(seat.name) · \(seat.barber)")
                .foregroundStyle(muted)
            Text("Choose a service, then reserve.")
                .foregroundStyle(ink)
            ForEach(services) { service in
                Button { selected = service.name } label: {
                    HStack {
                        Text(service.name).foregroundStyle(ink)
                        Spacer()
                        Text("\(service.price)").monospacedDigit().foregroundStyle(ink)
                    }
                    .padding(14)
                    .background(.white, in: RoundedRectangle(cornerRadius: 8))
                    .overlay(RoundedRectangle(cornerRadius: 8).stroke(selected == service.name ? ink : line, lineWidth: selected == service.name ? 2 : 1))
                }
                .buttonStyle(.plain)
            }
            if !hint.isEmpty {
                Text(hint).fontWeight(.semibold).foregroundStyle(ink)
            }
            Button("Reserve") {
                guard let service = services.first(where: { $0.name == selected }) else {
                    hint = "Choose a service."
                    return
                }
                pick(service)
            }
            .buttonStyle(InkButton())
            Spacer()
        }
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(paper)
        .presentationDetents([.medium, .large])
    }
}

private struct Mark: Shape {
    func path(in rect: CGRect) -> Path {
        var path = Path()
        path.move(to: CGPoint(x: 48, y: 48))
        path.addLine(to: CGPoint(x: 88, y: 48))
        path.addLine(to: CGPoint(x: 128, y: 88))
        path.addLine(to: CGPoint(x: 168, y: 48))
        path.addLine(to: CGPoint(x: 208, y: 48))
        path.addLine(to: CGPoint(x: 208, y: 208))
        path.addLine(to: CGPoint(x: 168, y: 208))
        path.addLine(to: CGPoint(x: 168, y: 128))
        path.addLine(to: CGPoint(x: 128, y: 168))
        path.addLine(to: CGPoint(x: 88, y: 128))
        path.addLine(to: CGPoint(x: 88, y: 208))
        path.addLine(to: CGPoint(x: 48, y: 208))
        path.closeSubpath()
        let scale = min(rect.width, rect.height) / 160
        let fitted = CGAffineTransform(scaleX: scale, y: scale).translatedBy(x: -48, y: -48)
        return path.applying(fitted)
    }
}

private struct ShopBar: View {
    var leave: (() -> Void)? = nil

    var body: some View {
        HStack(spacing: 10) {
            Mark()
                .fill(burgundy)
                .frame(width: 22, height: 22)
            Text("Al-mezzayn")
                .font(.headline)
                .foregroundStyle(ink)
            Spacer()
            if let leave {
                Button("Leave", action: leave)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(ink)
            }
        }
        .padding(.horizontal, 20)
        .padding(.vertical, 16)
        .frame(maxWidth: .infinity)
        .background(paper)
        .overlay(alignment: .bottom) { Rectangle().fill(line).frame(height: 1) }
    }
}

private func screenTitle(_ title: String, day: String) -> some View {
    VStack(alignment: .leading, spacing: 4) {
        Text(title).font(.system(size: 32, weight: .bold)).foregroundStyle(ink)
        if !day.isEmpty {
            Text(day).foregroundStyle(muted)
        }
    }
}

private func banner(_ text: String) -> some View {
    Text(text)
        .foregroundStyle(ink)
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(12)
        .background(.white, in: RoundedRectangle(cornerRadius: 8))
        .overlay(RoundedRectangle(cornerRadius: 8).stroke(ink, lineWidth: 1))
}

private struct InkButton: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .frame(maxWidth: .infinity)
            .padding(.vertical, 12)
            .padding(.horizontal, 14)
            .background(ink, in: RoundedRectangle(cornerRadius: 8))
            .foregroundStyle(.white)
            .fontWeight(.semibold)
            .scaleEffect(configuration.isPressed ? 0.98 : 1)
            .animation(.easeOut(duration: 0.12), value: configuration.isPressed)
    }
}

private struct OutlineButton: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .frame(maxWidth: .infinity)
            .padding(.vertical, 12)
            .padding(.horizontal, 14)
            .foregroundStyle(ink)
            .fontWeight(.semibold)
            .background(.white, in: RoundedRectangle(cornerRadius: 8))
            .overlay(RoundedRectangle(cornerRadius: 8).stroke(ink, lineWidth: 1.5))
            .scaleEffect(configuration.isPressed ? 0.98 : 1)
            .animation(.easeOut(duration: 0.12), value: configuration.isPressed)
    }
}

private struct TimeChip: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.subheadline.weight(.semibold))
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .foregroundStyle(configuration.isPressed ? Color.white : ink)
            .background(configuration.isPressed ? ink : Color.white, in: Capsule())
            .overlay(Capsule().stroke(ink, lineWidth: 1))
            .scaleEffect(configuration.isPressed ? 0.97 : 1)
            .animation(.easeOut(duration: 0.12), value: configuration.isPressed)
    }
}

private struct Flow: Layout {
    var spacing: CGFloat = 8

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let width = proposal.width ?? 0
        var x: CGFloat = 0
        var y: CGFloat = 0
        var row: CGFloat = 0
        for view in subviews {
            let size = view.sizeThatFits(.unspecified)
            if x > 0, x + size.width > width {
                x = 0
                y += row + spacing
                row = 0
            }
            row = max(row, size.height)
            x += size.width + spacing
        }
        return CGSize(width: width, height: y + row)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var x = bounds.minX
        var y = bounds.minY
        var row: CGFloat = 0
        for view in subviews {
            let size = view.sizeThatFits(.unspecified)
            if x > bounds.minX, x + size.width > bounds.maxX {
                x = bounds.minX
                y += row + spacing
                row = 0
            }
            view.place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(size))
            row = max(row, size.height)
            x += size.width + spacing
        }
    }
}

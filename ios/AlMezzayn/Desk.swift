import Foundation

struct Service: Decodable, Identifiable {
    let name: String
    let price: Int
    var id: String { name }
}

struct Hours: Decodable {
    let open: String
    let close: String
}

struct Seat: Decodable, Identifiable {
    let id: String
    let name: String
    let barber: String
    let free: [String]
}

struct Booking: Decodable, Identifiable {
    let id: String
    let seatId: String
    let seat: String
    let barber: String
    let service: String
    let customer: String
    let slot: String
    let price: Int
}

struct Board: Decodable {
    let day: String
    let hours: Hours
    let services: [Service]
    let seats: [Seat]
    let bookings: [Booking]
}

struct Session: Decodable {
    let token: String
    let name: String
    let role: String
}

struct ShopNotice: Decodable, Identifiable {
    let id: String
    let at: String
    let kind: String
    let text: String
}

private struct NoticeList: Decodable {
    let notices: [ShopNotice]
}

enum DeskError: Error {
    case message(String)
}

enum Desk {
    static let root = URL(string: "http://192.168.1.207:8093")!
    static var token = ""

    static func register(name: String, password: String, role: String) async throws -> Session {
        try await auth("/api/auth/register", ["name": name, "password": password, "role": role])
    }

    static func login(name: String, password: String) async throws -> Session {
        try await auth("/api/auth/login", ["name": name, "password": password])
    }

    static func board() async throws -> Board {
        try JSONDecoder().decode(Board.self, from: try await send(request("GET", "api/board")))
    }

    static func book(seatId: String, service: String, customer: String, slot: String) async throws -> Board {
        let data = try await send(request("POST", "api/bookings", [
            "seatId": seatId, "service": service, "customer": customer, "slot": slot,
        ]))
        return try JSONDecoder().decode(Board.self, from: data)
    }

    static func move(id: String, slot: String, seatId: String) async throws -> Board {
        let data = try await send(request("PATCH", "api/bookings/\(id)", ["slot": slot, "seatId": seatId]))
        return try JSONDecoder().decode(Board.self, from: data)
    }

    static func cancel(id: String) async throws -> Board {
        try JSONDecoder().decode(Board.self, from: try await send(request("DELETE", "api/bookings/\(id)")))
    }

    static func notices(since: String) async throws -> [ShopNotice] {
        var parts = URLComponents(url: root.appendingPathComponent("api/notices"), resolvingAgainstBaseURL: false)!
        parts.queryItems = [URLQueryItem(name: "since", value: since)]
        var call = URLRequest(url: parts.url!)
        call.httpMethod = "GET"
        if !token.isEmpty { call.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
        let list = try JSONDecoder().decode(NoticeList.self, from: try await send(call))
        return list.notices
    }

    private static func auth(_ path: String, _ body: [String: String]) async throws -> Session {
        try JSONDecoder().decode(Session.self, from: try await send(request("POST", path.trimmingCharacters(in: CharacterSet(charactersIn: "/")), body)))
    }

    private static func request(_ method: String, _ path: String, _ body: [String: String]? = nil) -> URLRequest {
        var call = URLRequest(url: root.appendingPathComponent(path))
        call.httpMethod = method
        if !token.isEmpty { call.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
        if let body {
            call.setValue("application/json", forHTTPHeaderField: "Content-Type")
            call.httpBody = try? JSONSerialization.data(withJSONObject: body)
        }
        return call
    }

    private static func send(_ request: URLRequest) async throws -> Data {
        let (data, response) = try await URLSession.shared.data(for: request)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        if status >= 400 {
            let body = try? JSONSerialization.jsonObject(with: data) as? [String: String]
            throw DeskError.message(body?["error"] ?? "The desk did not accept that.")
        }
        return data
    }
}

# Al-mezzayn

A barbershop counter. The customer books a seat on the phone. The owner watches the day in the browser.

The shop day follows Cairo. A seat and a time can be booked once. The customer books under their own name and can move or cancel that booking. Staff and the owner can book a walk-in, move it, or cancel it. The owner sets the hours, adds a seat, and reads the day, the last seven days, and the month.

## Accounts

Sign-up and sign-in are real accounts. The roles are customer, staff, and owner. The desk in the browser is for the owner.

## The phone

- `ios` is the SwiftUI app.
- `app` is the Android app.

A booking, a move, and a cancellation show up as a notice while the app is open, and on the desk.

## The desk

The owner sees the schedule, charts, the working hours, a walk-in, a new seat, and export as CSV or PDF.

The same API is the Kotlin server in `server` and the Node desk in `api`. The published desk is [mezzayn.vercel.app](https://mezzayn.vercel.app). Bookings live in MongoDB.

## Mark

The M mark is in `logo`.

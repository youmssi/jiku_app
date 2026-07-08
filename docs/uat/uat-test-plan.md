# Jikū — UAT Test Plan & Client Onboarding Script

**Status:** Draft for Product Owner + Tech Lead sign-off (JIKU-39 DoD)
**Audience:** UAT facilitator running sessions with real prospective clients (JIKU-40)
**Environment:** Staging only — never production data

This is the structured script a facilitator follows with each UAT participant. It is
deliberately task-by-task: each task has a concrete success criterion and a
structured feedback question, and tasks are grouped by the role performing them
(organizer, guest, validator), because their friction points differ.

Do not improvise this in the moment with a client present. Every participant runs
the same script so responses are comparable and can be prioritized objectively.

---

## 0. Before the session (facilitator checklist)

- [ ] Staging environment reachable; seed/demo data reset if needed (`./gradlew seedDemoData`).
- [ ] Participant is using **their own device** and, where feasible, **their own
      network** — this matters most for the check-in scenarios.
- [ ] Feedback form (Section 5) open and ready, one response set per participant.
- [ ] Consent to record notes; explain this is a test of the product, not of them —
      "there are no wrong answers; confusion is useful data."
- [ ] Capture participant profile: role(s), organization type (event agency / hotel /
      religious or community organizer / other), technical comfort (low/med/high).

Rate each task outcome as: **Pass** (met the success criterion unaided),
**Pass with friction** (succeeded but hesitated, misclicked, or needed a hint), or
**Fail** (could not complete). Record verbatim quotes where possible.

---

## 1. Organizer tasks

### O1 — Registration and branding setup
- **Do:** Create an organizer account, then set the white-label branding (display
  name, primary color, logo) for the tenant.
- **Success:** Account created and logged in; branding saved and visible.
- **Ask:** How clear was it what to enter, and where? Did the branding preview match
  what you expected? What, if anything, felt unnecessary or missing?

### O2 — Event creation and configuration
- **Do:** Create an event, set its date/time and **timezone**, location, capacity,
  and settings (seating, transfer, overbooking, invitation channels). Save as draft,
  then publish.
- **Success:** Event created with the intended settings; published without the
  facilitator explaining any field.
- **Ask:** Were any settings unclear or worded confusingly? Did the timezone behavior
  make sense to you? What would you change about the create/publish flow?

### O3 — Guest list import (CSV)
- **Do:** Import a guest list from a CSV (provide a sample with a mix of valid rows,
  a duplicate, and a row missing both email and phone).
- **Success:** Valid guests imported; duplicates and invalid rows reported clearly,
  not silently dropped.
- **Ask:** Did you understand what was imported vs. skipped, and why? Would the error
  feedback let you fix your file without help?

### O4 — Sending invitations (email and WhatsApp)
- **Do:** Send invitations over **both** email and WhatsApp.
- **Success:** Send confirmed; per-guest delivery status visible; if the guest
  allowance is exceeded, the paywall message and upgrade path appear (JIKU-34).
- **Ask:** Did you trust that invitations actually went out? Was the delivery status
  understandable? If you hit the allowance limit, was the next step obvious?

### O5 — Monitoring the dashboard
- **Do:** Open the event dashboard during/after invitations and (later) during
  check-in.
- **Success:** RSVP counts, usage/allowance, deliverability, and check-in progress
  are legible at a glance.
- **Ask:** What number did you look for first? Was anything hard to find or interpret?
  What one metric would you add?

---

## 2. Guest tasks

### G1 — RSVP end-to-end
- **Do:** Open the invitation link on a phone, read it, and confirm attendance (then,
  in a second pass, decline and re-confirm to test reversibility).
- **Success:** RSVP recorded; ticket issued on confirmation; event time shown in the
  **event's** timezone regardless of the guest's location.
- **Ask:** Did the invitation feel trustworthy and clear? Was confirming easy on your
  phone? Did the date/time make sense for where the event is?

### G2 — Requesting data deletion (JIKU-36)
- **Do:** From the invitation or ticket page, use "Request deletion of my data" and
  confirm.
- **Success:** Clear irreversible-action warning; after confirming, a clear "your data
  has been deleted" state; the privacy notice is reachable from the page.
- **Ask:** Was it clear what deletion would do and that it was permanent? Did you feel
  in control of your data? Was the privacy explanation understandable in under a minute?

---

## 3. Validator tasks

### V1 — Check-in under normal network conditions
- **Do:** Open the validator link, scan a guest's ticket QR, and also check in a guest
  via search (no QR).
- **Success:** Correct guest checked in; glanceable success/failure feedback; an
  already-checked-in ticket is clearly flagged (with who/when), not silently passed.
- **Ask:** At arm's length, could you tell success from failure instantly? Was the
  search fallback fast enough for a queue? What would slow you down at a busy door?

### V2 — Check-in under degraded / offline network (JIKU-25)
- **Do:** Pre-sync the roster, then **genuinely** degrade the network (airplane mode,
  or a real low-connectivity spot in the venue — not only a browser dev-tool throttle).
  Check several guests in offline, then restore connectivity and let the queue sync.
- **Success:** Check-ins succeed offline and are queued; the online/offline and queued
  indicators are accurate; on reconnection the queue syncs and conflicts resolve
  first-scan-wins without losing check-ins.
- **Ask:** Did you trust that offline check-ins were saved? Was it clear you were
  offline and how many were pending? After reconnecting, did you believe everything
  synced? Would you be comfortable running a real door on this?

---

## 4. Cross-role wrap-up questions (ask every participant)

- Overall, how confident would you be running a real event on this, from setup to the
  door? (1–5, and why.)
- What was the single most confusing moment across everything you did today?
- What is the one thing that, if it worked differently, would most change your mind
  about using this?
- Would you recommend it to another organizer like you? Why or why not?

---

## 5. Feedback form (structured — one set per participant)

Record this in a shared form/sheet (not free-flowing notes only), so responses are
comparable across participants and can be prioritized objectively afterward.

**Participant:** id · role(s) · org type · technical comfort · device · network

For **each task** (O1–O5, G1–G2, V1–V2):

| Field | Values |
|---|---|
| Outcome | Pass / Pass with friction / Fail |
| Time to complete | approximate |
| Friction points | free text (what hesitated, misclicked, needed a hint) |
| Feeling (1–5) | 1 = frustrating … 5 = effortless |
| Verbatim quote | optional but valuable |
| Suggested change | free text |

**Session-level:**

| Field | Values |
|---|---|
| Overall confidence (1–5) | + why |
| Most confusing moment | free text |
| Highest-impact change | free text |
| Would recommend? | Yes / No + why |
| Facilitator severity call, per issue | **Blocking (must fix before V1)** / Non-blocking (V1.1+/V2 backlog) |

The **Blocking vs. Non-blocking** column is what feeds the consolidated findings
document (JIKU-40) and, from there, remediation (JIKU-41) before the V1 release.

---

## 6. Sign-off

- [ ] Reviewed and approved by the **Product Owner**.
- [ ] Reviewed and approved by the **Tech Lead**.

Recruitment (JIKU-40) does not begin until both approvals are recorded here.

-- ============================================================================
-- CP-STATION :: Mock Interview feature
-- Run once against the Neon PostgreSQL database.
-- Safe to re-run: every statement is IF NOT EXISTS / idempotent.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- Expert / mentor profile.
-- A mentor is an existing user with an approved row here. users.role is left
-- untouched ('user' | 'admin') so no existing auth check changes behaviour.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS mentors (
    id                 SERIAL PRIMARY KEY,
    user_id            INTEGER UNIQUE NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    display_name       TEXT NOT NULL,
    headline           TEXT,
    bio                TEXT,
    company            TEXT,
    years_experience   INTEGER DEFAULT 0,
    hourly_rate_bdt    INTEGER NOT NULL DEFAULT 500,
    photo_url          TEXT,
    status             TEXT NOT NULL DEFAULT 'pending',
    rating_avg         NUMERIC(3,2) NOT NULL DEFAULT 0,
    rating_count       INTEGER NOT NULL DEFAULT 0,
    sessions_completed INTEGER NOT NULL DEFAULT 0,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- status: pending | approved | rejected | suspended
CREATE INDEX IF NOT EXISTS idx_mentors_status ON mentors (status);

-- ---------------------------------------------------------------------------
-- Expertise tags. This is what a student filters the mentor list by.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS mentor_stacks (
    id        SERIAL PRIMARY KEY,
    mentor_id INTEGER NOT NULL REFERENCES mentors(id) ON DELETE CASCADE,
    stack     TEXT NOT NULL,
    UNIQUE (mentor_id, stack)
);

CREATE INDEX IF NOT EXISTS idx_mentor_stacks_stack ON mentor_stacks (stack);

-- ---------------------------------------------------------------------------
-- Availability. The mentor publishes fixed-length windows (see SLOT_MINUTES in
-- MentorPortalHandler); booking claims one.
-- meeting_link is the mentor's own Google Meet / Zoom URL.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS mentor_slots (
    id           SERIAL PRIMARY KEY,
    mentor_id    INTEGER NOT NULL REFERENCES mentors(id) ON DELETE CASCADE,
    start_at     TIMESTAMPTZ NOT NULL,
    end_at       TIMESTAMPTZ NOT NULL,
    status       TEXT NOT NULL DEFAULT 'open',
    meeting_link TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (mentor_id, start_at)
);

-- status: open | held | booked | cancelled
CREATE INDEX IF NOT EXISTS idx_slots_lookup ON mentor_slots (mentor_id, status, start_at);

-- ---------------------------------------------------------------------------
-- Booking. amount_bdt is frozen at booking time so a later rate change on the
-- mentor profile never rewrites the price of an existing booking.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS bookings (
    id               SERIAL PRIMARY KEY,
    slot_id          INTEGER NOT NULL REFERENCES mentor_slots(id),
    mentor_id        INTEGER NOT NULL REFERENCES mentors(id),
    student_id       INTEGER NOT NULL REFERENCES users(id),
    stack            TEXT NOT NULL,
    notes            TEXT,
    amount_bdt       INTEGER NOT NULL,
    platform_fee_bdt INTEGER NOT NULL DEFAULT 0,
    status           TEXT NOT NULL DEFAULT 'pending_payment',
    meeting_link     TEXT,
    hold_expires_at  TIMESTAMPTZ,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- status: pending_payment | payment_review | confirmed | completed
--       | cancelled | expired | no_show

-- A slot may carry at most one *live* booking. Deliberately partial rather than
-- a plain UNIQUE column: once a booking is cancelled or expires the slot goes
-- back on sale, and the next student must be able to book that same slot.
CREATE UNIQUE INDEX IF NOT EXISTS idx_bookings_active_slot
    ON bookings (slot_id)
    WHERE status IN ('pending_payment', 'payment_review', 'confirmed', 'completed');

CREATE INDEX IF NOT EXISTS idx_bookings_student ON bookings (student_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_bookings_mentor  ON bookings (mentor_id, status);
CREATE INDEX IF NOT EXISTS idx_bookings_hold    ON bookings (status, hold_expires_at);

-- ---------------------------------------------------------------------------
-- Payment attempts. provider='manual' is the bKash send-money + TrxID flow;
-- the same table serves a real gateway later without a schema change.
-- idempotency_key blocks a double-charge on retry or webhook replay.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS payments (
    id              SERIAL PRIMARY KEY,
    booking_id      INTEGER NOT NULL REFERENCES bookings(id) ON DELETE CASCADE,
    provider        TEXT NOT NULL DEFAULT 'manual',
    amount_bdt      INTEGER NOT NULL,
    status          TEXT NOT NULL DEFAULT 'initiated',
    provider_txn_id TEXT,
    payer_msisdn    TEXT,
    idempotency_key TEXT UNIQUE,
    raw_payload     TEXT,
    verified_by     INTEGER REFERENCES users(id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- status: initiated | submitted | paid | failed | refund_pending | refunded
CREATE INDEX IF NOT EXISTS idx_payments_booking ON payments (booking_id);
CREATE INDEX IF NOT EXISTS idx_payments_status  ON payments (status, created_at DESC);

-- A given bKash transaction id may only ever be claimed once.
CREATE UNIQUE INDEX IF NOT EXISTS idx_payments_txn
    ON payments (provider, provider_txn_id)
    WHERE provider_txn_id IS NOT NULL;

-- ---------------------------------------------------------------------------
-- Two-way feedback: the student rates the mentor, and the mentor writes the
-- evaluation the student actually paid for.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS interview_feedback (
    id              SERIAL PRIMARY KEY,
    booking_id      INTEGER UNIQUE NOT NULL REFERENCES bookings(id) ON DELETE CASCADE,
    student_rating  INTEGER CHECK (student_rating BETWEEN 1 AND 5),
    student_comment TEXT,
    mentor_verdict  TEXT,
    mentor_notes    TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- mentor_verdict: hire | lean_hire | no_hire
